---
fork_repository: https://github.com/rinceyuan/Operit.git
working_branch: feat/github-copilot-provider
status: planning
---

# GitHub Copilot 模型提供商

## 背景

Operit 的模型提供商由 `ApiProviderType` 枚举驱动，`AIServiceFactory` 按枚举创建具体 `AIService`。现有提供商分为两类：

- API Key 类：OpenAI、Anthropic、OpenRouter 等，用户填写端点与密钥
- 账号订阅类：`OPENAI_CODEX`，通过 ChatGPT OAuth 登录，使用订阅额度，端点固定，模型目录动态获取

GitHub Copilot 属于第二类。用户持有 Copilot Free、Pro、Pro+、Business 或 Enterprise 订阅后，可以用 GitHub 账号授权直接调用 Copilot 托管的 GPT、Claude、Gemini 等模型，计费走用户自己的 GitHub AI Credits。

## 协议现状

截至 2026-11，社区与官方客户端的接入方式已经收敛，详细调研见 [1_protocol_and_auth.md](1_protocol_and_auth.md)：

- 登录使用 GitHub OAuth Device Flow，客户端只需要 `client_id`，不需要 secret，适合移动端
- 拿到的 `gho_`/`ghu_` token 直接作为 `Authorization: Bearer` 调用 Copilot API，不再走 `copilot_internal/v2/token` 交换；该交换对自有 client_id 返回 403
- 每个账号的 API 基址来自 `GET https://api.github.com/copilot_internal/user` 的 `endpoints.api`，例如 `https://api.individual.githubcopilot.com`
- `GET {base}/models` 返回模型目录，每个模型通过 `supported_endpoints` 声明支持 `/chat/completions`、`/responses` 或 `/v1/messages`
- GitHub 已正式与 opencode、Zed 合作，二者均使用自有 OAuth App；GitHub Models `models.github.ai` 已于 2026-07-30 下线，不再是可选路线

## 意图

新增内置提供商 `GITHUB_COPILOT`，复用 Codex 已验证的“账号登录 + 固定端点 + 动态模型目录 + 额度展示”产品形态，并复用 OpenCode 已验证的“按模型选择协议”路由模式，把请求分派给现有的 Chat Completions、Responses、Claude Messages 三套实现。

## 关键决策

需要在动工前确认：

1. OAuth 应用身份。推荐由 Operit 维护者注册自有 GitHub OAuth App 并开启 Device Flow，`client_id` 作为常量随应用发布。这是官方 Copilot SDK 文档认可的路径，账号风险最低，但非合作伙伴应用可见的模型数量可能少于 VS Code。冒用 `Iv1.b507a08c87ecfe98` 或其他客户端的 client_id、伪造 `Editor-Version` 等请求头存在被判定为不受支持客户端而封号的先例，本方案不采用。
2. 不复用插件市场的 GitHub 登录。市场登录经由 `GitHubOAuthBrokerService` 服务端中转，作用域与凭据生命周期都不同，Copilot 使用独立的 Device Flow 和独立的加密存储，退出互不影响。
3. 是否支持 GitHub Enterprise Cloud 数据驻留域名 `*.ghe.com`。推荐首版即支持，成本只是登录时允许填写域名并据此拼接三个主机名。
4. Claude 模型走 `/v1/messages` 还是 `/chat/completions`。推荐按 `supported_endpoints` 优先 `/v1/messages`，与 opencode 一致，可直接复用 `ClaudeProvider` 的思考块与缓存逻辑，避免在 Chat Completions 中处理 `reasoning_opaque`。

## 作用域

- 新增 `ApiProviderType.GITHUB_COPILOT` 及全部注册点
- Device Flow 登录、加密凭据存储、账号与 `endpoints.api` 发现
- `/models` 目录获取、过滤与能力映射
- `GitHubCopilotProvider` 按模型路由到三种协议，并附加 Copilot 专用请求头
- 设置页 Copilot 专用区块、登录对话框、配置就绪检查
- 额度快照 `quota_snapshots` 的手动刷新展示
- 中英文字符串、Logo、TS 类型声明、单元测试、开发者文档

## 非目标

- 不实现代码补全 Completions 接口，只做对话模型
- 不实现 `copilot_internal/v2/token` 交换与 VS Code 身份伪装
- 不实现 `POST /models/{id}/policy` 自动启用模型，首版只展示已启用模型，并提示用户去 GitHub 设置页启用
- 不接入 Copilot SDK 或 Copilot CLI 运行时，Android 上不可行
- 不改动现有 Codex、OpenCode、OpenAI、Claude 提供商的行为

## 步骤

1. 协议常量、Device Flow 客户端、凭据存储与 AuthManager，见 [1_protocol_and_auth.md](1_protocol_and_auth.md)
2. 枚举注册与按模型协议路由的 Provider，见 [2_provider_routing.md](2_provider_routing.md)
3. 模型目录获取与能力映射，见 [3_model_catalog.md](3_model_catalog.md)
4. 设置页、登录对话框与就绪检查，见 [4_settings_ui_and_readiness.md](4_settings_ui_and_readiness.md)
5. 额度展示、测试、文档与 i18n，见 [5_quota_tests_docs.md](5_quota_tests_docs.md)

## 风险

- 自有 OAuth App 可见模型可能较少，属于 GitHub 侧策略，客户端无法绕过；文档中如实说明
- Copilot 私有接口 `copilot_internal/*` 与 `X-GitHub-Api-Version` 可能变更，协议常量集中在一个对象中，便于跟进
- 请求头 `X-Initiator` 影响计费与限流归类，必须只根据最后一条消息判定，避免把工具续轮误报为用户轮
