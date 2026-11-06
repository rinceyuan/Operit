package com.ai.assistance.operit.data.api

import android.content.Context
import com.ai.assistance.operit.data.preferences.GitHubCopilotAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubCopilotAuthState
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

class GitHubCopilotAuthManager private constructor(context: Context) {
    private val preferences = GitHubCopilotAuthPreferences.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val authClient = GitHubCopilotAuthClient(httpClient)
    private val tokenMutex = Mutex()

    // Copilot token 有效期约 30 分钟，只保存在内存中，进程重启后重新交换。
    @Volatile
    private var cachedToken: GitHubCopilotToken? = null

    val authState: StateFlow<GitHubCopilotAuthState?> = preferences.authState
    val models: StateFlow<List<GitHubCopilotModel>> = preferences.models

    suspend fun requestDeviceCode(domain: String): GitHubCopilotDeviceCode =
        authClient.requestDeviceCode(domain)

    /** 完成设备码授权：轮询 OAuth token，验证 Copilot 权限并保存登录状态与模型目录。 */
    suspend fun completeLogin(domain: String, deviceCode: GitHubCopilotDeviceCode): GitHubCopilotAuthState {
        val oauthToken = authClient.pollAccessToken(domain, deviceCode)
        val token = authClient.exchangeCopilotToken(domain, oauthToken)
        val userInfo = authClient.fetchUserInfo(domain, oauthToken)
        if (!userInfo.chatEnabled) {
            throw GitHubCopilotAuthException("Copilot Chat is not enabled for this account")
        }
        val state = GitHubCopilotAuthState(
            domain = domain,
            oauthToken = oauthToken,
            login = userInfo.login,
            apiBase = token.apiBase,
        )
        tokenMutex.withLock { cachedToken = token }
        preferences.save(state)
        preferences.saveModels(authClient.fetchModels(token))
        return state
    }

    fun requireState(): GitHubCopilotAuthState =
        preferences.currentState() ?: throw GitHubCopilotAuthException("GitHub Copilot is not logged in")

    suspend fun getValidToken(): GitHubCopilotToken {
        cachedToken?.takeIf(::isFresh)?.let { return it }
        return tokenMutex.withLock {
            cachedToken?.takeIf(::isFresh) ?: exchangeToken()
        }
    }

    suspend fun refreshModels(): Result<List<GitHubCopilotModel>> {
        return try {
            val models = authClient.fetchModels(getValidToken())
            preferences.saveModels(models)
            Result.success(models)
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to fetch GitHub Copilot models", error)
            Result.failure(error)
        }
    }

    fun findModel(modelId: String): GitHubCopilotModel? =
        preferences.models.value.firstOrNull { it.id == modelId }

    suspend fun fetchUserInfo(): Result<GitHubCopilotUserInfo> {
        return try {
            val state = requireState()
            Result.success(authClient.fetchUserInfo(state.domain, state.oauthToken))
        } catch (error: Exception) {
            AppLogger.e(TAG, "Failed to fetch GitHub Copilot user info", error)
            Result.failure(error)
        }
    }

    suspend fun logout() {
        tokenMutex.withLock { cachedToken = null }
        preferences.clear()
    }

    private suspend fun exchangeToken(): GitHubCopilotToken {
        val state = requireState()
        val token = try {
            authClient.exchangeCopilotToken(state.domain, state.oauthToken)
        } catch (error: GitHubCopilotHttpException) {
            if (error.statusCode == 401) {
                // GitHub 授权已在网页端被撤销，保留旧凭据只会让每次请求都失败。
                AppLogger.e(TAG, "GitHub OAuth token was revoked; clearing Copilot login", error)
                cachedToken = null
                preferences.clear()
            }
            throw error
        }
        if (token.apiBase != state.apiBase) {
            preferences.save(state.copy(apiBase = token.apiBase))
        }
        cachedToken = token
        return token
    }

    private fun isFresh(token: GitHubCopilotToken): Boolean =
        token.expiresAtMillis - System.currentTimeMillis() > GitHubCopilotProtocol.TOKEN_REFRESH_WINDOW_MILLIS

    companion object {
        private const val TAG = "GitHubCopilotAuthManager"

        @Volatile
        private var instance: GitHubCopilotAuthManager? = null

        fun getInstance(context: Context): GitHubCopilotAuthManager {
            return instance ?: synchronized(this) {
                instance ?: GitHubCopilotAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
