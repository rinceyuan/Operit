# 模型目录与能力映射

## 现状

- `CodexModelListFetcher` 从外部目录拉取并按 `CodexModelPolicy` 过滤，结果为 `List<ModelOption>`
- `ModelListFetcher` 为通用 `/v1/models` 解析，不认识 Copilot 的 `capabilities` 与 `supported_endpoints`

## 接口

`GET {apiBase}/models`，请求头与对话相同。响应 `{ "data": [Model] }`，使用到的字段：

- `id`、`name`、`vendor`、`is_chat_default`、`model_picker_enabled`
- `policy.state`
- `supported_endpoints`
- `capabilities.type`
- `capabilities.limits.max_context_window_tokens`
- `capabilities.supports.tool_calls`、`vision`

## 过滤规则

与 Zed 一致：

- `capabilities.type == "chat"`
- `model_picker_enabled == true`
- `policy` 不存在或 `policy.state == "enabled"`
- `is_chat_default` 的模型排在首位

被策略禁用的模型不展示。设置页提示“部分模型需要先在 GitHub Copilot 设置中启用”，并提供跳转 `https://{domain}/settings/copilot` 的按钮。

## 实现

- 解析位于 `GitHubCopilotAuthClient.parseModels`，结果为 `GitHubCopilotModel`
- `GitHubCopilotModelListFetcher` 转为 `ModelOption`，名称附带厂商，例如 `Gemini 3.8 Flash · Google`
- 每次成功拉取后写入 `GitHubCopilotAuthPreferences` 的加密存储，进程启动时读入内存 `StateFlow`；登出时一并清除

`AIServiceFactory.createService` 是同步函数，无法在创建服务时发起网络请求，因此 `GitHubCopilotProvider.create` 只读取已持久化的目录决定协议。所选模型不在目录中时直接抛出明确错误，提示到设置页刷新 Copilot 模型列表，不猜测协议。

## 能力映射

不改动用户的开关设置，在创建服务时与目录能力取交集：

- 工具调用：`enableToolCall && supports.tool_calls`
- 图片输入：`enableDirectImageProcessing && supports.vision`
- 音频与视频：Copilot 不支持，设置页切到 Copilot 时关闭且不显示开关

## 实测

企业账号拉取到 13 个模型，涵盖 GPT、Gemini、Grok、MAI；该账号策略未启用 Claude。Gemini 3.8 Flash 路由到 `/chat/completions`，Grok 4.7 只声明 `/responses`，两者的聊天与 Tool Call 连接测试均通过。

## 后续项

- 思考配置：按 `reasoning_effort`、`max_thinking_budget` 生成思考档位，并在 `ModelThinkingConfigDefaultsCollect` 中加入 `GITHUB_COPILOT`
- 上下文长度：按 `max_context_window_tokens` 自动填写

## 完成

[DONE]
