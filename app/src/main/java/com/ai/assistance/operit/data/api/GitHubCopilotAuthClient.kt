package com.ai.assistance.operit.data.api

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class GitHubCopilotDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMillis: Long,
    val intervalSeconds: Long,
)

data class GitHubCopilotToken(
    val token: String,
    val expiresAtMillis: Long,
    val apiBase: String,
)

data class GitHubCopilotQuota(
    val entitlement: Long,
    val remaining: Long,
    val percentRemaining: Double,
    val unlimited: Boolean,
)

data class GitHubCopilotUserInfo(
    val login: String,
    val plan: String?,
    val chatEnabled: Boolean,
    val premiumQuota: GitHubCopilotQuota?,
    val chatQuota: GitHubCopilotQuota?,
    val quotaResetDate: String?,
)

data class GitHubCopilotModel(
    val id: String,
    val name: String,
    val vendor: String,
    val supportedEndpoints: List<String>,
    val supportsToolCalls: Boolean,
    val supportsVision: Boolean,
    val maxContextTokens: Int,
    val isChatDefault: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("vendor", vendor)
        .put("supported_endpoints", JSONArray(supportedEndpoints))
        .put("tool_calls", supportsToolCalls)
        .put("vision", supportsVision)
        .put("max_context", maxContextTokens)
        .put("is_chat_default", isChatDefault)

    companion object {
        fun fromStoredJson(json: JSONObject): GitHubCopilotModel = GitHubCopilotModel(
            id = json.getString("id"),
            name = json.getString("name"),
            vendor = json.optString("vendor"),
            supportedEndpoints = json.getJSONArray("supported_endpoints").toStringList(),
            supportsToolCalls = json.getBoolean("tool_calls"),
            supportsVision = json.getBoolean("vision"),
            maxContextTokens = json.getInt("max_context"),
            isChatDefault = json.getBoolean("is_chat_default"),
        )
    }
}

class GitHubCopilotAuthException(message: String) : IOException(message)

/** GitHub 设备码登录、Copilot token 交换、账号信息与模型目录请求。 */
class GitHubCopilotAuthClient(private val client: OkHttpClient) {

