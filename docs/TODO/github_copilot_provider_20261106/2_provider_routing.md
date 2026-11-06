# 枚举注册与按模型路由的 Provider

## 现状

- `OPENAI_CODEX` 由 `CodexProvider` 继承 `OpenAIResponsesProvider` 实现，通过 `ApiKeyProvider` 注入 OAuth token，并覆写 `applyAuthenticationHeaders` 追加专用请求头
- `OPENCODE` 由 `OpenCodeProvider` 以委托方式实现：`OpenCodeRouting.protocolFor` 按模型选择 `OPENAI_RESPONSES_GENERIC`、`ANTHROPIC_GENERIC`、`GEMINI_GENERIC` 或 Chat Completions，再创建对应子类，外层 `AIService by delegate`
- `OpenAIProvider`、`OpenAIResponsesProvider`、`ClaudeProvider` 都是 `open class`，前两者提供 `applyAuthenticationHeaders`、`customizeFinalRequestObject` 钩子

Copilot 同时具备这两种特征，因此组合两者：认证与请求头仿照 Codex，协议分派仿照 OpenCode。

## 注册点清单

以下位置需要新增 `GITHUB_COPILOT` 分支，清单来自 `OPENAI_CODEX` 与 `NOUS_PORTAL` 的全部引用：

- `data/model/ModelConfigData.kt`：枚举新增 `GITHUB_COPILOT, // GitHub Copilot（GitHub OAuth）`
- `data/collects/ApiProviderConfigCollect.kt`：`requiresApiKey = false`，默认端点与默认模型留空；实际端点在请求时读取 `authState.apiBase`，模型由目录决定
- `data/collects/ModelPricingDefaultsCollect.kt`：`"GITHUB_COPILOT" to zeroPricing(PricingCurrency.USD)`，费用由 GitHub AI Credits 结算
- `data/collects/ModelThinkingConfigDefaultsCollect.kt`：在 Responses 与 Claude 思考规则的 `providers` 中加入 `GITHUB_COPILOT`，具体见 [3_model_catalog.md](3_model_catalog.md)
- `api/chat/llmprovider/EndpointCompleter.kt`：与 `OPENAI_CODEX` 同组，原样返回
- `api/chat/llmprovider/AIServiceFactory.kt`：新增分支调用 `GitHubCopilotProvider.create(...)`
- `api/chat/llmprovider/ChatConfigReadiness.kt`：见 [4_settings_ui_and_readiness.md](4_settings_ui_and_readiness.md)
- `ui/features/settings/sections/ModelApiSettingsSection.kt`：区域提示 `OVERSEAS`、显示名、颜色、专用区块
- `assets/model_logos/GITHUB_COPILOT/`：`ProviderLogoLoader` 按枚举名查找
- `examples/types/software_settings.d.ts` 与 `examples/operit_editor.ts`：字符串联合类型加入 `'GITHUB_COPILOT'`
- `res/values*/strings.xml`：`provider_github_copilot`

`ReleasedProviderModelKeyDecoder` 通过 `ApiProviderType.entries` 自动覆盖，`CliToolModeSupport` 走默认分支，均无需修改。

## 新增文件

`api/chat/llmprovider/GitHubCopilotProvider.kt`，结构如下：

- `private class GitHubCopilotTokenProvider(authManager) : ApiKeyProvider`
  - `getApiKey()` 返回 `authManager.requireState().oauthToken`
  - `getCandidateKeyCount()` 未登录时为 0
- `internal object GitHubCopilotRouting`
  - `fun protocolFor(model: CopilotModelInfo): ApiProviderType`：`supported_endpoints` 含 `/v1/messages` 选 `ANTHROPIC_GENERIC`，否则含 `/responses` 选 `OPENAI_RESPONSES_GENERIC`，否则选 `OPENAI_GENERIC`
  - `fun endpointFor(apiBase, protocol)`：分别拼接 `/v1/messages`、`/responses`、`/chat/completions`
- `internal object GitHubCopilotHeaders`
  - 固定头：`X-GitHub-Api-Version`、`Openai-Intent: conversation-edits`、`User-Agent: Operit/<version>`、`X-Interaction-Id: <会话ID>`
  - 动态头：`X-Initiator`、`Copilot-Vision-Request`
  - 不发送 `Editor-Version`、`Editor-Plugin-Version`、`Copilot-Integration-Id`，不伪装成 VS Code
- 三个协议子类，均覆写认证与请求体钩子：
  - `GitHubCopilotChatProvider : OpenAIProvider`，`includeUsageInStream = true`
  - `GitHubCopilotResponsesProvider : OpenAIResponsesProvider`，`store=false`，`include` 追加 `reasoning.encrypted_content`，与 `CodexProvider.customizeFinalRequestObject` 相同
  - `GitHubCopilotClaudeProvider : ClaudeProvider`，需要为 `ClaudeProvider` 增加一个 `protected open fun applyAuthenticationHeaders`，默认实现保持现有 `x-api-key` 行为，Copilot 子类改为 Bearer 并追加 `anthropic-version`
- `class GitHubCopilotProvider private constructor(delegate, ...) : AIService by delegate`
  - `companion fun create(config, context, client, customHeaders, supportsVision, enableToolCall)`
  - 从 `GitHubCopilotAuthManager` 读取已持久化的模型目录得到 `CopilotModelInfo`，按 `GitHubCopilotRouting` 创建子类；工厂是同步调用，此处不发网络请求，目录规则见 [3_model_catalog.md](3_model_catalog.md)
  - 端点使用 `authState.apiBase` 拼接，不读取 `config.apiEndpoint`
  - `getModelsList` 调用 `GitHubCopilotModelCatalog`

## X-Initiator 判定

只看最后一条 `PromptTurn`：

- 最后一条是用户输入且不只含工具结果时为 `user`
- 其余情况为 `agent`，包括工具结果续轮、自动总结、标题生成

Chat 与 Responses 子类在 `customizeFinalRequestObject` 中依据最终 JSON 判定，Claude 子类依据 `messages` 最后一项是否全为 `tool_result` 判定。判定结果通过请求级字段传给认证钩子，不使用全局状态，避免并发请求互相覆盖。

## 视觉请求头

请求体中存在 `image_url`、`input_image` 或 Anthropic `image` 块时发送 `Copilot-Vision-Request: true`。判定在构建请求体之后进行，与 `X-Initiator` 共用同一个请求级上下文。

## 401 处理

任一协议返回 401 时调用 `authManager.fetchUser()` 复核；复核仍为 401 则清空凭据，错误信息提示重新登录。不做静默重试。

## 测试

- `GitHubCopilotRoutingTest`：三种 `supported_endpoints` 组合与端点拼接
- `GitHubCopilotInitiatorTest`：用户轮、工具续轮、Claude `tool_result`、带图片的用户轮
- `ClaudeProvider` 认证钩子回归：默认实现仍发送 `x-api-key` 与 `anthropic-version`

## 完成

