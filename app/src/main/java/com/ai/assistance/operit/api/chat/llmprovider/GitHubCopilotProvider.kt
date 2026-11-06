package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.api.GitHubCopilotAuthManager
import com.ai.assistance.operit.data.api.GitHubCopilotModel
import com.ai.assistance.operit.data.api.GitHubCopilotProtocol
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.ChatUtils
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

private class GitHubCopilotTokenProvider(
    private val authManager: GitHubCopilotAuthManager,
) : ApiKeyProvider {
    override suspend fun getApiKey(): String = authManager.getValidToken().token

    override suspend fun getCandidateKeyCount(): Int =
        if (authManager.authState.value == null) 0 else 1
}

/** 按 Copilot 模型目录声明的 `supported_endpoints` 选择请求协议。 */
internal object GitHubCopilotRouting {
    fun protocolFor(model: GitHubCopilotModel): ApiProviderType {
        val endpoints = model.supportedEndpoints
        return when {
            // 未声明 supported_endpoints 的模型属于 Copilot 最初的 Chat Completions 协议。
            endpoints.isEmpty() -> ApiProviderType.OPENAI_GENERIC
            GitHubCopilotProtocol.ENDPOINT_CHAT_COMPLETIONS in endpoints -> ApiProviderType.OPENAI_GENERIC
            GitHubCopilotProtocol.ENDPOINT_RESPONSES in endpoints -> ApiProviderType.OPENAI_RESPONSES_GENERIC
            else -> throw IllegalArgumentException(
                "Unsupported GitHub Copilot model protocol: ${model.id} $endpoints"
            )
        }
    }

    fun endpointFor(apiBase: String, protocol: ApiProviderType): String = when (protocol) {
        ApiProviderType.OPENAI_RESPONSES_GENERIC -> apiBase + GitHubCopilotProtocol.ENDPOINT_RESPONSES
        else -> apiBase + GitHubCopilotProtocol.ENDPOINT_CHAT_COMPLETIONS
    }
}

/**
 * 为所有 Copilot 对话请求补充 Copilot 请求头。
 *
 * 认证值由 [GitHubCopilotTokenProvider] 写入 `Authorization`；这里额外根据请求体判定
 * `X-Initiator` 与 `Copilot-Vision-Request`。在拦截器中统一处理，Chat 与 Responses
 * 两种协议的父类无需修改。
 */
internal class GitHubCopilotRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = original.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: throw IllegalStateException("GitHub Copilot request has no Copilot token")
        val bodyJson = original.body?.let(::readJsonBody)
        val builder = GitHubCopilotProtocol.applyCopilotHeaders(original.newBuilder(), token)
            .header("X-Initiator", GitHubCopilotRequestClassifier.initiator(bodyJson))
        if (bodyJson != null && GitHubCopilotRequestClassifier.containsImage(bodyJson)) {
            builder.header("Copilot-Vision-Request", "true")
        }
        return chain.proceed(builder.build())
    }

    private fun readJsonBody(body: RequestBody): JSONObject? {
        val buffer = Buffer()
        body.writeTo(buffer)
        val text = buffer.readUtf8()
        return if (text.trimStart().startsWith("{")) JSONObject(text) else null
    }
}

internal object GitHubCopilotRequestClassifier {
    /**
     * 只根据最后一条消息判定发起方：最后一条是用户消息为 `user`，否则（工具结果续轮、
     * 助手预填等）为 `agent`。Copilot 依此区分用户轮与代理续轮的计费，
     * 把续轮误报为 `user` 会额外消耗高级请求额度。
     */
    fun initiator(body: JSONObject?): String {
        if (body == null) return "user"
        val messages = body.optJSONArray("messages")
        if (messages != null && messages.length() > 0) {
            val last = messages.optJSONObject(messages.length() - 1)
            return if (last?.optString("role") == "user") "user" else "agent"
        }
        val input = body.optJSONArray("input")
        if (input != null && input.length() > 0) {
            val last = input.optJSONObject(input.length() - 1)
            return if (last?.optString("role") == "user") "user" else "agent"
        }
        return "user"
    }

    fun containsImage(value: Any?): Boolean = when (value) {
        is JSONObject -> {
            val type = value.optString("type")
            if (type == "image_url" || type == "input_image") {
                true
            } else {
                value.keys().asSequence().any { key -> containsImage(value.opt(key)) }
            }
        }
        is JSONArray -> (0 until value.length()).any { containsImage(value.opt(it)) }
        else -> false
    }
}

