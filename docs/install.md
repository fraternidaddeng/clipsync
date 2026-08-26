# 剪剪相传 ClipSync · 安装与配对指南

一页读完：装好 Windows 端与 Android 端、接通网络、扫码配对、选一档 Android 后台读取通路。目标是「换台设备 10 分钟能装起来」。

> 诚实声明：Android 三档后台读取（特权直读 / 日志感知 / 悬浮窗轮询）的代码与自动化测试已就绪，但**尚无真机验证记录**（见 `docs/device-validation-matrix.md`）。通路页只有在实测读到内容后才会显示 READY，不会谎报。

## 1. 前置条件

- **Windows**：Windows 10 22H2 或 Windows 11（x64）。安装包自带 .NET 运行时，**不需要**另装任何东西。
- **Android**：Android 10（API 29）及以上。不需要 root；各档通路的权限按需授予、随时可撤。
- **网络**：两台设备在**同一局域网**，或都安装了 **Tailscale**。没有公网中转——互相连不通就不同步，这是设计边界，不是故障。

## 2. 获取安装包并校验

安装包由 `scripts/package-windows.ps1` 与 `scripts/package-android.ps1` 产出到 `dist/`（或从 Releases 下载）：

| 文件 | 用途 |
|---|---|
| `ClipSync-windows-x64.zip` | Windows 便携包（免安装，自带运行时） |
| `ClipSync-android.apk` | Android 正式签名包 |
| `*.sha256` | 对应文件的 SHA-256 校验值 |

校验（可选但建议）：Windows 上 `Get-FileHash .\ClipSync-windows-x64.zip`，Linux/macOS 上 `sha256sum -c ClipSync-windows-x64.zip.sha256`，比对 `.sha256` 文件内容一致即可。

## 3. 安装 Windows 端

1. 解压 ZIP 到任意目录（如 `D:\Apps\`），得到 `ClipSync\` 文件夹；运行其中的 `ClipSync.App.exe`。
2. 首次运行 SmartScreen 可能拦截（发布包未做代码签名）：点「更多信息」→「仍要运行」。
3. Windows 防火墙弹窗时勾选**专用网络**并允许。程序监听 TCP `47654`（被占用时自动换端口）并在 UDP `47653` 做局域网发现广播（只含设备 ID/端口/指纹，不含剪贴板内容）。
4. 应用常驻托盘（托盘右键：打开剪剪相传 / 诊断日志 / 退出）。数据在 `%LOCALAPPDATA%\ClipSync`（可用环境变量 `CLIPSYNC_DATA_DIR` 改）。卸载 = 删程序目录 + 删数据目录，无注册表残留。

## 4. 安装 Android 端

1. 把 `ClipSync-android.apk` 传到手机（数据线、LocalSend 等均可）。
2. 点开安装，按系统提示允许「安装未知应用」；系统会校验 APK 签名。
3. 注意：`ClipSync-android-debug.apk` 是测试包，与正式包**签名不同，不能互相覆盖安装**（需先卸载，历史会清空）。

## 5. 接通网络：同一局域网 或 Tailscale

- **同一局域网**：两台设备连同一路由器即可，无需配置。公司/访客 Wi-Fi 常开「AP 隔离」，设备互相不可见时请换网络或走 Tailscale。
- **Tailscale**（跨网段/远程场景）：
  1. 两台设备都安装 Tailscale 并登录**同一账号**。
  2. Windows 上执行 `tailscale ip -4` 得到本机 `100.x.y.z` 地址。
  3. 打开剪剪相传 → **通路** → 网络段的「连接」卡片 → 「额外监听地址」填入该地址（默认只监听局域网私有网段，Tailscale 的虚拟网卡地址需手动加入），**重启应用**生效。
  4. 之后配对二维码会带上 Tailscale 地址，Android 端正常扫码即可；已配对设备也会通过该地址重连。

### 开着 Clash / Surge 等代理时（全局模式必读）

同步走的是两台设备之间的**直连**（电脑端 TCP `47654`，Tailscale 场景为 `100.x` 地址），任何时候都不该经过代理。两端代码已显式绕过系统 HTTP(S) 代理（Android OkHttp 用 `Proxy.NO_PROXY`，Windows 用 `ClientWebSocket.Options.Proxy = null`），所以只开「系统代理」通常不影响同步；但 **TUN / VPN / 增强模式和全局（Global）模式在 IP 层接管全部流量，应用自己绕不开**，需要在代理软件里放行局域网：

- **Clash（Clash Verge / mihomo / Clash for Android）**
  - 首选开启「**绕过局域网 / Bypass LAN**」（TUN 场景对应 `route-exclude-address`）。注意**全局模式下规则表不生效**，这个开关依然有效，务必打开。
  - 规则模式下也可以在规则**最前面**给电脑 IP（示例 `192.168.1.23`，在电脑上用 `ipconfig` 查）加直连：

```yaml
rules:
  # 剪剪相传：电脑 192.168.1.23 的 47654 端口直连（mihomo/Clash.Meta 语法）
  - AND,((IP-CIDR,192.168.1.23/32),(DST-PORT,47654)),DIRECT
  # 更省事的写法：整台电脑直连，或放行常见内网网段与 Tailscale 网段
  - IP-CIDR,192.168.1.23/32,DIRECT,no-resolve
  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve # Tailscale
