# 蓝牙产品路径 QA · 本机 agent 提示词（复制即用）

> 把下面整段复制给你本机（有真机的那台）的 agent 执行。

---

请在真机上执行 `docs/bluetooth-product-path-qa.md` 的蓝牙备援产品路径 QA，要求：

1. **拉取最新代码**：在仓库 `main` 分支执行 `git pull`，记录 `git rev-parse HEAD` 的 commit。
2. **双端从同一 commit 构建并安装**：Windows 端跑 `scripts/build-windows.ps1`，Android 端跑 `scripts/build-android.ps1`，把构建产物装到两台真机上。
3. **严格按清单顺序执行** `docs/bluetooth-product-path-qa.md` 第 0–7 节，不跳步、不换序；每项记录 通过 / 失败 / 未测 + 原始观察（状态文案照抄），截图/日志不得含真实剪贴板正文。
4. **结果填入 `docs/bluetooth-product-path-report.md`**：若该文件不存在，先参照 `docs/bluetooth-phase0-report-template.md` 的样式新建一份（按清单 0–7 节逐项列行，附操作者、日期、构建 commit、设备信息），再填写。空着的项写「未测」，不要删行。
5. **CI 说明**：GitHub Actions 目前一次都没跑过，需要仓库所有者在网页端启用/核验（见 `docs/ci-status.md`），这不是你要修的问题；报告里如实注明「CI 未运行，待所有者启用 Actions」即可。
6. **收尾**：提交报告（只提交文档改动），`git pull --rebase` 后推送到 `main`，回报最终 commit SHA 和逐项结果摘要。

注意：本清单结果不构成 READY 声明，任何文档不得因此出现 READY 字样。
