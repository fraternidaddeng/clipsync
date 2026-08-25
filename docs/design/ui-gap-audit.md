# 剪剪相传 · UI 差距清单

> 从属于《设计纲领》（`DESIGN-CHARTER.md`）与 `tokens.md`。
> 本文件记录「章程 → 实现」尚未合拢的差距，按影响排序；每完成一轮打磨更新一次。
> 最后更新：2026-08-24（Windows 收尾轮：衬线随包 / GroupCard 深度 / 历史空状态与首启落通路 / 超椭圆裁决为不做；Android 赭色脉动复核无缺失）

---

## 一、本轮已合拢的差距（记录留档）

| 项 | 落地 |
|---|---|
| Android 三个声音随包分发 | `res/font` 打包 `NotoSerifSC-SemiBold`、`NotoSansSC-Regular/Medium`、`JetBrainsMono-Regular/Medium` 五枚；`ClipSyncFonts` 三族接入 `ClipSyncType` 全字阶与 M3 Typography（Material 组件不再漏系统字体）；Sans 600 按打包清单落 Medium 文件而非合成粗体；配对页指纹/地址改用随包 Mono |
| Android 卡片深度 | 历史卡从平面 1px 边框卡换 `charterCard`（sh-1 阴影 + `sf` 面 + `sf-grad` 顶部受光 + 1px 发丝线）；通路「需要你操作」段改为 z1 深度上叠赭色着色（不再是无影平条） |
| Android 动效令牌 | `CharterMotion`：`cubic-bezier(.16,1,.3,1)` + 260/300/320ms 档；接入 tab 切换 Crossfade、向导开合、管道段展开、通知条进出、历史列表 item 位移；2.6s 赭色描边脉动核验存在并改引令牌 |
| Android 超椭圆 | `SuperellipseShape`（n≈4.4，采样路径）+ `CharterShapes.card/control`；卡 16dp / 控件 12dp 统一（通知条 8→12、段操作 chip 10→12、测试结果条 10→12、历史卡 12→16） |
| Android 设备邻近色 | 来源盒不再硬编码 `dev1`：`ClipSyncColors.device/deviceBg/deviceLn(pairingOrder)` 按配对顺位取色（着色底 11%/12%、描边 24%，过五循环）；`PairingStore.pairedPeers()` 定义顺位契约，未配对来源退灰色事实盒 |
| 应用图标 | 折线标记应用图标（`docs/design/icons/app-icon.svg` + 16 网格小帧变体）。Windows `app.ico`（16–256 八档，`scripts/generate-app-icons.py` 生成）接入 exe（`ApplicationIcon`）与全部窗口（`Window.Icon`）；Android 自适应图标（前景/背景/monochrome 三层矢量，minSdk 29 无需光栅 mipmap），默认机器人不再出现 |
| Windows 标题栏 | 三个窗口全部 `WindowChrome` 自绘：灰蓝 `sf3` 顶栏、品牌折线 + 衬线名、窗控三钮（关闭钮悬停用 err 着色盒）。主窗标题栏承载 暂停/私密 开关与四段 mini rail（纲领 3.7：rail 在标题栏复用） |
| 配对二维码窗口 | 纯白静区保留，外加流动蓝四角取景框；倒计时转 Mono「机器的声音」；无可达地址 = 赭色着色盒（需要你操作，不是错误）；指纹四位一组、八组一行、两行、t1 最高对比；文案全中文 |
| 配对批准窗口 | 请求方用设备邻近色着色盒 + 手机字形；重配对警示 = err 着色盒（真 error：密钥替换）；批准 = 唯一实心主按钮 |
| Android 配对页 | 全中文文案（与 `ui_preview.html` 逐字一致）；证书变更 = 赭黄整块警示 + 「我已核实 — 替换配对」责任按钮（全应用唯一）；指纹两行分组；扫码取景框 = charter 卡 + 流动蓝四角；幽灵按钮族对齐章程 |
| Android 系统表面 | 通知/QS 磁贴小图标换折线标记（单色、2.4 线宽，对齐 16 网格托盘视觉重量）；`setColor` 接流动蓝；同步通道补描述、去角标；前台通知状态文案全中文并入 `strings.xml` |
| 托盘/杂项中文化 | 托盘菜单、气泡提示、「需要你操作」缘由、配对不可用弹窗全部转中文 |
| WPF 夜间主题 | `CharterTokensNight.xaml` 同键夜间字典（tokens §2 夜值，夜间抬起 = 提亮面色）；`CharterThemeManager` 读 `SystemUsesLightTheme`（与托盘同一取值，缺省视为深色）、监听 `SystemEvents.UserPreferenceChanged` 运行时整体替换字典；三个窗口约 170 处 `StaticResource` 刷成 `DynamicResource`，切主题无需重启 |
| Windows 随包字体（Sans/Mono） | `Resources/Fonts/` 打包 Noto Sans SC 400/500、Plus Jakarta Sans 400/500/600、JetBrains Mono 400/500（OFL 许可证随文件）；`CsSans`/`CsMono` 改指随包字体，三窗口不再落 `Segoe UI / 微软雅黑`，Mono 链中文回落到随包 Noto Sans SC；`Consolas` 仅作加载失败兜底 |
| Android 空状态（三处） | 历史空列表：未配对 = Serif 短句「先把两端接起来」+ 幽灵「去配对」按钮（跳通路）；已配对 = 「静候第一条剪贴」。通路「已配对设备」区空态 = 事实陈述 + 幽灵配对入口。偏好「设备」区：未配对空态 + 去配对；已配对显示对端名 + 「管理配对」链接行 |
| Android 首次运行引导 | `OnboardingScreen`（仅首次、未配对安装展示一次，`FirstRunStore` 落盘）：衬线品牌问候、三个 tab 各一句诚实说明、配对入口明确指向通路网络段、「先说清楚」能力限制卡（后台读取需授权、以实测为准）；实心「去配对」+ 幽灵「先看看」；文案为纯数据 `OnboardingContent`，有测试锁定承诺 |
| Android 通知关闭状态行 | 通路页「通知已关闭」诚实条（stage-gap A9 收尾）：`areNotificationsEnabled` 每次 refresh/回到前台重探，false 时灰面事实条陈述后果（收到内容/需要恢复通知不会出现）+ 「去系统设置开启」深链；非赭非红——用户的选择是事实不是错误 |
| Android 配对页次级状态 | 等待批准/已配对/失败三态脱离默认外观：charter 卡面 + 流动蓝进度环；证书不匹配 = 全应用唯一 err 着色盒，其余失败为灰面事实卡 |
| Windows 卡片深度 | 历史卡与通路四段改 tokens §8 三层嵌套：最外层 `DropShadowEffect`（sh-1 近似，文字不进 Effect 子树）、中层 1px 边框、最内 2px 顶部高光 + `sf-grad` 受光；悬停 1px 上移 + 阴影跳 sh-2（`CsSh1Effect`/`CsSh2Effect`/`CsEdgeBrush` 入 `CharterTokens.xaml`，含夜间字典同键对应） |
| Windows 托盘浮窗 | tokens §12.6 落地：左键 = 440px 浮窗（最近 4 条 + 四段 rail + 页脚状态条 + 暂停提级），双击/菜单 = 主窗口；约 3 秒自动退场、指针悬停驻留；不铺颗粒、无时间性动效；点卡片即复制 |
| 设备邻近色按配对顺位（Windows） | `ListDevicesAsync` 的 created_at 顺位 → dev-1..dev-5 循环（`DeviceAccent`）；历史来源盒、浮窗来源盒、设备行着色盒全部按顺位取色；本机/已不在册来源退灰盒 |
| 赭色「需要你操作」脉动（Windows） | 通路捕获段降级态改状态编码 §10 正形：act-bg 轨道 + 1.5px act 描边空填充 + 2.6s 外扩 5px 渐隐光环（`Storyboard` + KeySpline `.16,1,.3,1`） |
| Windows 随包衬线 | `Resources/Fonts/` 打包 `NotoSerifSC-SemiBold`（~11.8MB，体积裁决沿用 Android 侧结论；OFL 许可证随文件）；`CsSerif` 改指随包字体（typographic 族名 `#Noto Serif SC` 为主、GDI 族名兜底），`SimSun/Georgia` 只在加载失败时出场——三个声音两端全部随包，字体差距关闭 |
| Windows 历史空状态 + 首启落通路 | 主窗历史空列表 = 三处衬线时刻之一（纲领 3.5）：与 Android 逐字同句——未配对「先把两端接起来」+ 幽灵「去配对」（跳通路页，同一动线）；已配对「静候第一条剪贴」。搜索无匹配单独一句灰色事实（`ActiveQuery` 区分真空与无匹配，镜像 Android `searchActive`）。首启（未配对且无历史）直接落「通路」页（`pc-ui-inventory` #14） |
| Windows 偏好页卡片深度 | `GroupCard`（偏好四组 + 通路设备清单卡）从 1px 平面卡换 tokens §8 三层嵌套：最外层 sh-1 阴影（文字不进 Effect 子树）、中层 1px 边框 + `sf-grad` 受光、最内 2px 顶部高光——与历史卡/通路段同构，主窗卡片深度全部收敛 |
| Android 赭色脉动复核 | 复核确认无缺失：所有 `NEEDS_ACTION` 段经 `FillBar` 统一路由到 `PulsingBar`（act-bg 轨道 + 1.5px act 描边 + 2.6s 外扩 5px 渐隐光环，引 `CharterMotion` 令牌）；下游段安静是 single-beckon 规则（纲领 §5.6「全屏唯一会伸手的」）的刻意行为，非缺口 |

