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
  3. 打开剪剪相传 → **偏好** → 「额外监听地址」填入该地址（默认只监听局域网私有网段，Tailscale 的虚拟网卡地址需手动加入），**重启应用**生效。
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

## 7. Android 后台读取通路（四档任选）

Android 10 起系统禁止普通应用后台读剪贴板，剪剪相传把它拆成能力阶梯，在 **通路** 页逐项探测、诚实显示；「读」与「写回」分开探测，不会把网络在线谎报成剪贴板可用：

| 档位 | 需要什么 | 特点 |
|---|---|---|
| 前台/手动 | 无 | 应用在前台复制即同步；后台用分享面板、快捷磁贴、通知里的复制入口，永远可用 |
| 悬浮窗轮询 | 悬浮窗权限 | 不需要电脑；代价是耗电与秒级轮询延迟 |
| 日志感知 + 悬浮窗 | 电脑 adb 授权 + 悬浮窗权限 | 通路页有「复制 adb 命令」一键复制：`adb shell pm grant com.clipsync.android android.permission.READ_LOGS` |
| 特权直读 | Shizuku（adb 或 root 激活） | 体验最好：后台复制即时上行 |

每档的引导、探测与自检都在通路页内完成；权限随时可撤，撤掉后自动降级到仍可用的档位。

## 8. 故障排查

| 现象 | 排查顺序 |
|---|---|
| 扫码报「码已过期」 | 一次性令牌超时，Windows 端重新出示二维码即可 |
| 扫码后连不上 | ① 两台设备是否同一网段（AP 隔离/访客网络会挡）② Windows 防火墙是否放行专用网络 TCP 47654 / UDP 47653 ③ Tailscale 场景是否已填「额外监听地址」并重启 ④ 任一台开着 Clash/Surge 等代理时见第 5 节代理小节 |
| 开着 Clash/Surge 等代理时连不上或不同步 | 见第 5 节「开着 Clash / Surge 等代理时」：开启「绕过局域网」，或给电脑 IP 与 47654 端口加 DIRECT 直连规则；TUN/VPN/全局模式必须放行局域网 |
| 配对成功但不同步 | ① 两端「暂停」「私密模式」开关 ② Android 通路页当前档位状态 ③ 超过 1 MiB 的文本按协议「仅本地保留」，不属于丢失 |
| Android 后台经常断 | 把剪剪相传加入电池优化白名单/允许自启动（国产 ROM 常见）；确认前台服务通知还在 |
| 启动时弹出空的控制台/PowerShell 窗口（标题是 dotnet 路径） | 说明是经 `dotnet ClipSync.App.dll` / `dotnet run` 等控制台方式拉起的：`dotnet.exe` 是控制台程序，会带出一个空窗口。请直接运行（或让开机自启项指向）`ClipSync.App.exe`。应用自身也会在启动时自动分离继承来的控制台，作为兜底 |
| 提示对方证书变化 | 只有对方确实重装/换机时才「核实后替换配对」；来历不明的变化一律拒绝 |
| 需要报告问题 | 托盘 → 「诊断日志」导出。日志与诊断**绝不包含剪贴板正文与密钥**，可放心提供 |

## 9. 自行打包与签名（发布者）

- **Windows**：`pwsh ./scripts/package-windows.ps1` → `dist/ClipSync-windows-x64.zip`（在 Linux/CI 上运行会自动附加 `-p:EnableWindowsTargeting=true`）。
- **Android**：先用 `keytool` 生成一次发布密钥库（保存在仓库之外，如密码管理器）：

```powershell
keytool -genkeypair -v -keystore clipsync-release.jks -storetype PKCS12 `
    -alias clipsync -keyalg RSA -keysize 4096 -validity 3650
```

  然后设置四个环境变量并打包：`CLIPSYNC_ANDROID_KEYSTORE`（密钥库路径）、`CLIPSYNC_ANDROID_KEYSTORE_PASSWORD`、`CLIPSYNC_ANDROID_KEY_ALIAS`、`CLIPSYNC_ANDROID_KEY_PASSWORD`，执行 `pwsh ./scripts/package-android.ps1` → `dist/ClipSync-android.apk`。
- **密钥库与密码绝不入库**：`.gitignore` 已拦截 `*.jks`/`*.keystore`；签名配置只从环境变量读取。丢失密钥库将无法对老用户发布升级包，请妥善备份。
