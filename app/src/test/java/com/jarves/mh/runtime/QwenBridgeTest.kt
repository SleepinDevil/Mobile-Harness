package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route mapping and provider-visibility rules for the Qwen Code bridge. */
class QwenBridgeTest {

    @Test
    fun anthropicProviderUsesAnthropicAuthBucket() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.ANTHROPIC, baseUrl = "https://api.anthropic.com/"),
            gatewayUrl = null,
        )
        assertEquals("anthropic", route.authType)
        assertEquals("ANTHROPIC_API_KEY", route.envKey)
        assertEquals("https://api.anthropic.com", route.baseUrl)
        assertNull(route.wireApi)
    }

    @Test
    fun gatewayStyleProvidersUseAnthropicAuth() {
        for (kind in listOf(ProviderKind.DEEPSEEK, ProviderKind.KIMI)) {
            val route = QwenRouteMapper.forProfile(ProviderProfile(kind), gatewayUrl = null)
            assertEquals(kind.name, "anthropic", route.authType)
            assertEquals(kind.name, "ANTHROPIC_API_KEY", route.envKey)
        }
    }

    @Test
    fun llmRouterPrefersRoutingGatewayUrl() {
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        val routed = QwenRouteMapper.forProfile(profile, gatewayUrl = "http://127.0.0.1:9090")
        assertEquals("openai", routed.authType)
        assertEquals("OPENAI_API_KEY", routed.envKey)
        assertEquals("http://127.0.0.1:9090/v1", routed.baseUrl)

        val direct = QwenRouteMapper.forProfile(profile, gatewayUrl = null)
        assertEquals("https://openrouter.ai/api/v1", direct.baseUrl)
    }

    @Test
    fun openCodeZenUsesResponsesWireApi() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN), gatewayUrl = null)
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
        assertEquals("https://opencode.ai/zen/v1", route.baseUrl)
    }

    @Test
    fun nvidiaNimUsesOpenAiChatWithoutWireApi() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM), gatewayUrl = null)
        assertEquals("openai", route.authType)
        assertNull(route.wireApi)
        assertEquals("https://integrate.api.nvidia.com/v1", route.baseUrl)
    }

    @Test
    fun customAnthropicMessagesRouteKeepsBaseUrlRoot() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/anthropic/", dshApi = "anthropic-messages"),
            gatewayUrl = null,
        )
        assertEquals("anthropic", route.authType)
        assertEquals("https://gw.example.com/anthropic", route.baseUrl)
    }

    @Test
    fun customOpenAiCompletionsRouteNormalizesToVersionRoot() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/api/v1/chat/completions", dshApi = "openai-completions"),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("https://gw.example.com/api/v1", route.baseUrl)
        assertNull(route.wireApi)
    }

    @Test
    fun customOpenAiResponsesRouteAddsVersionRootAndWireApi() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/responses", dshApi = "openai-responses"),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
        assertEquals("https://gw.example.com/v1", route.baseUrl)
    }

    @Test
    fun customRouteFallsBackToUrlInferenceWhenApiBlank() {
        val route = QwenRouteMapper.forProfile(
            ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1/responses", dshApi = ""),
            gatewayUrl = null,
        )
        assertEquals("openai", route.authType)
        assertEquals("responses", route.wireApi)
    }

    @Test(expected = IllegalStateException::class)
    fun claudeSubscriptionProfileIsRejected() {
        QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE), gatewayUrl = null)
    }

    @Test
    fun modelFallsBackToProviderDefault() {
        val route = QwenRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK, model = ""), gatewayUrl = null)
        assertEquals(ProviderKind.DEEPSEEK.defaultModel, route.model)
    }

    @Test
    fun qwenCodeSupportsEveryApiKeyProviderButNotClaudeLogin() {
        val providers = providersForAgent(AgentKind.QWEN_CODE)
        assertFalse(ProviderKind.CLAUDE in providers)
        assertTrue(ProviderKind.ANTHROPIC in providers)
        assertTrue(ProviderKind.LLM_ROUTER in providers)
        assertTrue(ProviderKind.DEEPSEEK in providers)
        assertTrue(ProviderKind.KIMI in providers)
        assertTrue(ProviderKind.OPENCODE_ZEN in providers)
        assertTrue(ProviderKind.NVIDIA_NIM in providers)
        assertTrue(ProviderKind.CUSTOM in providers)
    }

    @Test
    fun qwenValidationUsesSelectedCustomWire() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.QWEN_CODE))
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun qwenRuntimePathsAreExcludedFromChanges() {
        val tempDir = java.nio.file.Files.createTempDirectory("mh-qwen-ignore").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(tempDir)
            assertTrue(checkpoints.isInternalRuntimePath(".qwen/settings.json"))
            assertTrue(checkpoints.isInternalRuntimePath("app/.qwen/logs/session.jsonl"))
            assertFalse(checkpoints.isInternalRuntimePath("src/main/Main.kt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