    suspend fun requestDeviceCode(domain: String): GitHubCopilotDeviceCode {
        val body = JSONObject()
            .put("client_id", GitHubCopilotProtocol.CLIENT_ID)
            .put("scope", GitHubCopilotProtocol.SCOPE)
        val request = Request.Builder()
            .url(GitHubCopilotProtocol.deviceCodeUrl(domain))
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = executeJson(request, "device code")
        val now = System.currentTimeMillis()
        return GitHubCopilotDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.getString("verification_uri"),
            expiresAtMillis = now + json.getLong("expires_in") * 1000L,
            intervalSeconds = json.getLong("interval"),
        )
    }

    /** 按 RFC 8628 轮询，直到用户在浏览器中完成授权，返回 GitHub OAuth token。 */
    suspend fun pollAccessToken(domain: String, deviceCode: GitHubCopilotDeviceCode): String {
        var intervalSeconds = deviceCode.intervalSeconds
        while (System.currentTimeMillis() < deviceCode.expiresAtMillis) {
            delay(intervalSeconds * 1000L + GitHubCopilotProtocol.POLL_SAFETY_MARGIN_MILLIS)
            val body = JSONObject()
                .put("client_id", GitHubCopilotProtocol.CLIENT_ID)
                .put("device_code", deviceCode.deviceCode)
                .put("grant_type", GitHubCopilotProtocol.DEVICE_GRANT_TYPE)
            val request = Request.Builder()
                .url(GitHubCopilotProtocol.accessTokenUrl(domain))
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val json = executeJson(request, "access token")
            val accessToken = json.optString("access_token")
            if (accessToken.isNotBlank()) {
                return accessToken
            }
            when (val error = json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    intervalSeconds = if (json.has("interval")) {
                        json.getLong("interval")
                    } else {
                        intervalSeconds + GitHubCopilotProtocol.SLOW_DOWN_INCREMENT_SECONDS
                    }
                }
                else -> throw GitHubCopilotAuthException(
                    json.optString("error_description").ifBlank { error.ifBlank { "unknown error" } }
                )
            }
        }
        throw GitHubCopilotAuthException("Device code expired")
    }

    suspend fun exchangeCopilotToken(domain: String, oauthToken: String): GitHubCopilotToken {
        val request = GitHubCopilotProtocol
            .applyGitHubHeaders(Request.Builder(), oauthToken)
            .url(GitHubCopilotProtocol.copilotTokenUrl(domain))
            .get()
            .build()
        val json = executeJson(request, "Copilot token")
        val apiBase = json.optJSONObject("endpoints")?.optString("api").orEmpty().trimEnd('/')
        if (apiBase.isBlank()) {
            throw GitHubCopilotAuthException("Copilot token response has no endpoints.api")
        }
        return GitHubCopilotToken(
            token = json.getString("token"),
            expiresAtMillis = json.getLong("expires_at") * 1000L,
            apiBase = apiBase,
        )
    }

    suspend fun fetchUserInfo(domain: String, oauthToken: String): GitHubCopilotUserInfo {
        val request = GitHubCopilotProtocol
            .applyGitHubHeaders(Request.Builder(), oauthToken)
            .url(GitHubCopilotProtocol.copilotUserUrl(domain))
            .get()
            .build()
        return parseUserInfo(executeJson(request, "Copilot user"))
    }

    suspend fun fetchModels(token: GitHubCopilotToken): List<GitHubCopilotModel> {
        val request = GitHubCopilotProtocol
            .applyCopilotHeaders(Request.Builder(), token.token)
            .url("${token.apiBase}/models")
            .get()
            .build()
        return parseModels(executeJson(request, "Copilot models"))
    }

    private suspend fun executeJson(request: Request, label: String): JSONObject {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 只截取错误体前段，避免日志与提示中出现过长内容。
                    throw GitHubCopilotHttpException(
                        "GitHub Copilot $label request failed: HTTP ${response.code} ${body.take(300)}",
                        response.code,
                    )
                }
                JSONObject(body)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        internal fun parseUserInfo(json: JSONObject): GitHubCopilotUserInfo {
            val snapshots = json.optJSONObject("quota_snapshots")
            return GitHubCopilotUserInfo(
                login = json.optString("login"),
                plan = json.optString("copilot_plan").takeIf { it.isNotBlank() },
                chatEnabled = json.optBoolean("chat_enabled", false),
                premiumQuota = snapshots?.optJSONObject("premium_interactions")?.let(::parseQuota),
                chatQuota = snapshots?.optJSONObject("chat")?.let(::parseQuota),
                quotaResetDate = json.optString("quota_reset_date").takeIf { it.isNotBlank() },
            )
        }

        private fun parseQuota(json: JSONObject): GitHubCopilotQuota = GitHubCopilotQuota(
            entitlement = json.optLong("entitlement"),
            remaining = json.optLong("remaining"),
            percentRemaining = json.optDouble("percent_remaining", 0.0),
            unlimited = json.optBoolean("unlimited", false),
        )

        /**
         * 只保留模型选择器中可见、已启用的对话模型；默认模型排在首位。
         * 规则与 Zed 的 Copilot 客户端一致。
         */
        internal fun parseModels(json: JSONObject): List<GitHubCopilotModel> {
            val data = json.getJSONArray("data")
            val result = mutableListOf<GitHubCopilotModel>()
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val capabilities = model.optJSONObject("capabilities") ?: continue
                if (capabilities.optString("type") != "chat") continue
                if (!model.optBoolean("model_picker_enabled", false)) continue
                val policyState = model.optJSONObject("policy")?.optString("state")
                if (policyState != null && policyState != "enabled") continue

                val supports = capabilities.optJSONObject("supports")
                val limits = capabilities.optJSONObject("limits")
                result += GitHubCopilotModel(
                    id = model.getString("id"),
                    name = model.getString("name"),
                    vendor = model.optString("vendor"),
                    supportedEndpoints = model.optJSONArray("supported_endpoints")?.toStringList()
                        ?: emptyList(),
                    supportsToolCalls = supports?.optBoolean("tool_calls", false) ?: false,
                    supportsVision = supports?.optBoolean("vision", false) ?: false,
                    maxContextTokens = limits?.optInt("max_context_window_tokens", 0) ?: 0,
                    isChatDefault = model.optBoolean("is_chat_default", false),
                )
            }
            return result.distinctBy { it.id }.sortedByDescending { it.isChatDefault }
        }
    }
}

class GitHubCopilotHttpException(message: String, val statusCode: Int) : IOException(message)

internal fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
