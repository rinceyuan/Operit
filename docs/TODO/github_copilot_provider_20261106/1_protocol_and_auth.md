# 协议常量、登录与凭据

## 现状

Codex 的登录栈分为四层，可作为 Copilot 的对照：

- `data/api/CodexOAuthClient.kt`：协议常量 `CodexOAuthProtocol` 与 token 请求
- `data/preferences/CodexAuthPreferences.kt`：`EncryptedSharedPreferences` 存储，含 Keystore 恢复失败时重置的处理，背景见 [codex_auth_keystore_restore_20260904](../codex_auth_keystore_restore_20260904/index.md)
- `data/api/CodexAuthManager.kt`：单例，暴露 `authState: StateFlow`，负责刷新、登出、额度
- `ui/features/codex/`：PKCE + 本地回环回调服务器 + 登录对话框

Copilot 使用 Device Flow，没有回调，因此不需要回环服务器。

## 协议细节

登录，`{domain}` 为 `github.com` 或 `*.ghe.com`：

- `POST https://{domain}/login/device/code`，`{client_id: "Iv1.b507a08c87ecfe98", scope: "read:user"}`，`Accept: application/json`
- 返回 `device_code`、`user_code`、`verification_uri`、`expires_in`、`interval`
- 轮询 `POST https://{domain}/login/oauth/access_token`，`grant_type` 为 `urn:ietf:params:oauth:grant-type:device_code`
- `authorization_pending` 继续；`slow_down` 使用返回的新 `interval`，未返回时在当前间隔上加 5 秒；其余错误终止
- 每次轮询在服务端间隔上额外等待 3 秒，减少 `slow_down`

Copilot token 交换：

- `GET {githubApi}/copilot_internal/v2/token`，github.com 的 `{githubApi}` 为 `https://api.github.com`，`*.ghe.com` 为 `https://api.{domain}`
- 请求头 `Authorization: token <GitHub token>`、`Editor-Version`、`Editor-Plugin-Version`、`User-Agent`、`X-GitHub-Api-Version: 2025-04-01`
- 响应字段 `token`、`expires_at`（秒）、`endpoints.api`；`endpoints.api` 缺失时视为失败，不推断默认主机
- token 约 30 分钟过期，过期前 2 分钟内重新交换；交换返回 401 说明 GitHub 授权已被撤销，清空登录状态

账号与额度：

- `GET {githubApi}/copilot_internal/user`，同上请求头
- 使用 `login`、`copilot_plan`、`chat_enabled`、`quota_snapshots.premium_interactions`、`quota_snapshots.chat`、`quota_reset_date`
- `chat_enabled == false` 时拒绝登录

参考实现：ericc-ch/copilot-api `src/lib/api-config.ts`、`src/lib/token.ts`，avante.nvim `lua/avante/providers/copilot.lua`。

## 实现

- `data/api/GitHubCopilotProtocol.kt`：client_id、版本号、编辑器标识、域名校验、URL 拼接、两组请求头
- `data/api/GitHubCopilotAuthClient.kt`：设备码、轮询、token 交换、账号信息、模型目录请求与解析
- `data/preferences/GitHubCopilotAuthPreferences.kt`：独立加密存储 `github_copilot_credentials`，保存域名、GitHub token、login、`apiBase` 与模型目录；读失败时只重置本存储
- `data/api/GitHubCopilotAuthManager.kt`：单例；`completeLogin` 依次轮询、交换、校验账号、拉取目录后保存；`getValidToken` 用 Mutex 保证并发请求只交换一次，Copilot token 只保存在内存

## 测试

- `GitHubCopilotAuthClientTest`：域名规范化、`*.ghe.com` 的 URL、用户信息与模型目录解析

## 完成

[DONE]

