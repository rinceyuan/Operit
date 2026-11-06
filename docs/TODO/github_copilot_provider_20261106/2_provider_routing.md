# 枚举注册与按模型路由的 Provider

## 现状

- `OPENAI_CODEX` 由 `CodexProvider` 继承 `OpenAIResponsesProvider` 实现，通过 `ApiKeyProvider` 注入 OAuth token，并覆写 `applyAuthenticationHeaders` 追加专用请求头
- `OPENCODE` 由 `OpenCodeProvider` 以委托方式实现：按模型选择协议子类，外层 `AIService by delegate`

Copilot 的协议分派仿照 OpenCode；请求头不覆写父类钩子，而是用 OkHttp 拦截器统一处理。

## 注册点

- `data/model/ModelConfigData.kt`：枚举新增 `GITHUB_COPILOT`
- `data/collects/ApiProviderConfigCollect.kt`：`requiresApiKey = false`，默认端点与模型留空
- `data/collects/ModelPricingDefaultsCollect.kt`：零价，费用由 GitHub 结算
- `api/chat/llmprovider/EndpointCompleter.kt`：与 `OPENAI_CODEX` 同组，原样返回
- `api/chat/llmprovider/AIServiceFactory.kt`：调用 `GitHubCopilotProvider.create`
- `api/chat/llmprovider/ChatConfigReadiness.kt` 与 `ModelConfigScreen`：见 [4_settings_ui_and_readiness.md](4_settings_ui_and_readiness.md)
- `ui/features/settings/sections/ModelApiSettingsSection.kt`：区域提示、显示名、颜色、专用区块
- `ui/features/settings/sections/AdvancedSettingsSection.kt`：与 Codex 一样隐藏 API Key 池
- `assets/model_logos/GITHUB_COPILOT/`：Primer Octicons 的 copilot 图标，MIT 许可
- `examples/types/software_settings.d.ts`、`examples/operit_editor.ts`：联合类型加入 `GITHUB_COPILOT`

`ReleasedProviderModelKeyDecoder` 通过 `ApiProviderType.entries` 自动覆盖，`CliToolModeSupport` 走默认分支，均无需修改。

## GitHubCopilotProvider

位于 `api/chat/llmprovider/GitHubCopilotProvider.kt`：

- `GitHubCopilotTokenProvider`：`getApiKey()` 返回 `authManager.getValidToken().token`，父类据此写入 `Authorization: Bearer`
- `GitHubCopilotRouting.protocolFor`：`supported_endpoints` 含 `/chat/completions` 或未声明时走 Chat Completions，否则含 `/responses` 时走 Responses，其余抛出不支持
- `GitHubCopilotChatProvider : OpenAIProvider`，`providerType = OPENAI_GENERIC`，开启流式 usage
- `GitHubCopilotResponsesProvider : OpenAIProvider`，`useResponsesApi = true`，未开思考时剥离历史推理元数据，与 OpenCode 的 Responses 路由一致
- `create` 只读取已持久化的模型目录决定协议；`AIServiceFactory.createService` 是同步函数，不能在此发网络请求。模型不在目录中时抛出明确错误，提示刷新模型列表
- 端点为 `authState.apiBase` 加协议路径，不读取 `config.apiEndpoint`
- 图片与工具调用开关与模型目录的 `vision`、`tool_calls` 能力取交集，避免向不支持的模型发送

## 请求拦截器

`GitHubCopilotRequestInterceptor` 加在由共享客户端派生的 OkHttpClient 上：

- 用 `GitHubCopilotProtocol.applyCopilotHeaders` 写入 `Copilot-Integration-Id`、`Editor-Version`、`Editor-Plugin-Version`、`User-Agent`、`Openai-Intent`、`X-GitHub-Api-Version`、`X-Request-Id`
- `X-Initiator`：只看请求体最后一条 `messages` 或 `input`，角色为 `user` 时为 `user`，否则为 `agent`；工具结果续轮因此不会被计为用户轮
- 请求体任意位置出现 `image_url` 或 `input_image` 时加 `Copilot-Vision-Request: true`

## 测试

`GitHubCopilotProviderTest` 覆盖：

- `supported_endpoints` 的三种组合、不支持协议时报错、端点拼接
- `X-Initiator`：用户轮、工具续轮、Responses 输入
- 图片检测：Chat 与 Responses 的图片片段，纯文本中的同名字符串不误判

## 完成

[DONE]

