package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.data.api.GitHubCopilotModel
import com.ai.assistance.operit.data.model.ApiProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotProviderTest {
    private fun model(vararg endpoints: String) = GitHubCopilotModel(
        id = "m",
        name = "M",
        vendor = "",
        supportedEndpoints = endpoints.toList(),
        supportsToolCalls = true,
        supportsVision = false,
        maxContextTokens = 0,
        isChatDefault = false,
    )

    @Test
    fun routingPrefersChatCompletionsAndUsesResponsesOnlyWhenRequired() {
        assertEquals(ApiProviderType.OPENAI_GENERIC, GitHubCopilotRouting.protocolFor(model()))
        assertEquals(
            ApiProviderType.OPENAI_GENERIC,
            GitHubCopilotRouting.protocolFor(model("/chat/completions", "/responses")),
        )
        assertEquals(
            ApiProviderType.OPENAI_RESPONSES_GENERIC,
            GitHubCopilotRouting.protocolFor(model("/responses")),
        )
        assertEquals(
            "https://api.business.githubcopilot.com/responses",
            GitHubCopilotRouting.endpointFor(
                "https://api.business.githubcopilot.com",
                ApiProviderType.OPENAI_RESPONSES_GENERIC,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun routingRejectsModelsWithoutSupportedProtocol() {
        GitHubCopilotRouting.protocolFor(model("/v1/messages"))
    }

    @Test
    fun initiatorFollowsOnlyTheLastMessage() {
        val userTurn = JSONObject(
            """{"messages":[{"role":"assistant","content":"a"},{"role":"user","content":"q"}]}"""
        )
        val toolTurn = JSONObject(
            """{"messages":[{"role":"user","content":"q"},{"role":"tool","content":"r"}]}"""
        )
        val responsesToolTurn = JSONObject(
            """{"input":[{"role":"user","content":"q"},{"type":"function_call_output","output":"r"}]}"""
        )
        assertEquals("user", GitHubCopilotRequestClassifier.initiator(userTurn))
        assertEquals("agent", GitHubCopilotRequestClassifier.initiator(toolTurn))
        assertEquals("agent", GitHubCopilotRequestClassifier.initiator(responsesToolTurn))
    }

    @Test
    fun imageDetectionCoversChatAndResponsesParts() {
        val chat = JSONObject(
            """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}"""
        )
        val responses = JSONObject("""{"input":[{"role":"user","content":[{"type":"input_image"}]}]}""")
        val text = JSONObject("""{"messages":[{"role":"user","content":"image_url"}]}""")
        assertTrue(GitHubCopilotRequestClassifier.containsImage(chat))
        assertTrue(GitHubCopilotRequestClassifier.containsImage(responses))
        assertFalse(GitHubCopilotRequestClassifier.containsImage(text))
    }
}
