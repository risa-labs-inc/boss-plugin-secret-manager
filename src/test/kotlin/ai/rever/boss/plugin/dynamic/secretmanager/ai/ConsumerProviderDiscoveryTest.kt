package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsumerProviderDiscoveryTest {
    private class Harness(
        keys: Map<String, String> = mapOf("OPENROUTER_API_KEY" to "test-only-key"),
        response: Pair<Int, String> = 200 to """{"data":[{"id":"consumer/model","name":"Consumer model","context_length":131072}]}""",
        probe: OllamaSystemCheck = noOllamaOnThisMachine(),
        val nowNanos: AtomicLong = AtomicLong(0),
    ) : AutoCloseable {
        val root = Files.createTempDirectory("consumer-provider").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val env = EnvResolver(root, processEnv = keys::get, systemProperty = { null }, useLaunchctl = false)
        val prefs = ActiveProviderPrefs(bossRootDir = root)
        val http = QueuedHttpClient(emptyList(), always = response)
        val catalog = ModelCatalog(ModelCatalogClient(http))
        val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env)
        val vm = AiProvidersViewModel(store, catalog, prefs, null, null, scope, env,
            ollamaSystemCheck = probe, monotonicNanos = nowNanos::get)
        val api = LlmProviderSettingsApiImpl(vm)

        suspend fun load() {
            api.availableModels()
            withTimeout(5000) { vm.catalogsLoaded.first { it } }
        }

        override fun close() {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test fun `OpenRouter model metadata needs no default or settings visit`() = runBlocking {
        Harness().use { h ->
            h.load()
            val router = h.api.configuredProviders().single { it.providerId == ProviderRegistry.OPENROUTER }
            assertEquals("", router.modelId)
            assertNull(h.api.activeConfig(), "apps without their own model selection need a default")
            val model = h.api.availableModels().single { it.providerId == ProviderRegistry.OPENROUTER }.models.single()
            assertEquals("consumer/model", model.id)
            assertEquals(131072, model.contextLength)
            assertEquals(1, h.http.requests.size)
            assertFalse(h.api.configuredProviders().any { it.providerId == ProviderRegistry.OLLAMA })
        }
    }

    @Test fun `consumer catalog waits for Ollama probe before deciding to fetch`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probe = OllamaSystemCheck(path = "", home = "", isWindows = false, isExecutable = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }, physicalMemoryBytes = { null }, browse = { false })
        Harness(keys = emptyMap(), probe = probe).use { h ->
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                h.api.availableModels()
                withTimeout(5000) { h.vm.connectionsLoaded.first { it } }
                assertNull(withTimeoutOrNull(100) { h.vm.catalogsLoaded.first { it } },
                    "catalog sweep must remain suspended while the probe is unresolved")
                assertFalse(h.vm.catalogsLoaded.value)
                assertTrue(h.http.requests.isEmpty())
            } finally {
                release.countDown()
            }
            withTimeout(5000) { h.vm.catalogsLoaded.first { it } }
            assertEquals(1, h.http.requests.size, "the installed daemon is discovered after the probe answers")
            assertEquals(ProviderRegistry.OLLAMA, h.api.configuredProviders().single().providerId)
        }
    }

    @Test fun `a provider without a fetched catalog is omitted rather than empty`() = runBlocking {
        Harness(response = 503 to "unavailable").use { h ->
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
            repeat(20) { h.api.availableModels() }
            assertEquals(1, h.http.requests.size, "polling must not retry a failure continuously")
        }
    }

    @Test fun `manual provider contributes its typed model without catalog fetch`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.prefs.writeModel(ProviderRegistry.CUSTOM, "private-model")
            h.prefs.writeCustomEndpoint(ProviderRegistry.CUSTOM, "http://localhost:9999/v1/chat/completions")
            h.load()
            assertEquals("private-model", h.api.availableModels().single().models.single().id)
            assertTrue(h.http.requests.isEmpty())
        }
    }

    @Test fun `Google without a selected model never exposes a malformed endpoint`() = runBlocking {
        Harness(keys = mapOf("GOOGLE_API_KEY" to "test-only-key")).use { h ->
            h.vm.ensureConnectionsLoaded()
            withTimeout(5000) { h.vm.connectionsLoaded.first { it } }
            assertFalse(h.api.configuredProviders().any { it.providerId == ProviderRegistry.GOOGLE })
        }
    }

    @Test fun `a key added after discovery loads models without opening settings`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
            h.store.saveKey(ProviderRegistry.OPENROUTER, "new-test-key").getOrThrow()
            withTimeout(5000) {
                h.vm.state.first { it.catalogOf(ProviderRegistry.OPENROUTER) is CatalogState.Loaded }
            }
            assertTrue(h.api.availableModels().any { it.providerId == ProviderRegistry.OPENROUTER })
        }
    }

    @Test fun `consumer reads recheck catalog TTL after the bounded refresh interval`() = runBlocking {
        Harness().use { h ->
            h.load()
            val descriptor = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!
            h.catalog.refresh(descriptor, "test-only-key", force = true,
                nowEpochMs = System.currentTimeMillis() - ModelCatalog.CACHE_TTL_MS - 1000)
            assertEquals(2, h.http.requests.size)
            h.nowNanos.set(31_000_000_000L)
            h.api.availableModels()
            withTimeout(5000) {
                h.catalog.states.first {
                    (it[descriptor.id] as? CatalogState.Loaded)?.fetchedAtEpochMs?.let { fetched ->
                        System.currentTimeMillis() - fetched < 5000
                    } == true
                }
            }
            assertEquals(3, h.http.requests.size)
        }
    }

    @Test fun `a fetched empty catalog is distinguishable from no fetched catalog`() = runBlocking {
        Harness(response = 200 to """{"data":[]}""").use { h ->
            h.load()
            assertEquals(ProviderRegistry.OPENROUTER, h.api.availableModels().single().providerId)
            assertTrue(h.api.availableModels().single().models.isEmpty())
        }
    }

    @Test fun `manual provider without a typed model is omitted`() = runBlocking {
        Harness(keys = emptyMap()).use { h ->
            h.prefs.writeCustomEndpoint(ProviderRegistry.CUSTOM, "http://localhost:9999/v1/chat/completions")
            h.load()
            assertTrue(h.api.availableModels().isEmpty())
        }
    }
}