---

## 二、剩余差距（按影响排序）

### P1 · 直接可见的缺口

本轮全部合拢（衬线随包 / 空状态与首启 / 卡片深度，见上表），暂无 P1 项。

### P2 · 章程规定但未接线

| 差距 | 现状 | 需要做 |
|---|---|---|
| ~~设备邻近色手动改色~~ **已解决（2026-08-25，settings-roadmap P1#14）** | 通路设备行五色点选色器：Android 存 `PairingStore`（`device.accent.<id>`），Windows 存 devices 表 `accent_override`（schema v5）；点配对顺位默认色即清除覆盖，历史来源盒随生效色 | — |
| 动效令牌（Windows 剩余部分） | Android 已接令牌（本轮）；Windows 已做「需要你操作」2.6s 脉动（KeySpline 逐位对齐），其余交互过渡仍无统一缓动/时长档；配对成功的一次性镜面流光两端均未做 | WPF `Storyboard` 接 `cubic-bezier(.16,1,.3,1)` + 180–220ms 档到其余过渡 |
| ~~QS 磁贴状态~~ **已解决（2026-08-25）** | 磁贴在 QS 面板打开期间跟随 `connectionStates` 与 暂停/私密 偏好：仅已连接为 active，其余（排队/被拒）为 inactive——保持可点，外观说实话；纯映射 `SendClipboardTileState` 有单元测试 | — |
| ~~托盘主题只采样一次~~ **已解决（2026-08-25）** | 托盘监听 `SystemEvents.UserPreferenceChanged`（General/VisualStyle），派发到 UI 线程重读任务栏主题并整套换图标（换完再释放旧套）；外观手动覆盖不钉托盘——托盘住在任务栏里 | — |

