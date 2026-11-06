# 模型目录与能力映射

## 现状

- `CodexModelListFetcher` 从外部目录拉取并按 `CodexModelPolicy` 过滤，结果为 `List<ModelOption>`
- `ModelListFetcher` 为通用 `/v1/models` 解析，不认识 Copilot 的 `capabilities` 与 `supported_endpoints`
- 思考配置默认值集中在 `ModelThinkingConfigDefaultsCollect`，按 `providers` 与模型匹配规则生效

## 接口

`GET {apiBase}/models`，请求头与对话相同。响应 `{ "data": [Model] }`，使用到的字段：

- `id`、`name`、`vendor`、`is_chat_default`、`model_picker_enabled`
- `policy.state`
- `supported_endpoints`
- `capabilities.type`
- `capabilities.limits.max_context_window_tokens`、`max_prompt_tokens`、`max_output_tokens`
- `capabilities.supports.tool_calls`、`vision`、`streaming`、`reasoning_effort`、`max_thinking_budget`、`adaptive_thinking`
- `billing.token_prices` 或旧字段 `billing.multiplier`，仅用于展示

## 过滤规则

与 Zed 一致：

- `capabilities.type == "chat"`
- `model_picker_enabled == true`
- `policy` 不存在或 `policy.state == "enabled"`
- `is_chat_default` 的模型排在首位

被策略禁用的模型不展示。模型列表对话框底部提示“部分模型需要先在 GitHub Copilot 设置中启用”，并提供跳转 `https://github.com/settings/copilot` 的入口。

## 新增文件

`api/chat/llmprovider/GitHubCopilotModelCatalog.kt`：

- `data class CopilotModelInfo(id, name, vendor, supportedEndpoints, supportsTools, supportsVision, maxContextTokens, maxOutputTokens, reasoningEfforts, maxThinkingBudget)`
- `suspend fun fetch(authManager, client): Result<List<CopilotModelInfo>>`
- `fun toModelOptions(models): List<ModelOption>`，名称附带厂商，例如 `Claude Sonnet 4.6 · Anthropic`
- 目录持久化：每次成功拉取后写入独立 DataStore，键为 `domain + login`，内容为每个模型的 `id` 与 `supported_endpoints` 等路由所需字段
- `GitHubCopilotAuthManager` 初始化时把持久化目录读入内存 `StateFlow`

`AIServiceFactory.createService` 是同步函数，无法在创建服务时发起网络请求，因此 `GitHubCopilotProvider.create` 只读取内存中的目录决定协议。所选模型不在目录中时，创建服务直接抛出明确错误，提示到设置页刷新 Copilot 模型列表，不猜测协议。登出时同时清除该账号的目录。

## 能力映射

选中模型后，设置页按目录信息自动设置开关，用户仍可手动修改：

- `enableToolCall` 取 `supports.tool_calls`
- `enableDirectImageProcessing` 取 `supports.vision`
- 音频与视频固定关闭，Copilot 不支持
- 上下文长度取 `max_context_window_tokens / 1000`，写入现有上下文长度设置

## 思考配置

- Responses 协议模型：`reasoning_effort` 列表映射为思考档位，请求体写 `reasoning.effort`，复用 `OPENAI_RESPONSES` 规则
- Claude 协议模型：`max_thinking_budget` 存在时启用 `thinking.budget_tokens`，`adaptive_thinking` 为真时使用自适应思考，复用 `ANTHROPIC` 规则
- Chat 协议模型：`reasoning_effort` 存在时写入顶层 `reasoning_effort`

在 `ModelThinkingConfigDefaultsCollect` 对应规则的 `providers` 数组中加入 `GITHUB_COPILOT`，模型匹配沿用现有规则。

## 测试

- `GitHubCopilotModelCatalogTest`：过滤规则、默认模型排序、缺失可选字段、`supported_endpoints` 为空
- 使用脱敏的真实 `/models` 响应作为测试夹具

## 完成

