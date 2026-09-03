# ClipSync v0.2.0 真机测试总结

日期：2026-09-01
测试身份：真实用户（替朋友验证项目）
环境：Windows 11 Pro（LENOVO 83DG）+ Android（vivo V2426A），同一局域网

\---

## 一、任务背景与信息分层

朋友开发的局域网剪贴板同步工具「剪剪相传 ClipSync」v0.2.0（Windows WPF + Android Kotlin，P2P、TLS 1.3 证书钉扎、无云端）。

本次测试分两阶段：

- **使用阶段（用户/体验）**：以普通用户身份直接使用，使用前不读文档、不读代码，只记录实际看到的现象与主观感受。
- **分析定位阶段**：使用遇阻后，再读源码/文档、跑网络与崩溃诊断，定位阻碍成因并提出优化建议。

为区分信息来源，下文每个问题按 **用户/体验** 与 **分析定位** 两层标注。其中：问题 2 的崩溃属"用户有模糊印象、但当时未识别为崩溃、未明确反馈"；问题 5 是用户主动提问引发的调查，并非"使用中遇到的阻碍"。

\---

## 二、核心问题与结论

### 问题 1：Android 扫码后一直"配对失败"，批准窗口不弹（主问题）

**用户/体验**：手机和电脑连同一个 Wi-Fi。用手机扫电脑上弹出的配对二维码，手机提示"请在电脑的剪剪相传窗口中批准此手机"，但电脑上始终没看到哪里能点批准，等了约 90 秒后手机报"配对失败"。

**分析定位**：不是应用坏了，是 **Windows 防火墙没放行 TCP 47654**，手机的配对确认请求被静默丢弃，应用根本没收到请求，批准窗口自然永远不会弹。

**证据链（全部实测）**：

|检查项|结果|含义|
|-|-|-|
|手机在局域网|`ping 192.168.124.6` 通（73ms）|网络层没问题|
|电脑 WLAN|`192.168.124.2/24`，网关 `192.168.124.1`|二维码会带上真实局域网地址|
|应用监听|`192.168.124.2:47654` 正常监听|Windows 端服务在跑|
|防火墙策略|BlockInbound, AllowOutbound|默认拦所有未放行入站|
|47654 放行规则|完全没有（netsh / Get-NetFirewallRule 都查不到）|关键|
|尝试自动加规则|失败：拒绝访问（会话非管理员）|需要管理员权限|

**关键坑**：`Test-NetConnection` 本机连本机走的是回环优化，绕过入站规则，**不能证明手机能连上**。

**修复**：管理员 PowerShell 执行放行规则后配对立即成功：

```powershell
New-NetFirewallRule -DisplayName "ClipSync 47654" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 47654 -Profile Any
```

数据库 `devices` 表出现配对记录 `vivo V2426A`（未撤销），最硬证据。

**网络类型矛盾澄清**：我最初读到 WLAN 为"公用网络"，用户反馈"本来就是专用网络"，复查时已变为"专用"。无论公用/专用，BlockInbound 策略都会拦未放行端口——**缺 47654 放行规则才是根因，不是网络分类标签**。

### 问题 2：应用自身崩溃（独立 Bug，与配对无关）

**用户/体验（模糊印象，未识别为崩溃）**：使用中隐约觉得点托盘图标时程序"闪了一下就退出了"，有印象，但当时没把它当成崩溃、也没在反馈里点出来。

**分析定位**：用 `dotnet-dump` 分析崩溃转储（`ClipSync.App.exe.43968.dmp`，位于本机 `C:\Users\nc17\AppData\Local\CrashDumps\`，未随包附送）实证：

```
System.InvalidOperationException
  System.Windows.Window.VerifyCanShow()   ← WPF 关闭阶段禁止再 Show 窗口
  System.Windows.Window.Show()
  TrayFlyoutWindow.ShowFlyout()           ← 托盘悬浮窗
  TrayLeftMouseUp                         ← 单击托盘图标
  ─────────────────────────────────────
  App.OnExit → Application.DoShutdown()   ← 应用正在退出