```

- **Surge（macOS / iOS）**：设置里保持「**跳过代理 / Skip Proxy**」包含内网网段；增强模式/全局代理时在 `[Rule]` 最前面加：

```text
[Rule]
# 剪剪相传：电脑 192.168.1.23 的 47654 端口直连
AND,((DEST-PORT,47654),(IP-CIDR,192.168.1.23/32)),DIRECT
# 更省事的写法：整台电脑直连；Tailscale 场景再放行 100.64/10
IP-CIDR,192.168.1.23/32,DIRECT,no-resolve
IP-CIDR,100.64.0.0/10,DIRECT,no-resolve
```

- 手机上跑 Clash for Android（VPN 模式）时同样要开「绕过局域网」，或把上面的直连规则加进手机端配置。
- 电脑端口被占用时程序会自动换端口（见第 3 节），改规则前先在剪剪相传里确认当前监听端口。改完代理配置后无需重新配对，重连即可。

## 6. 配对（约两分钟）

1. **Windows**：托盘 → 打开剪剪相传 → **通路** → 「配对新设备」，屏幕出示二维码。二维码只含本机地址、端口、证书指纹与一次性令牌（有倒计时，过期重新出示），**绝不包含配对密钥**。
2. **Android**：打开剪剪相传 → **通路** → 配对 → 「扫描二维码」。
3. 两端各自确认：Windows 弹出批准窗口，两边屏幕都会显示证书指纹，**肉眼比对一致**后确认。
4. 完成。之后任一端复制文本即自动同步；离线期间复制的内容会在重连后按序补齐、不重不漏。
5. 撤销：任一端删除对方设备即断连。重装/换机后指纹会变化，Android 端会出现「我已核实 — 替换配对」确认块——确认是你自己的操作后再替换，否则一律拒绝。

## 7. 蓝牙备援（可选，LAN 断开时的后备通道）

LAN 被 VPN/TUN 全局接管、AP 隔离或路由器故障时，已配对的两台设备可以改走**蓝牙 RFCOMM** 继续同步文本。默认双端关闭；只是后备，不是替代——IP 恢复后会自动切回，速度也远低于局域网（一对真机实测约 150–180 KiB/s、建连 0.7–2.2 秒，见下方诚实声明）。

前置条件：**必须先完成第 6 节的扫码配对**（蓝牙信道复用同一份配对密钥做双向认证，不提供独立的配对入口），且两台设备已在**系统设置里完成蓝牙配对（bonded）**。

1. **系统蓝牙配对**：Windows 设置 → 蓝牙和其他设备 → 添加设备，选择你的手机，两边核对数字后确认。只需做一次。若 Windows 一直扫不到手机（小米等 OEM 上常见），改从**手机侧发起**：系统设置 → 蓝牙 → 往下滚到「可用设备」→ 点你的电脑名 → 两边确认配对。
2. **Windows 端**：剪剪相传 → **通路** → 网络段的「连接」卡片 → 打开「**蓝牙备援**」。网络段随即出现「蓝牙备援待命」。若显示「蓝牙适配器不可用」，检查电脑蓝牙开关或驱动，再关-开一次该开关重试。
3. **Android 端**：剪剪相传 → **通路** → 网络段下方的「**蓝牙备援**」卡片 → 打开开关 → 在已配对（bonded）设备列表中选择你的电脑。Android 12+ 会请求「附近的设备」权限（`BLUETOOTH_CONNECT`），拒绝则该功能保持不可用但不影响其他通路。
4. **之后全自动**：手机只有在所有 IP 地址都连不上时才尝试蓝牙；蓝牙会话期间持续探测 IP，一旦恢复即优雅切回。走蓝牙时，Android 通知与两端通路页都会明确显示「蓝牙备援」。

安全与边界：

- 蓝牙链路上运行的是 bt1 安全信道（见 `docs/protocol-bt1.md`）：基于既有配对密钥的 HMAC-SHA-256 双向认证 + 按连接派生的 AES-256-GCM 加密，系统蓝牙配对只是承载层，**从不替代**剪剪相传自己的配对与撤销（撤销设备后蓝牙握手同样必败）。
- 不需要设备进入「可被发现」模式；只连你手动选定的 bonded 设备，不做扫描。
- **仅同步文本**：图片事件在蓝牙会话中不传输（协议按 v1 运行）。**在「仅蓝牙可用」窗口内复制的图片不会同步，事后 IP 恢复也不会补传**——它们在历史中标注「仅本机保留」，需要时请在 IP 恢复后手动重新复制。蓝牙窗口内复制的文本不受影响，恢复 IP 后照常按序补齐。
- 电脑睡眠/无线电关闭会中断监听，唤醒后自动恢复；诚实声明：蓝牙链路可行性已在一对真机上实测通过（Realtek 适配器 Windows 机 × Redmi Note 11T Pro，三轮全过：建连 0.7–2.2 s、RTT 中位约 31 ms、吞吐约 150–180 KiB/s，详见 `docs/bluetooth-phase0-report.md`），但那是 spike 工具路径——应用内蓝牙备援的整机验证（更多适配器/OEM、锁屏长时存活）尚未完成，见 `docs/bluetooth-fallback-plan.md` 阶段 5。

## 8. Android 后台读取通路（四档任选）

Android 10 起系统禁止普通应用后台读剪贴板，剪剪相传把它拆成能力阶梯，在 **通路** 页逐项探测、诚实显示；「读」与「写回」分开探测，不会把网络在线谎报成剪贴板可用：

| 档位 | 需要什么 | 特点 |
|---|---|---|
| 前台/手动 | 无 | 应用在前台复制即同步；后台用分享面板、快捷磁贴、通知里的复制入口，永远可用 |
| 悬浮窗轮询 | 悬浮窗权限 | 不需要电脑；代价是耗电与秒级轮询延迟 |
| 日志感知 + 悬浮窗 | 电脑 adb 授权 + 悬浮窗权限 | 通路页有「复制 adb 命令」一键复制：`adb shell pm grant com.clipsync.android android.permission.READ_LOGS` |
| 特权直读 | 内置特权宿主（电脑 adb 执行一次启动命令激活，Windows 端可一键启动；或 root） | 体验最好：后台复制即时上行 |

每档的引导、探测与自检都在通路页内完成；权限随时可撤，撤掉后自动降级到仍可用的档位。

### 8.1 开启「特权直读」（内置特权宿主）

特权直读体验最好——息屏也能即时上行，且不占悬浮窗、不耗电轮询。它由剪剪相传**内置**的特权宿主（进程名 `clipsync_priv_server`）提供，**不需要安装任何第三方 App**。因为 Android 安全模型不允许应用自行开启这条通道，只能由电脑执行一次启动命令来拉起。全程显式同意，应用**绝不**静默调用 adb。

首次准备（**手机上的手动步骤，无法代劳**）：

1. 手机「设置 → 关于本机」连点版本号解锁「开发者选项」，进入开发者选项打开 **USB 调试**（无线场景打开 **无线调试**）。
2. 用数据线把手机连到电脑（或在同一网络用无线调试配对）。
3. 电脑首次执行 adb 命令时，**手机会弹出「允许 USB 调试？」的 RSA 指纹确认框**——必须在手机上勾选「一律允许」并确认。这一步是 Android 的安全设计，任何工具都无法跳过或代点。

启动特权宿主（三选一）：

- **Windows 端一键（推荐）**：配对成功后，通路页出现「特权直读」卡片；勾选一次 adb 授权同意（说明 adb 权限含义），点「检测手机」，再点「启动特权直读」。Windows 会自动查找 `adb`（随包 platform-tools / `ANDROID_HOME` / 系统 `PATH`），找不到时如实提示，可自行安装 Google platform-tools 后重试。
- **Android 通路页复制命令**：特权直读路线卡点「复制启动命令」，把命令粘到已连手机的电脑上执行。
- **手动执行**：在已连手机的电脑上运行
  `adb shell sh /storage/emulated/0/Android/data/com.clipsync.android/start.sh`

完成后回通路页点「重新探测」，特权直读应转为已授权/就绪。

注意：**设备重启后特权通道会关闭，需要重新执行启动命令**（重启不需要再过一次 RSA 指纹确认，除非撤销了 USB 调试授权）。Windows 通路页在检测到手机已连但宿主未运行时，会提供再次「启动特权直读」的入口。

## 9. 故障排查

| 现象 | 排查顺序 |
|---|---|
| 扫码报「码已过期」 | 一次性令牌超时，Windows 端重新出示二维码即可 |
| 扫码后连不上 | ① 两台设备是否同一网段（AP 隔离/访客网络会挡）② Windows 防火墙是否放行专用网络 TCP 47654 / UDP 47653 ③ Tailscale 场景是否已填「额外监听地址」并重启 ④ 任一台开着 Clash/Surge 等代理时见第 5 节代理小节 |
| 开着 Clash/Surge 等代理时连不上或不同步 | 见第 5 节「开着 Clash / Surge 等代理时」：开启「绕过局域网」，或给电脑 IP 与 47654 端口加 DIRECT 直连规则；TUN/VPN/全局模式必须放行局域网 |
| 配对成功但不同步 | ① 两端「暂停」「私密模式」开关 ② Android 通路页当前档位状态 ③ 超过 1 MiB 的文本按协议「仅本地保留」，不属于丢失 |
| 特权直读启动失败 / 一直不就绪 | ① 手机是否已开 USB 调试、且已在 RSA 指纹框点「一律允许」 ② 电脑 `adb devices` 是否显示 `device`（显示 `unauthorized` 就是没过 RSA 确认；`offline` 重插线或重启 adb）③ Windows 卡片是否已勾选 adb 授权同意、adb 位置是否显示已找到（未找到需装 Google platform-tools）④ **设备重启后需重新执行启动命令** ⑤ 实在不便用电脑就改用「悬浮窗轮询」档，无需电脑 |
| Android 后台经常断 | 把剪剪相传加入电池优化白名单/允许自启动（国产 ROM 常见）；确认前台服务通知还在 |
| 启动时弹出空的控制台/PowerShell 窗口（标题是 dotnet 路径） | 说明是经 `dotnet ClipSync.App.dll` / `dotnet run` 等控制台方式拉起的：`dotnet.exe` 是控制台程序，会带出一个空窗口。请直接运行（或让开机自启项指向）`ClipSync.App.exe`。应用自身也会在启动时自动分离继承来的控制台，作为兜底 |
| 蓝牙备援连不上 | ① 双端开关都已打开且已扫码配对 ② 系统蓝牙已配对（bonded）且 Android 端已选定目标设备——Windows「添加设备」扫不到手机时改从手机侧「可用设备」发起配对（见第 7 节第 1 步） ③ Android 12+ 的「附近的设备」权限已授予 ④ Windows 通路页若显示「蓝牙适配器不可用」，检查蓝牙开关/驱动后重开备援开关 ⑤ 蓝牙只是后备：手机仅在所有 IP 地址失败后才拨蓝牙 |
| 提示对方证书变化 | 只有对方确实重装/换机时才「核实后替换配对」；来历不明的变化一律拒绝 |
| 需要报告问题 | 托盘 → 「诊断日志」导出。日志与诊断**绝不包含剪贴板正文与密钥**，可放心提供 |

## 10. 自行打包与签名（发布者）

- **Windows**：`pwsh ./scripts/package-windows.ps1` → `dist/ClipSync-windows-x64.zip`（在 Linux/CI 上运行会自动附加 `-p:EnableWindowsTargeting=true`）。
- **Android**：先用 `keytool` 生成一次发布密钥库（保存在仓库之外，如密码管理器）：

```powershell
keytool -genkeypair -v -keystore clipsync-release.jks -storetype PKCS12 `
    -alias clipsync -keyalg RSA -keysize 4096 -validity 3650
```

  然后设置四个环境变量并打包：`CLIPSYNC_ANDROID_KEYSTORE`（密钥库路径）、`CLIPSYNC_ANDROID_KEYSTORE_PASSWORD`、`CLIPSYNC_ANDROID_KEY_ALIAS`、`CLIPSYNC_ANDROID_KEY_PASSWORD`，执行 `pwsh ./scripts/package-android.ps1` → `dist/ClipSync-android.apk`。
- **密钥库与密码绝不入库**：`.gitignore` 已拦截 `*.jks`/`*.keystore`；签名配置只从环境变量读取。丢失密钥库将无法对老用户发布升级包，请妥善备份。
