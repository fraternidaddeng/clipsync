# 阶段 7 变更记录

日期：2026-08-18
状态：**完成（含签名 APK）**。打包由 Grok 4.6 子代理完成，编排端收口并完成签名。两套测试保持全绿（Android 411/0/1 skipped；Windows 191 + 33，0 警告）。

## 交付内容

- **`scripts/package-release.ps1`**：一键出包。Windows `dotnet publish` self-contained win-x64 便携 ZIP（免管理员，内置 README.txt：运行方式、数据位置 `%LocalAppData%\ClipSync`、开机启动、卸载）；Android `assembleRelease`（keystore + 密码环境变量齐备时签名并把 keystore SHA-256 指纹写进 release notes，否则明确标注 unsigned 并**打印** `keytool` 建库命令，不代生成）；每个产物生成 `.sha256` + 汇总 `SHA256SUMS.txt`；`releases\<version>\` 保留最近两个版本，`-Prune` 才真正删除。
- **`scripts/install-windows.ps1` / `uninstall-windows.ps1`**：当前用户 `HKCU\...\Run` 开机启动（`-EnableAutostart`，免管理员）；卸载先移除 Run 键，删除数据必须显式确认或 `-DeleteData`，并支持 `-ExportTo` 先导出 `clipsync.db`，绝不静默删数据。
- **`docs/distribution.md`**：安装（Windows 10 分钟路径、Android 侧载含 MIUI 继续安装）、每模式权限引导（通知可拒、电池优化、overlay 同意、READ_LOGS 仅 adb 打印、Shizuku 每次重启后 `start.sh` + MIUI 自启动）、重启后哪些自动恢复哪些需手动、每模式停用方式、回滚说明（Windows 换文件夹数据不动；Android 降版本可能需卸载并丢本机历史）、以及「可见状态 → 具体修复」故障速查表（对应 plan 3 分钟定位验收）。
- **`scripts/android-bootstrap.ps1` 扩展**：加列设备、USB/adb 授权状态、Shizuku 是否安装的检查，并打印（不执行）`start.sh` 命令；安全契约不变——只打印特权命令，从不执行，不捆绑 Shizuku。
- **`THIRD_PARTY_NOTICES.md`**：补 SQLitePCLRaw 2.1.6（随 Windows ZIP 实际分发的传递依赖）与「实际随包分发清单」说明；Syzygy grep 确认无代码/资源引用。
- **`.gitignore`**：`releases/`（产物不入库）。
- **`android/app/build.gradle.kts`**：release signingConfig 仅在 `D:\paste-tools\clipsync-release.keystore`（或 `$env:CLIPSYNC_KEYSTORE`）存在且 `$env:CLIPSYNC_KEYSTORE_PASSWORD` 设置时启用；debug 构建与单测完全不受影响。

## 实测（2026-08-18）

- `package-release.ps1` 端到端跑通，产物在 `releases/0.1.0/`：`ClipSync-Windows-0.1.0-win-x64.zip`（85.5 MB，sha256 `486bde2dd05a502d…`）、`ClipSync-Android-0.1.0-unsigned.apk`（48.3 MB，sha256 `9b4c954cd47d5ea2…`）+ 校验文件 + release notes；ZIP 哈希复算一致。
- 便携包从临时解压目录用一次性 `CLIPSYNC_DATA_DIR` 启动成功（`listener_started` → `peer_server_started_port_4107`，47654 被正在运行的实例占用故落到临时端口，符合设计），只杀了自己启动的 PID，未碰正在跑的实例与真实数据目录。
- 版本号：Windows 组件 0.2.0、Android versionName 0.1.0；产物目录当前以 0.1.0 命名，可 `-Version` 覆盖（后续建议统一两端版本号）。

## 签名（2026-08-18，用户授权后由编排端执行）

- keystore：`D:\paste-tools\clipsync-release.keystore`（PKCS12，RSA 2048，有效期 10000 天，CN=ClipSync Personal）；随机高强度密码存于仓库外 `D:\paste-tools\clipsync-release-keystore.password.txt`（keytool 经 `-storepass:env` 读取，命令行与日志均不含密码）。**两个文件都必须备份**；丢失即无法再用同一身份签名升级包。
- 签名产物：`releases/0.1.0/ClipSync-Android-0.1.0.apk`（48.3 MB，sha256 `97b456360582a7ed…`）。`apksigner verify --print-certs` 确认签名者 CN=ClipSync Personal，证书 SHA-256 `1ad02d4ff32a5a6dd8b25adf70dc78e0430c34439886f5e41a96f871fdba32dd`，与 keystore 指纹一致，已写入 RELEASE_NOTES。
- 注意：测试机上现装的是 debug 签名包；release 包签名不同，不能直接覆盖安装（需卸载，会丢手机侧历史与配对）。release 包用于全新安装。

## 待人工 / 未做
- **MSIX、计划任务开机启动**：plan 允许二选一，已选便携 ZIP + Run 键。
- **10 分钟全新机安装验收**：文档就绪，未在一台全新 Windows 上计时演练。
- 自动静默更新：明确不做（plan）。
