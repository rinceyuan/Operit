---
fork_repository: https://github.com/rinceyuan/Operit.git
working_branch: feat/github-copilot-provider
status: in_progress
---

# GitHub Copilot 模型提供商

## 背景

Operit 的模型提供商由 `ApiProviderType` 枚举驱动，`AIServiceFactory` 按枚举创建具体 `AIService`。现有提供商分为两类：

- API Key 类：OpenAI、Anthropic、OpenRouter 等，用户填写端点与密钥
- 账号订阅类：`OPENAI_CODEX`，通过 ChatGPT OAuth 登录，使用订阅额度，端点固定，模型目录动态获取

GitHub Copilot 属于第二类。用户持有 Copilot Free、Pro、Pro+、Business 或 Enterprise 订阅后，可以用 GitHub 账号授权直接调用 Copilot 托管的 GPT、Claude、Gemini 等模型，计费走用户自己的 GitHub AI Credits。

## 协议现状

截至 2026-11 的社区与官方客户端接入方式，详细调研见 [1_protocol_and_auth.md](1_protocol_and_auth.md)：

- 登录使用 GitHub OAuth Device Flow，客户端只需要 `client_id`，不需要 secret，适合移动端
- 官方合作客户端 opencode、Zed 使用自有 OAuth App，把 GitHub token 直接作为 `Authorization: Bearer` 调用 Copilot API
- ericc-ch/copilot-api 等社区项目使用 `Iv1.b507a08c87ecfe98`，先经 `copilot_internal/v2/token` 交换 Copilot token；该交换对自有 client_id 返回 403
- 每个账号的 API 基址由 Copilot token 响应或 `copilot_internal/user` 的 `endpoints.api` 给出，例如 `https://api.individual.githubcopilot.com`
- `GET {base}/models` 返回模型目录，每个模型通过 `supported_endpoints` 声明支持 `/chat/completions`、`/responses` 或 `/v1/messages`
- GitHub 已正式与 opencode、Zed 合作，二者均使用自有 OAuth App；GitHub Models `models.github.ai` 已于 2026-07-30 下线，不再是可选路线

## 意图

新增内置提供商 `GITHUB_COPILOT`，复用 Codex 已验证的“账号登录 + 固定端点 + 动态模型目录 + 额度展示”产品形态，并复用 OpenCode 已验证的“按模型选择协议”路由模式，把请求分派给现有的 Chat Completions、Responses、Claude Messages 三套实现。

## 关键决策

已确认：

1. OAuth 应用身份采用 Copilot 编辑器插件的 GitHub App `Iv1.b507a08c87ecfe98`，与 ericc-ch/copilot-api、CopilotChat.nvim、avante.nvim 相同。登录得到的 GitHub token 经 `copilot_internal/v2/token` 交换为短期 Copilot token，对话请求携带 `Editor-Version`、`Copilot-Integration-Id: vscode-chat` 等编辑器请求头。这样可见完整模型目录；代价是该身份未获 GitHub 对第三方的授权，存在账号风控风险，设置页需如实告知用户。自建 OAuth App 的 token 无法完成该交换。
2. 不复用插件市场的 GitHub 登录。市场登录经由 `GitHubOAuthBrokerService` 服务端中转，作用域与凭据生命周期都不同，Copilot 使用独立的 Device Flow 和独立的加密存储，退出互不影响。
3. 首版支持 GitHub Enterprise Cloud 数据驻留域名 `*.ghe.com`：登录时填写域名，设备码、token 交换与账号接口都按该域名拼接；github.com 上的 Business、Enterprise 订阅由 Copilot token 返回的 `endpoints.api` 自动区分。
4. 协议选择优先 `/chat/completions`，模型不支持时再用 `/responses`。Claude 模型同样走 Chat Completions，首版不处理 `reasoning_opaque` 思考回传，`/v1/messages` 留作后续项。

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
- 首版不实现 `/v1/messages` 与 Claude 思考块回传
- 不实现 `POST /models/{id}/policy` 自动启用模型，首版只展示已启用模型，并提示用户去 GitHub 设置页启用
- 不接入 Copilot SDK 或 Copilot CLI 运行时，Android 上不可行
- 不改动现有 Codex、OpenCode、OpenAI、Claude 提供商的行为

## 步骤

1. [DONE] 协议常量、Device Flow 客户端、凭据存储与 AuthManager，见 [1_protocol_and_auth.md](1_protocol_and_auth.md)
2. [DONE] 枚举注册与按模型协议路由的 Provider，见 [2_provider_routing.md](2_provider_routing.md)
3. [DONE] 模型目录获取与能力映射，见 [3_model_catalog.md](3_model_catalog.md)
4. [DONE] 设置页、登录对话框与就绪检查，见 [4_settings_ui_and_readiness.md](4_settings_ui_and_readiness.md)
5. 额度展示、测试、文档与 i18n，见 [5_quota_tests_docs.md](5_quota_tests_docs.md)；额度与测试已完成，用户文档与 i18n 待办

## 风险

- 使用 `Iv1.b507a08c87ecfe98` 与编辑器请求头不在 GitHub 对第三方的授权范围内，GitHub 曾对“不受支持的客户端”发出警告并暂停 Copilot 权限，用户需自行承担
- Copilot 私有接口 `copilot_internal/*`、编辑器版本号与 `X-GitHub-Api-Version` 可能变更，协议常量集中在 `GitHubCopilotProtocol`，便于跟进
- 请求头 `X-Initiator` 影响计费与限流归类，必须只根据最后一条消息判定，避免把工具续轮误报为用户轮
