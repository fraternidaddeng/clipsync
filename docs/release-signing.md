# Windows 更新包签名

自 fix/deep-review 起，Windows 端的自动更新必须同时满足两条才会安装：
1. SHA-256 与 GitHub 上的 release 元数据一致。元数据（latest JSON、`.sha256`、`.sig`）**只从 GitHub 获取**，第三方代理只能用来下载 ZIP 本体。
2. `<zip>.sig` 能用客户端内置的公钥（ECDSA P-256 / SHA-256）验证通过。

`UpdateSignature.EmbeddedPublicKeySpki` 为空时，客户端会拒绝自动安装，界面显示“校验失败”，用户只能去 GitHub 手动下载。

## 一次性：生成密钥对
```
pip install cryptography
python scripts/sign-release.py keygen --out ~/clipsync-release-key.pem
```
把打印出来的 `EmbeddedPublicKeySpki = ...` 填进 `windows/ClipSync.Core/Update/UpdateSignature.cs`，然后提交（公钥可以公开）。
私钥**绝不进仓库**：离线保存，或者作为 CI secret 使用（例如 `CLIPSYNC_RELEASE_SIGNING_KEY`）。

## 每次发版
```
python scripts/sign-release.py sign --key ~/clipsync-release-key.pem ClipSync-windows-x64.zip
python scripts/sign-release.py verify --pub <base64 spki> ClipSync-windows-x64.zip
```
把生成的 `ClipSync-windows-x64.zip.sig` 和 ZIP、`.sha256` 一起上传到同一个 release。

也可以用 openssl：`openssl dgst -sha256 -sign key.pem zip | base64 -w0 > zip.sig`。
