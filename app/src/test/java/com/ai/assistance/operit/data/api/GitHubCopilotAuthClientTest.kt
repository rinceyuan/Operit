package com.ai.assistance.operit.data.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotAuthClientTest {
    @Test
    fun domainNormalizationAcceptsGitHubAndGheOnly() {
        assertEquals("github.com", GitHubCopilotProtocol.normalizeDomain(" https://GitHub.com/ "))
        assertEquals("acme.ghe.com", GitHubCopilotProtocol.normalizeDomain("acme.ghe.com"))
        assertEquals(
            "https://api.acme.ghe.com/copilot_internal/v2/token",
            GitHubCopilotProtocol.copilotTokenUrl("acme.ghe.com"),
        )
        assertEquals(
            "https://api.github.com/copilot_internal/user",
            GitHubCopilotProtocol.copilotUserUrl("github.com"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun domainNormalizationRejectsOtherHosts() {
        GitHubCopilotProtocol.normalizeDomain("gitlab.com")
    }

    @Test
    fun modelCatalogKeepsEnabledPickerChatModelsWithDefaultFirst() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"embed","name":"Embed","model_picker_enabled":false,
               "capabilities":{"type":"embeddings"}},
              {"id":"gpt-4.1","name":"GPT-4.1","vendor":"Azure OpenAI","model_picker_enabled":true,
               "capabilities":{"type":"chat","supports":{"tool_calls":true,"vision":true},
                 "limits":{"max_context_window_tokens":128000}},
               "supported_endpoints":["/chat/completions","/responses"]},
              {"id":"disabled","name":"Disabled","model_picker_enabled":true,
               "policy":{"state":"unconfigured"},"capabilities":{"type":"chat"}},
              {"id":"claude","name":"Claude","vendor":"Anthropic","is_chat_default":true,
               "model_picker_enabled":true,"policy":{"state":"enabled"},
               "capabilities":{"type":"chat","supports":{"tool_calls":true}}}
            ]}
            """.trimIndent()
        )
        val models = GitHubCopilotAuthClient.parseModels(json)
        assertEquals(listOf("claude", "gpt-4.1"), models.map { it.id })
        val gpt = models[1]
        assertTrue(gpt.supportsVision)
        assertEquals(128000, gpt.maxContextTokens)
        assertEquals(listOf("/chat/completions", "/responses"), gpt.supportedEndpoints)
        assertFalse(models[0].supportsVision)
    }

    @Test
    fun userInfoReadsPlanAndPremiumQuota() {
        val info = GitHubCopilotAuthClient.parseUserInfo(
            JSONObject(
                """
                {"login":"octo","copilot_plan":"individual","chat_enabled":true,
                 "quota_reset_date":"2026-12-01",
                 "quota_snapshots":{"premium_interactions":
                   {"entitlement":300,"remaining":120,"percent_remaining":40.0,"unlimited":false}}}
                """.trimIndent()
            )
        )
        assertEquals("octo", info.login)
        assertEquals("individual", info.plan)
        assertTrue(info.chatEnabled)
        assertEquals(120L, info.premiumQuota?.remaining)
        assertEquals("2026-12-01", info.quotaResetDate)
    }
}
