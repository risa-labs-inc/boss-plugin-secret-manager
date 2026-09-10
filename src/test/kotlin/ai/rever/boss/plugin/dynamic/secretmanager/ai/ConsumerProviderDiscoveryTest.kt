package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumerProviderDiscoveryTest {
    @Test fun `OpenRouter connection and cached model metadata need no default or settings visit`() = runBlocking {
        val root = Files.createTempDirectory("consumer-provider").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val env = EnvResolver(root, processEnv = { if (it == "OPENROUTER_API_KEY") "test-only-key" else null },
                systemProperty = { null }, useLaunchctl = false)
            // Seed every registry entry so no configured local provider starts a network fetch.
            val entries = ProviderRegistry.all.joinToString(",") {
                """"${it.id}":{"models":[{"id":"consumer/model","displayName":"Consumer model","contextLength":131072}],"fetchedAtEpochMs":${System.currentTimeMillis()}}"""
            }
            File(root, "ai-model-catalog.json").writeText("""{"providers":{$entries},"version":1}""")
            val vm = AiProvidersViewModel(store = null, catalog = ModelCatalog(cacheDir = root),
                prefs = ActiveProviderPrefs(bossRootDir = root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env)
            val api = LlmProviderSettingsApiImpl(vm)
            api.configuredProviders()
            withTimeout(5000) { vm.connectionsLoaded.first { it } }
            val router = api.configuredProviders().single { it.providerId == ProviderRegistry.OPENROUTER }
            assertEquals("", router.modelId)
            assertNull(api.activeConfig(), "legacy active config still requires a model")
            api.availableModels()
            withTimeout(5000) { vm.state.first { it.catalogOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded } }
            val models = api.availableModels().single { it.providerId == ProviderRegistry.OPENROUTER }.models
            assertTrue(models.any { it.id == "consumer/model" && it.contextLength == 131072 })
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
