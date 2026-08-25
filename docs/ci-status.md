# CI 状态诊断：为什么 GitHub Actions 一次都没跑过

**诊断日期**：2026-08-25（UTC）
**诊断范围**：仅 `main`；仅使用只读 API 与仓库内证据。按任务约束，未开启/修改任何计费设置。

## 结论（TL;DR）

**工作流文件本身没有问题，无需修改。** `.github/workflows/ci.yml` 已被 GitHub 正确注册且状态为 `active`，触发器覆盖了所有实际发生的事件；但 GitHub 从未为任何事件**创建**过运行记录（全仓库 Actions 运行总数为 0）。这说明运行是在**仓库/账号设置层面被 GitHub 拒绝创建**的，而不是工作流 YAML 未命中触发条件。当前凭证无管理员权限（读取 Actions 设置返回 403），无法在 API 层面区分下述两个候选根因，需要仓库所有者在网页端确认。

## 证据链

1. **工作流已注册且 active**：`GET /repos/fraternidaddeng/clipsync/actions/workflows` 返回唯一工作流 `CI`（id `340854850`，path `.github/workflows/ci.yml`），`state: "active"`——不是 `disabled_manually` 也不是 `disabled_inactivity`。
2. **触发器正确**：`on.push.branches` 含 `main`、`cursor/**`、`feature/**`；`on.pull_request.branches` 含 `main`；另有 `workflow_dispatch`。没有 `paths`/`paths-ignore` 过滤，任何 main 推送都应命中。
3. **合格事件大量存在**：工作流落地 main（提交 `c456080`）之后，main 已有数十次推送（仓库 `pushed_at` 为 2026-08-25T06:21Z），且存在多条 `cursor/**` 分支推送。
4. **GitHub 确实在处理这些推送**：工作流注册信息的 `updated_at`（2026-08-25T00:24:52Z）与修改 ci.yml 的提交 `48e0a14` 的推送时间吻合——即推送事件到达了 GitHub 并触发了工作流**重新注册**，但依然**没有创建任何运行**。
5. **运行数为零**：`GET /actions/runs` 返回 `total_count: 0`（全仓库、所有事件类型）；抽查近期 main 提交（如 `cc77f3e`）的 check-runs 与 commit status 均为 0。历史上 4 个已合并 PR 也没有任何检查记录（这些 PR 均创建于工作流落地之前，属预期；关键异常在于其后的 push 事件）。
6. **仓库标志正常**：非 fork、未 archive、未被平台 disable。
7. **设置不可读**：`GET /repos/.../actions/permissions` 对当前凭证返回 403 "Resource not accessible by integration"（GitHub App 安装令牌缺少管理员读取权限），因此无法直接确认仓库 Actions 开关状态。

## 候选根因（二选一，需所有者网页端确认）

- **A. 仓库级 Actions 被禁用**：Settings → Actions → General → "Actions permissions" 选中了 "Disable actions"。此时工作流列表 API 仍会返回 `active`，但任何事件都不会创建运行——与观测完全一致。
- **B. 账号级限制（新账号 + 私有仓库）**：账号 `fraternidaddeng` 与本仓库同日创建（2026-08-24），私有仓库的 Actions 分钟数属计费资源。GitHub 对存在计费/验证问题或被反滥用策略暂缓的账号会**直接不创建运行记录**（而不是创建后失败/排队）——同样与"零运行"观测一致。Free 计划本身含 2000 分钟/月私有仓库额度，因此若属此类，多为**支付方式验证/账号核验**问题而非额度耗尽。

## 所有者操作清单（均在网页端，无需开启计费）

1. 打开 `github.com/fraternidaddeng/clipsync` → **Settings → Actions → General**：确认 "Actions permissions" 为 **Allow all actions and reusable workflows** 并保存。若原先是 Disable，改回后即根因 A 闭环。
2. 若该设置本就是允许：打开仓库 **Actions** 标签页，查看页面横幅提示（计费验证、spending limit、账号核验等），按提示完成**核验**（不需要购买付费计划）。
3. 恢复后验证：在 Actions 标签页选择 `CI` → **Run workflow**（`workflow_dispatch`）手动触发一次，或推送任意提交到 main；应出现 `validate-protocol` / `build-windows` / `build-android` 三个作业。
4. 验证通过后，可回填 `docs/manual-qa-results.md` 中"限制 6（CI 状态）"条目。

## 本次未做的事

- 未修改 `.github/workflows/ci.yml`（触发器无缺陷，无需修复）。
- 未尝试 `workflow_dispatch` 触发（当前凭证为只读，且写操作被任务规范禁止）。
- 未修改任何计费/账号设置（任务明确禁止）。
