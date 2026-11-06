package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ai.assistance.operit.data.api.GitHubCopilotModel
import com.ai.assistance.operit.util.AppLogger
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** GitHub Copilot 登录状态，所有 Copilot 模型配置共享。 */
data class GitHubCopilotAuthState(
    val domain: String,
    val oauthToken: String,
    val login: String,
    val apiBase: String,
)

class GitHubCopilotAuthPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private var preferences = createPreferences(appContext)

    private val _authState = MutableStateFlow(readState())
    val authState: StateFlow<GitHubCopilotAuthState?> = _authState.asStateFlow()

    private val _models = MutableStateFlow(readModels())
    val models: StateFlow<List<GitHubCopilotModel>> = _models.asStateFlow()

    fun currentState(): GitHubCopilotAuthState? = _authState.value

    fun save(state: GitHubCopilotAuthState) {
        require(state.domain.isNotBlank()) { "GitHub domain is empty" }
        require(state.oauthToken.isNotBlank()) { "GitHub OAuth token is empty" }
        require(state.apiBase.isNotBlank()) { "Copilot API base is empty" }
        preferences.edit()
            .putString(KEY_DOMAIN, state.domain)
            .putString(KEY_OAUTH_TOKEN, state.oauthToken)
            .putString(KEY_LOGIN, state.login)
            .putString(KEY_API_BASE, state.apiBase)
            .apply()
        _authState.value = state
    }

    fun saveModels(models: List<GitHubCopilotModel>) {
        val json = JSONArray()
        models.forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY_MODELS, json.toString()).apply()
        _models.value = models
    }

    fun clear() {
        preferences.edit().clear().apply()
        _authState.value = null
        _models.value = emptyList()
    }

    private fun readState(): GitHubCopilotAuthState? {
        return try {
            val domain = preferences.getString(KEY_DOMAIN, null)
            val token = preferences.getString(KEY_OAUTH_TOKEN, null)
            val apiBase = preferences.getString(KEY_API_BASE, null)
            if (domain.isNullOrBlank() || token.isNullOrBlank() || apiBase.isNullOrBlank()) {
                null
            } else {
                GitHubCopilotAuthState(
                    domain = domain,
                    oauthToken = token,
                    login = preferences.getString(KEY_LOGIN, null).orEmpty(),
                    apiBase = apiBase,
                )
            }
        } catch (error: SecurityException) {
            // 设备迁移后 Keystore 密钥不随备份恢复，密文无法解密；只重置 Copilot 凭据，
            // 否则每次打开模型设置页都会抛异常。
            AppLogger.e(TAG, "GitHub Copilot credentials are unreadable; resetting store", error)
            resetStore()
            null
        }
    }

    private fun readModels(): List<GitHubCopilotModel> {
        return try {
            val raw = preferences.getString(KEY_MODELS, null) ?: return emptyList()
            val array = JSONArray(raw)
            (0 until array.length()).map { GitHubCopilotModel.fromStoredJson(array.getJSONObject(it)) }
        } catch (error: SecurityException) {
            AppLogger.e(TAG, "GitHub Copilot model catalog is unreadable; resetting store", error)
            resetStore()
            emptyList()
        }
    }

    private fun resetStore() {
        appContext.deleteSharedPreferences(STORE_NAME)
        preferences = createEncryptedPreferences(appContext)
    }

    companion object {
        private const val TAG = "GitHubCopilotAuthPrefs"
        private const val STORE_NAME = "github_copilot_credentials"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_OAUTH_TOKEN = "oauth_token"
        private const val KEY_LOGIN = "login"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_MODELS = "models"

        @Volatile
        private var instance: GitHubCopilotAuthPreferences? = null

        fun getInstance(context: Context): GitHubCopilotAuthPreferences {
            return instance ?: synchronized(this) {
                instance ?: GitHubCopilotAuthPreferences(context.applicationContext).also { instance = it }
            }
        }

        private fun createPreferences(context: Context): SharedPreferences {
            return try {
                createEncryptedPreferences(context)
            } catch (error: GeneralSecurityException) {
                recreateUnreadableStore(context, error)
            } catch (error: IOException) {
                recreateUnreadableStore(context, error)
            }
        }

        private fun recreateUnreadableStore(context: Context, error: Exception): SharedPreferences {
            // Android 备份恢复了 SharedPreferences XML 但没有恢复 Keystore 条目，Tink 会拒绝旧密钥集。
            AppLogger.e(TAG, "GitHub Copilot encrypted store cannot be opened; resetting it", error)
            context.deleteSharedPreferences(STORE_NAME)
            return createEncryptedPreferences(context)
        }

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            return EncryptedSharedPreferences.create(
                context,
                STORE_NAME,
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