```

**根因**：退出过程中单击托盘图标 → 试图在 Shutdown 阶段 Show 悬浮窗 → WPF 抛异常 → **App 没有注册任何全局异常处理器**（`DispatcherUnhandledException` 都没有）→ 异常击穿进程（`0xc000041d`）。属于"点着点着就闪退"的真实隐患。

### 问题 3：UI 描述负向内容过多

**用户/体验**：界面上安全/权限类说明读起来负向话术太多（"绝不 / 不会 / 静默"），对真实用户不提供信息增量，有种"此地无银三百两"的感觉。

**分析定位**：该判断部分成立，但量化后并非"大量"——全默认中文字符串文件约 460 条中，含负向表述的共 15 条，集中在引导、特权同意、备份/清空、诊断几处。典型：

* `Conduit\_Privileged\_Desc` "全程需你明确同意，**绝不静默**"
* `Conduit\_Privileged\_ConsentBody` "adb **只**在你每次点击时运行，**不会**常驻或**静默**执行"
* `Onboarding\_Privileged\_Consent` "**绝不**常驻或**静默**执行，每一步都需要你的明确同意"
* `Diag\_Heading` "仅状态码与时间，**绝不含**剪贴板正文"
* `Onboarding\_Honesty\_Body` "剪剪相传**不会假装做到**……做不到就直说"

**建议标准（修正）**：分两类处理——(1) 实质隐私/行为承诺应保留，如"剪贴板正文永不出现在日志""撤销不会远程删除对方已收到内容"，删掉反而降低可信度；(2) 自我辩解式话术才改正向行为描述，如上文"绝不静默 / 不会假装做到"。例如改成："点击『启动特权直读』时，本机会通过 adb 向手机发送一条启动命令；其他时候不调用 adb。"

**风险告知（补充）**：这些负向提示并非空穴来风——它们对应真实风险，不是被害妄想。与其把风险认知零散堆在界面文案里，不如由应用提供一份**使用前必读（或建议阅读）的文档/协议**，集中告知真实风险：① 设备是否私人可信——已配对设备可读取同步给它的正文，且撤销不能远程擦除对方已收到的副本；② 剪贴板内容本身可被前台网页经粘贴事件或授权读取，存在被网站抓取的可能（此点项目 `docs/threat-model.md` 未列，但真实存在）；③ `docs/threat-model.md` 已列出的其余威胁（局域网中间人、未配对设备、敏感内容被同步、蓝牙攻击面等）与隐私默认值。UI 只保留不可逆后果与决策前必知项，其余风险认知归入这份必读文档，并在引导/首启时引导阅读。

### 问题 4：Windows 输入框字符纵向裁剪

**用户/体验**：Windows 侧多个输入框的字符显示不完整、上下被切，肉眼判断是纵向高度的问题（录屏佐证：`输入框字符裁剪演示.mp4`，随包附送）。

**分析定位**：全仓 8 个单行输入框（7 个 `Height=30` + 搜索框 32）均受影响，唯一正常的是 `Height=88` 的多行「屏蔽进程」框（内嵌字体实测：JetBrains Mono 行盒 1.32~1.565em、Noto Sans SC 1.448em，均贴着或超过 16px 视口）。根因在 `MainWindow.xaml` 的 `InsetTextBox`：`Padding="10,6"` + `FontSize=12.5` + 各处死高度 30（搜索框 32）。可用高度 `30 − 2×1(边框) − 2×6(Padding) = 16px`，而 `CsMono`（JetBrains Mono）行盒约为字号 1.35\~1.5 倍（12.5px 约 17\~19px），中文回退到 Noto Sans SC 度量更大 → **内容行高超过可用高度，TextBoxView 被上下裁剪**。`VerticalContentAlignment=Center` 只是让被裁内容居中，不解决裁剪。

**修复方向**：

1. `Height` 换 `MinHeight`（34\~36），或纵向 Padding 6→4；
2. 加 `TextBlock.LineStackingStrategy="BlockLineHeight"` + `TextBlock.LineHeight`（如 18）锁定行盒；
3. 中文/通用输入框不用 Mono 家族（Mono 度量高、缺中文，只适合 IP/端口/密钥）。

### 问题 5：TUN 模式（v2rayN/xray）对同步的影响

**用户/体验**：用户平时用 v2 的 TUN 模式（测试时关着），想知道开着会不会影响手机与电脑的连通。

**分析定位**：

* **系统/HTTP 代理**：应用层，ClipSync 两端已显式绕过（Android `NO\_PROXY`、Windows `Proxy=null`），开着不影响。
* **TUN / VPN / 增强模式**：IP 层接管整个虚拟网卡，**应用代码绕不开**。开着 TUN 时，手机到电脑的局域网直连包会被当出站流量接管 → 请求到不了 47654 → 表现与防火墙拦截几乎一样（"连不上 / 配对失败 / 批准窗口不弹"）。UDP 47653 发现广播也会失效；手机端同样受影响。

**当前状态**：Wintun 网卡 Disconnected、系统代理关闭、默认路由直连 → 无影响（与成功配对一致）。

**以后开 TUN 的做法**（两端都要配）：

* 开启"绕过局域网 / Bypass LAN"，或加规则：`ip,192.168.124.0/24,direct`（按实际网段），端口级 `tcp:47654→direct, udp:47653→direct`。

### 问题 6：语言选择器选中项显示为代码格式（2026-09-02 补充）

**用户/体验**：偏好页「语言」下拉框里选了一种语言后，闭合的下拉框直接把选中项显示成一串类似代码/JSON 的文字（`LanguageOption { Key = system, DisplayName = 跟随系统 }`），而不是语言名。

**分析定位**：`InsetComboBox` 样式（`MainWindow.xaml`）的选中框 `ContentPresenter` 缺 `ContentTemplateSelector="{TemplateBinding ItemTemplateSelector}"`，导致 `DisplayMemberPath="DisplayName"` 在闭合态不生效、回退到 `LanguageOption` record 的 `ToString()`。下拉列表项走另一条路径不受影响，所以只有闭合态选中框坏。修复：在该 `ContentPresenter` 补上 `ContentTemplateSelector` 一行即可（本页唯一使用 `InsetComboBox` 的是语言选择器）。

\---

## 三、给朋友/项目的优化建议汇总

（以下建议均来自「分析定位」层）

1. **应用内增加防火墙检测与一键放行**（47654 无入站放行则引导添加）——文档 install.md/README 已提示放行，但应用自身无任何检测/引导逻辑，本次踩坑根因正在此处。
2. **加全局异常兜底**：`DispatcherUnhandledException` + `AppDomain.CurrentDomain.UnhandledException`，捕获、写诊断日志、优雅退出。
3. **修"退出时点托盘崩溃"**：`TrayFlyoutWindow.ShowFlyout()` 在 Shutdown 阶段直接返回，不调 `Show()`。
4. **Android「无法连接」文案补一句防火墙提示**："无法连接到电脑。请确认两台设备在同一网络"与超时文案两分支已存在，只需在"无法连接"里补"若同网仍失败，可能是 Windows 防火墙未放行 47654"。
5. **等待/批准反馈**：Android 等待页显示已等待秒数/连接状态；Windows 批准窗口任务栏闪烁 + 系统通知。
6. **UI 文案去负向化**：按"只留不可逆后果 + 决策前风险"标准精简；或者可以考虑提供使用前的必读协议。
7. **输入框高度/行盒修复**：`InsetTextBox` 高度账与字体度量对齐。
8. **语言选择器选中项修复**：`InsetComboBox` 选中框 `ContentPresenter` 补 `ContentTemplateSelector="{TemplateBinding ItemTemplateSelector}"`。

\---

## 四、状态与遗留

* 配对已成功，`vivo V2426A` 已入列，局域网同步可用。
* 崩溃转储：`ClipSync.App.exe.43968.dmp`（本机 `C:\Users\nc17\AppData\Local\CrashDumps\`，未随包附送；已分析，结论见问题 2）。
* 会话文件（本总结来源）：本机 `C:\Users\nc17\.dsh\sessions\--D-clipsync--\` 下 zstd 压缩 JSONL（未随包附送，含完整对话与工具调用记录）。
* **未执行**：源码补丁与重新打包（用户未确认是否需要）。

\---

## 五、与项目文档的对应关系

| 问题 | 项目文档是否已提及 | 说明 |
|---|---|---|
| 1 防火墙 47654 | 已提及（表层） | `README` 三语言、`docs/install.md`（安装第 3 步 + 排障表）、`manual-qa-checklist.md` 均已要求"放行 47654/47653"；本反馈的新增量是"公用网络下不弹提示 + 应用零防火墙自检/一键放行"这一深层缺口 |
| 2 退出点托盘崩溃 | 未提及 | 项目文档只有"无云端崩溃上报"（隐私承诺），无此 bug |
| 3 文案负向 | 未提及（立场相反） | `settings-roadmap.md` 把负向话术视为"隐私承诺（纲领 5.9）有意写足"；本反馈建议改为"必读文档 + 精简 UI 提示" |
| 4 输入框裁剪 | 未提及 | `verification-without-device.md` 自承"XAML 视觉只做 ViewModel 层验证"；本反馈命中该盲区 |
| 5 TUN | 已提及（完整） | `docs/install.md` 第 5 节专门覆盖 TUN/VPN/代理放行 |
| 6 语言选择器 | 未提及 | `settings-roadmap.md` P1#16 注明"未经真机 QA"；本反馈命中该盲区 |

**要点**：问题 2、3、4、6 是本反馈的增量价值（项目文档未覆盖）；问题 1、5 文档已写，本反馈的贡献分别是"更深一层根因"（问题 1）与"确认已有结论"（问题 5）；问题 4、6 恰好命中项目自己标注的"未经真机 QA"盲区。

\---

## 六、核实与处置（2026-09-03 追加）

以上为 2026-09-01 的原始反馈，原样保留。以下是项目方逐条核实后的结论与处置；本文件由仓库根目录移入 `docs/` 归档。

| 问题 | 核实结论 | 处置 |
|---|---|---|
| 1 防火墙 | **部分成立。** 缺 TCP 47654 入站放行是主因；另发现手机端"约 90 秒"与 Windows 端批准超时 90 秒（`PairingService.ApprovalTimeout`）吻合，意味着请求也可能已到达而批准窗口未被看到——两头都修。复核 `UdpDiscoveryBroadcaster`：UDP 47653 只发不收、Android 无监听，**无需放行** | 诊断日志记录配对请求到达/超时/拒绝/成功；批准窗口任务栏闪烁 + 托盘气泡；Windows 端防火墙状态检测与一键放行/移除（ADR 0006，`docs/adr/0006-firewall-rule-management.md`，默认仅专用网络）；Android「无法连接」分层提示补防火墙一句。文档：README 三语与 `docs/install.md` 去掉 UDP 47653 放行要求，§3 补手动放行 / 网络类型检查 / Block 规则查删，排障表同步；`manual-qa-checklist.md` 第 0 节改写 |
| 2 退出点托盘崩溃 | **成立。** 修法修正：`Dispatcher.HasShutdownStarted` 与 `DispatcherUnhandledException` 均覆盖不到托盘原生回调，改为自维护退出标志 + 托盘回调处本地 catch | 退出标志 + 本地 catch 已加；全局兜底（`DispatcherUnhandledException` + `AppDomain.UnhandledException`，写诊断日志后优雅退出）已加。`manual-qa-checklist.md` 第 5 节新增"退出过程中连点托盘图标 / 快捷键 / 诊断，进程不崩溃"一项 |
| 3 文案负向 | **部分成立。** 量化后真正自我辩解式的文案双端共 5 条，其中 3 条 adb 相关（`Conduit_Privileged_Desc`、`Conduit_Privileged_ConsentBody`、`Onboarding_Privileged_Consent`）改为正向行为描述（"点击「启动特权直读」时，本机通过 adb 向手机发送一条启动命令；其他时候不调用 adb"）；"做不到就直说"（`Onboarding_Honesty_Body`）作为刻意的品牌声音保留；实质承诺（正文不进日志、撤销不远程删除）保留 | 新增使用前必读文档三语（`docs/privacy-and-risks.md` / `.zh-CN.md` / `.ja.md`）与应用内入口；README 三语与 `install.md` 的 adb 否认句同步改正向；纲领 §5.9 补措辞规范（正向行为描述、同一承诺一条路径出现一次）；威胁模型补"接收端剪贴板暴露"（本反馈指出的遗漏项）与"公用配置文件放行"两行 |
| 4 输入框裁剪 | **部分成立。** 现象与 8 个受影响输入框的计数准确；根因修正为 `InsetTextBox` 模板的 Padding 双重生效（模板与实例各一次，实际视口仅 4px），不是行盒/字号账 | 修法为删去一行 Margin；重命名框字体由 Mono 改 Sans（Mono 缺中文、度量高，只保留给 IP/端口/密钥类输入） |
| 5 TUN | **属实。** 系统代理已被两端显式绕过；TUN/VPN 在 IP 层接管、应用绕不开，两端都要配 | `docs/install.md` 第 5 节补 v2rayN / xray / v2rayNG 的做法（绕过局域网、`ip,<网段>/24,direct`、`tcp:47654→direct`），标题与排障表同步 |
| 6 语言选择器 | **完全准确。** `InsetComboBox` 选中框 `ContentPresenter` 缺 `ContentTemplateSelector` | 已按建议补一行修复 |

未采纳的建议与理由：问题 4 原建议的"Height 换 MinHeight / LineStackingStrategy 锁行盒"不再需要——根因是 Padding 双重生效，删一行即可，不引入新的行盒规则；问题 3 原建议"UI 只保留不可逆后果与决策前必知项"按纲领 §5.9 措辞规范折中执行：实质承诺保留、否认句改正向、零散风险认知归入必读文档。

