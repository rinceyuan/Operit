# 协议常量、登录与凭据

## 现状

Codex 的登录栈分为四层，可作为 Copilot 的对照：

- `data/api/CodexOAuthClient.kt`：协议常量 `CodexOAuthProtocol` 与 token 请求
- `data/preferences/CodexAuthPreferences.kt`：`EncryptedSharedPreferences` 存储，含 Keystore 恢复失败时重置的处理，背景见 [codex_auth_keystore_restore_20260904](../codex_auth_keystore_restore_20260904/index.md)
- `data/api/CodexAuthManager.kt`：单例，暴露 `authState: StateFlow`，负责刷新、登出、额度
- `ui/features/codex/`：PKCE + 本地回环回调服务器 + 登录对话框

Copilot 使用 Device Flow，没有回调，也没有 refresh token，因此不需要回环服务器和刷新逻辑。

## 协议细节

登录：

- `POST https://{domain}/login/device/code`，表单或 JSON `{client_id, scope: "read:user"}`，`Accept: application/json`
- 返回 `device_code`、`user_code`、`verification_uri`、`expires_in`、`interval`
- 轮询 `POST https://{domain}/login/oauth/access_token`，`{client_id, device_code, grant_type: "urn:ietf:params:oauth:grant-type:device_code"}`
- `authorization_pending` 继续；`slow_down` 使用返回的新 `interval`，未返回时在当前间隔上加 5 秒；`expired_token`、`access_denied` 终止
- opencode 在服务端间隔上额外加 3 秒余量，可照做以减少 `slow_down`

账号与端点发现：

- `GET https://api.{domain}/copilot_internal/user`，github.com 对应 `https://api.github.com/copilot_internal/user`
- 请求头 `Authorization: Bearer <gho>`、`X-GitHub-Api-Version: 2025-04-01`、`Accept: application/json`、`User-Agent: Operit/<version>`
- `chat_enabled == false` 时拒绝登录；`can_signup_for_limited == true` 时提示开通 Copilot Free
- 保存 `endpoints.api` 作为 Copilot API 基址，同时保存 `copilot_plan`、`access_type_sku`、`quota_reset_date` 用于展示
- `endpoints.api` 是唯一的基址来源，缺失时登录失败并展示原因，不推断默认主机；opencode 会推断 `https://api.githubcopilot.com` 或 `https://copilot-api.{domain}`，本项目不采用这种兜底

参考实现：anomalyco/opencode `packages/core/src/plugin/provider/github-copilot.ts`，zed-industries/zed `crates/copilot_chat/src/copilot_oauth.rs`。

## 新增文件

- `data/api/GitHubCopilotProtocol.kt`
  - `object GitHubCopilotProtocol`：`CLIENT_ID`、`SCOPE`、`DEFAULT_DOMAIN = "github.com"`、`USER_API_VERSION`、`CHAT_API_VERSION`、`OPENAI_INTENT`
  - `fun deviceCodeUrl(domain)`、`accessTokenUrl(domain)`、`userUrl(domain)`
- `data/api/GitHubCopilotDeviceFlowClient.kt`
  - `suspend fun requestDeviceCode(domain): DeviceCodeResponse`
  - `suspend fun pollAccessToken(domain, deviceCode, intervalSeconds, expiresAt): String`，内部处理 pending、slow_down、过期与取消
  - `suspend fun fetchCopilotUser(domain, token): CopilotUserResponse`
- `data/preferences/GitHubCopilotAuthPreferences.kt`
  - `data class GitHubCopilotAuthState(domain, oauthToken, apiBase, login, plan, sku)`
  - 独立的加密存储 `github_copilot_oauth_credentials`，读失败时只重置本存储，沿用 Codex 已确认的处理方式
- `data/api/GitHubCopilotAuthManager.kt`
  - 单例，`authState: StateFlow<GitHubCopilotAuthState?>`
  - `suspend fun completeLogin(domain, token)`：调用 user 接口校验并保存
  - `fun requireState(): GitHubCopilotAuthState`
  - `suspend fun logout()`：清空本地凭据；GitHub OAuth App token 吊销需要 client secret，客户端不做，登出说明里提示用户可在 GitHub 设置页撤销授权
  - `suspend fun fetchUser(): Result<CopilotUserResponse>`：供额度刷新与 401 后重新校验使用

## 错误语义

- user 接口返回 401：token 已被撤销，清空凭据并让就绪检查返回需要登录
- user 接口返回 403 或 `chat_enabled == false`：账号无 Copilot 对话权限，登录对话框展示原因，不保存凭据
- 网络异常：登录对话框展示错误并允许重试，不改变已保存状态

## 测试

- `GitHubCopilotDeviceFlowClientTest`：用 MockWebServer 覆盖 pending、slow_down 带与不带 interval、expired、denied、成功
- `GitHubCopilotProtocolTest`：github.com 与 `acme.ghe.com` 的三个 URL
- `GitHubCopilotUserResponseTest`：`chat_enabled=false`、缺失 `endpoints.api` 导致登录失败、完整字段解析

## 完成