internal class GitHubCopilotChatProvider(
    endpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String>,
    supportsVision: Boolean,
    enableToolCall: Boolean,
    thinkingConfigurations: String,
    thinkingOptionId: String,
) : OpenAIProvider(
    apiEndpoint = endpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = ApiProviderType.OPENAI_GENERIC,
    supportsVision = supportsVision,
    enableToolCall = enableToolCall,
    includeUsageInStream = true,
    thinkingConfigurations = thinkingConfigurations,
    thinkingOptionId = thinkingOptionId,
)

internal class GitHubCopilotResponsesProvider(
    endpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String>,
    supportsVision: Boolean,
    enableToolCall: Boolean,
    thinkingConfigurations: String,
    thinkingOptionId: String,
) : OpenAIProvider(
    apiEndpoint = endpoint,
    apiKeyProvider = apiKeyProvider,
    modelName = modelName,
    client = client,
    customHeaders = customHeaders,
    providerType = ApiProviderType.OPENAI_RESPONSES_GENERIC,
    supportsVision = supportsVision,
    enableToolCall = enableToolCall,
    thinkingConfigurations = thinkingConfigurations,
    thinkingOptionId = thinkingOptionId,
) {
    override val useResponsesApi: Boolean = true

    override fun createRequestBody(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
    ): RequestBody {
        // 未开启思考时去掉历史中的 Responses 推理元数据，否则加密推理块会被原样回传。
        val requestChatHistory =
            if (enableThinking) chatHistory
            else ChatUtils.stripOpenAiResponsesReasoningMetaTurns(chatHistory)
        return createJsonRequestBody(
            createRequestBodyInternal(
                context = context,
                chatHistory = requestChatHistory,
                modelParameters = modelParameters,
                stream = stream,
                availableTools = availableTools,
                preserveThinkInHistory = preserveThinkInHistory,
            )
        )
    }
}

/** GitHub Copilot 提供商：按模型协议委托给 Chat Completions 或 Responses 实现。 */
class GitHubCopilotProvider private constructor(
    private val delegate: AIService,
    private val authManager: GitHubCopilotAuthManager,
) : AIService by delegate {

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        GitHubCopilotModelListFetcher.getModelsList(authManager)

    companion object {
        fun create(
            config: ModelConfigData,
            context: Context,
            client: OkHttpClient,
            customHeaders: Map<String, String>,
            enableDirectImageProcessing: Boolean,
            enableToolCall: Boolean,
        ): AIService {
            val authManager = GitHubCopilotAuthManager.getInstance(context)
            val state = authManager.requireState()
            val modelId = config.modelName.trim()
            val model = authManager.findModel(modelId)
                ?: throw IllegalArgumentException(
                    "GitHub Copilot model is not in the model catalog: $modelId. " +
                        "Refresh the model list in model settings."
                )
            val protocol = GitHubCopilotRouting.protocolFor(model)
            val endpoint = GitHubCopilotRouting.endpointFor(state.apiBase, protocol)
            val copilotClient = client.newBuilder()
                .addInterceptor(GitHubCopilotRequestInterceptor())
                .build()
            val tokenProvider = GitHubCopilotTokenProvider(authManager)
            // 模型目录声明不支持的能力不向接口发送，避免 400。
            val supportsVision = enableDirectImageProcessing && model.supportsVision
            val toolCall = enableToolCall && model.supportsToolCalls
            val routed: AIService = when (protocol) {
                ApiProviderType.OPENAI_RESPONSES_GENERIC -> GitHubCopilotResponsesProvider(
                    endpoint, tokenProvider, model.id, copilotClient, customHeaders,
                    supportsVision, toolCall, config.thinkingConfigurations, config.thinkingOptionId,
                )
                else -> GitHubCopilotChatProvider(
                    endpoint, tokenProvider, model.id, copilotClient, customHeaders,
                    supportsVision, toolCall, config.thinkingConfigurations, config.thinkingOptionId,
                )
            }
            return GitHubCopilotProvider(routed, authManager)
        }
    }
}

object GitHubCopilotModelListFetcher {
    suspend fun getModelsList(authManager: GitHubCopilotAuthManager): Result<List<ModelOption>> =
        authManager.refreshModels().map { models ->
            models.map { model ->
                ModelOption(
                    id = model.id,
                    name = if (model.vendor.isBlank()) model.name else "${model.name} · ${model.vendor}",
                )
            }
        }
}
