package com.ai.assistance.operit.data.api

import java.util.Locale
import java.util.UUID
import okhttp3.Request

/**
 * GitHub Copilot 协议常量。
 *
 * 登录使用 Copilot 编辑器插件的 GitHub App 身份 `Iv1.b507a08c87ecfe98`，
 * 获取的 GitHub OAuth token 通过 `copilot_internal/v2/token` 交换为短期 Copilot token，
 * 对话请求携带编辑器标识请求头。该身份才能访问完整的 Copilot 模型目录，
 * 自建 OAuth App 的 token 无法完成交换（返回 403）。
 */
object GitHubCopilotProtocol {
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val SCOPE = "read:user"
    const val DEFAULT_DOMAIN = "github.com"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

    const val GITHUB_API_VERSION = "2025-04-01"
    const val COPILOT_API_VERSION = "2025-10-01"
    const val EDITOR_VERSION = "vscode/1.106.0"
    const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
    const val USER_AGENT = "GitHubCopilotChat/0.26.7"
    const val INTEGRATION_ID = "vscode-chat"
    const val OPENAI_INTENT = "conversation-panel"

    // 服务端轮询间隔之外的安全余量，减少 slow_down 响应。
    const val POLL_SAFETY_MARGIN_MILLIS = 3_000L
    const val SLOW_DOWN_INCREMENT_SECONDS = 5L
    // Copilot token 在过期前该时间内视为需要重新交换。
    const val TOKEN_REFRESH_WINDOW_MILLIS = 120_000L

    const val ENDPOINT_CHAT_COMPLETIONS = "/chat/completions"
    const val ENDPOINT_RESPONSES = "/responses"

    /**
     * 规范化用户输入的 GitHub 域名：去掉协议与路径并转小写。
     * github.com 以外只接受 GitHub Enterprise Cloud 数据驻留域名 `*.ghe.com`。
     */
    fun normalizeDomain(input: String): String {
        val host = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .lowercase(Locale.ROOT)
        require(host.isNotEmpty()) { "GitHub domain is empty" }
        require(host == DEFAULT_DOMAIN || host.endsWith(".ghe.com")) {
            "Unsupported GitHub domain: $host"
        }
        return host
    }

    fun deviceCodeUrl(domain: String): String = "https://$domain/login/device/code"

    fun accessTokenUrl(domain: String): String = "https://$domain/login/oauth/access_token"

    fun githubApiBase(domain: String): String =
        if (domain == DEFAULT_DOMAIN) "https://api.github.com" else "https://api.$domain"

    fun copilotTokenUrl(domain: String): String =
        "${githubApiBase(domain)}/copilot_internal/v2/token"

    fun copilotUserUrl(domain: String): String = "${githubApiBase(domain)}/copilot_internal/user"

    fun githubSettingsUrl(domain: String): String = "https://$domain/settings/copilot"

    /** 访问 api.github.com 私有接口时的请求头，认证使用 GitHub OAuth token。 */
    fun applyGitHubHeaders(builder: Request.Builder, oauthToken: String): Request.Builder {
        return builder
            .header("Authorization", "token $oauthToken")
            .header("Accept", "application/json")
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
    }

    /** 访问 Copilot API 时的固定请求头，认证使用交换得到的 Copilot token。 */
    fun applyCopilotHeaders(builder: Request.Builder, copilotToken: String): Request.Builder {
        return builder
            .header("Authorization", "Bearer $copilotToken")
            .header("Copilot-Integration-Id", INTEGRATION_ID)
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("Openai-Intent", OPENAI_INTENT)
            .header("X-GitHub-Api-Version", COPILOT_API_VERSION)
            .header("X-Request-Id", UUID.randomUUID().toString())
    }
}
