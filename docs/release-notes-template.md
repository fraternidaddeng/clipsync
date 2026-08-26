# 发布说明模板（release notes template)

用法：每次发布复制本模板为 `docs/releases/vX.Y.Z.md`（或直接粘贴进 GitHub Release 正文），把尖括号占位符全部替换；没有内容的小节写「无」，不要删除小节。与 `CHANGELOG.md` 的分工：CHANGELOG 是逐条流水，发布说明是面向用户的叙述与验收声明。

---

# ClipSync v<X.Y.Z>

- 发布日期：<YYYY-MM-DD>
- 构建 commit：`<sha>`
- 分支/标签：`<ref>`

## 下载与校验

| 产物 | 文件 | SHA-256 |
|---|---|---|
| Windows 便携版 | `ClipSync-windows-x64.zip` | `<sha256>` |
| Android APK | `ClipSync-android.apk` | `<sha256>` |

（文件名以打包脚本实产为准——`scripts/package-windows.ps1` / `scripts/package-android.ps1` 产出不带版本号后缀的固定文件名；未配置签名 secrets 时 Android 产物为 `ClipSync-android-unsigned.apk`，不可安装。SHA-256 与构建 SHA 由 `release.yml` 在 Release 正文自动注明，可直接对照回填。）

APK 签名指纹：`<签名证书 SHA-256，跨版本必须一致>`

## 本版亮点

<面向用户的 1–3 段话：这一版解决了什么、谁应该升级。>

## 变更明细

### 新增

- <条目，注明平台 [Windows]/[Android]/[双端]>

### 变更

- <条目>

### 修复

- <条目>

### 安全

- <涉及配对、密钥、TLS、日志卫生的变更单列；无则写「无」>

## 验证状态（诚实声明，不得省略）

- 自动化：<N> 个用例全绿（Android JVM <n1> / 跨平台对端 <n2> / Windows 应用层 <n3>），CI run：<链接>。
- 人工 QA：按 `docs/manual-qa-checklist.md` 执行，通过 <n>/<总数> 项；失败/跳过项见「已知限制」。
- 实体机矩阵：`docs/device-validation-matrix.md` 当前状态——D1 <状态> / D2 <状态> / D3 <状态> / D4 <状态>。**未实测的组合不构成兼容性承诺。**

## 已知限制

- <本版明确不工作/未验证的场景，逐条列出；来源：QA 清单失败项 + 矩阵 NOT_TESTED 缺口>

## 升级说明

- <是否需要重新配对、数据是否自动迁移、配置变更；全新安装参照安装文档 <链接>>
