# Windows 更新包签名

自 fix/deep-review 起，Windows 端的自动更新必须同时满足两条才会安装：
1. SHA-256 与 GitHub 上的 release 元数据一致。元数据（latest JSON、`.sha256`、`.sig`）**只从 GitHub 获取**，第三方代理只能用来下载 ZIP 本体。
2. `<zip>.sig` 能用客户端内置的公钥（ECDSA P-256 / SHA-256）验证通过。

客户端已内置发布公钥。对应私钥保存在仓库外，并通过 GitHub Actions 仓库 secret `CLIPSYNC_RELEASE_SIGNING_KEY` 提供给发布流程，格式为完整的 PEM 文本。

`.github/workflows/release.yml` 在发布 Windows ZIP 时强制要求该 secret，自动生成 `.sig`，并用客户端源码中的公钥复核后再上传。缺少密钥、公私钥不匹配或验签失败都会阻止发布，避免发出无法通过应用内升级的版本。

## 初始化另一套发布密钥

现有公钥与私钥必须配套使用，正常发版不需要重新生成。以下命令只用于初始化；已有客户端仍信任旧公钥，更换密钥前需安排迁移。
```
pip install cryptography
python scripts/sign-release.py keygen --out ~/clipsync-release-key.pem
```
把打印出来的 `EmbeddedPublicKeySpki = ...` 填进 `windows/ClipSync.Core/Update/UpdateSignature.cs`，然后提交（公钥可以公开）。
私钥**绝不进仓库**：在仓库外保存备份，把 PEM 全文写入 GitHub Actions 仓库 secret `CLIPSYNC_RELEASE_SIGNING_KEY`。这套密钥专用于更新 ZIP 验签，与 Android APK 签名和 Windows EXE 的 Authenticode 签名独立。

## 每次发版
```
python scripts/sign-release.py sign --key ~/clipsync-release-key.pem --pub-source windows/ClipSync.Core/Update/UpdateSignature.cs ClipSync-windows-x64.zip
python scripts/sign-release.py verify --pub-source windows/ClipSync.Core/Update/UpdateSignature.cs ClipSync-windows-x64.zip
```
手动发布时，把生成的 `ClipSync-windows-x64.zip.sig` 和 ZIP、`.sha256` 一起上传到同一个 release。正常 tag 发布由工作流完成这些步骤。

也可以用 openssl：`openssl dgst -sha256 -sign key.pem zip | base64 -w0 > zip.sig`。
