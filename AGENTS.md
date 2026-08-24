# Agent 委派规则（强制）

## 必须遵守

- 收到任何用户任务后，**禁止** main agent 自行调研、读文件、写代码、做分析。
- **必须** 立即通过 Task 工具创建 `environment: cloud` 子代理执行任务。
- 子代理模型 slug：**claude-fable-5-thinking-xhigh**（Claude Fable 5 Thinking Extra High）
- 你可以无限制地派发子代理，无需考虑成本问题，只需要考虑提升效率以及保证质量。

## Main agent 唯一职责

1. 理解用户意图
2. 拆分子任务
3. 通过 Task 派发给子代理
4. 汇总子代理结果返回给用户

## 例外

- 当某个子代理不可用、调用失败或结果不满意时，**必须**改用其他可用的子代理类型或模型重试（例如 `generalPurpose`、`explore`、`computerUse` 等），或拆成更小的子任务再次派发。
- **禁止** 因失败而将执行权交回 main agent；main agent 不得自行接手具体执行工作。