### P3 · 核验与打磨

| 差距 | 现状 | 需要做 |
|---|---|---|
| Win10 下自绘 chrome 表现 | `GlassFrameThickness=-1` 在 Win11 有圆角+投影；Win10 直角待实机核验；配对两窗 `SizeToContent=Height` 与 WindowChrome 组合的首次布局也需实机确认无底部空隙 | 实机核验；必要时按系统版本回退 |
| MIUI 通知表现 | `setColor`/折线小图标在 MIUI 会被二次改写（纲领 5.8 预期内） | 实机核验 Redmi；不达标则接受系统绘制 |
| 夜间设备色 | 藕紫 305 / 灰粉 335 的夜值仍未实机核验（纲领 §六 遗留） | 实机比对后修 `tokens.md` |
| ~~高 DPI 的 QR 整数缩放~~ **已解决（2026-08-25）** | `RenderPngForDpi` 按 `VisualTreeHelper.GetDpi` 取整每模块物理像素（就近 280dip 目标边、最小 1），`Image` 按位图物理尺寸精确布局杜绝重采样；跨屏 DPI 变化重栅格同一票据（不重发）；NearestNeighbor 兜底；纯计算 `PixelsPerModule` 有参数化测试 | — |
| 可访问性 | 窗控钮有 `AutomationProperties`，但开关/来源盒的读屏文案不全；`t4` 压 `sf3` 的对比度边缘案例 | 系统性过一遍 AutomationProperties / contentDescription |
| ~~Android 状态栏色硬编码~~ **已解决（2026-08-25）** | `cs_bg`（tokens §2 z0 底）入 `values/colors.xml` + `values-night/colors.xml`，两套 `styles.xml` 的状态栏/导航栏改引 `@color/cs_bg`；夜值随 AppCompat 夜间模式（含外观手动覆盖）解析 | — |
| 历史图片项 | 74dp 缩略条（纲领 §六 待裁决）完全未做——当前阶段仅文本同步 | 随图片同步阶段一起做 |

---

## 三、明确不做 / 已裁决

- **Windows 超椭圆不做**（2026-08-24 裁决）：WPF 的 `Border`/`Clip` 管线没有可插拔的圆角几何，超椭圆意味着用自绘 `Geometry` 替换三个窗口 + 托盘浮窗里的全部圆角 `Border`（含 `9,9,0,0` 这类分角半径、三层嵌套卡的每一层、以及挂 `DropShadowEffect` 的外层——阴影跟随自绘形状还需再包一层），是一次全量重写；而 n≈4.4 超椭圆与普通圆角在 12–16px 半径下的差异每角不足 1px。收益配不上侵入度：Windows 保持普通圆角矩形，超椭圆为 Android 独有（两端「同一性格、各说方言」在纲领允许范围内）。若未来出现共享绘制层再回看。
- **托盘浮窗、通知、磁贴不铺颗粒**（tokens §5）：现状正确，保持。
- **绿色**：全部资产核查过，无色相 100–180 出现。
- **Android 启动图标夜间变体**：自适应图标背景自带 charter 底色，深色主题人格由 monochrome 层（API 33+ 主题图标）承担，不做第二套。
- **红色关闭钮**：窗控关闭钮悬停用 err 着色盒（低彩度红族着色盒语汇），不用系统饱和红——「关闭」是惯例位置，红族提示破坏性边界，但不喊叫。
