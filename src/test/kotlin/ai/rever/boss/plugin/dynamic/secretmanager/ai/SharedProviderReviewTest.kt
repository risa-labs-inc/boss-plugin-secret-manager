package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedProviderReviewTest {
    private val definition = SharedProviderDefinition(
        "boss-managed-provider-v1", "Shared AI", "managed", "https://api.example/v1", true,
    )
    private val descriptor = definition.descriptor("entry")
    private val connection = ProviderConnection(descriptor.id, "minted", CredentialSource.BROKERED)
    private val broker = object : BrokeredKeySource {
        override val supportsSharedProviders = true
        override fun permitsEndpoint(brokerId: String, endpoint: String) =
            brokerId == "managed" && SharedProviderDefinition.withinScope(endpoint, definition.baseUrl)
        override suspend fun fetch(brokerId: String) = if (brokerId == "managed") {
            Result.success(BrokeredKey("minted", 600))
        } else Result.failure(IllegalArgumentException("Unknown broker"))
    }

    private fun entry(id: String = "entry") = SecretEntryWithSharingData(
        id = id, website = "Shared AI", username = "", password = "", createdAt = "", updatedAt = "",
        tags = listOf(SharedProviderDefinition.TAG), isOwner = false, accessLevel = "read",
        notes = """{"schema":"boss-managed-provider-v1","name":"Shared AI","brokerId":"managed",
            "baseUrl":"https://api.example/v1","defaultForNewUsers":true}""",
    )

    private class Vault : SecretDataProvider by FakeSecretDataProvider(emptyList()) {
        var entries = emptyList<SecretEntryWithSharingData>()
        var reads = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun getUserSecretsWithSharingInfo(limit: Int, offset: Int)
            : Result<PaginatedSecretsWithSharingData> {
            reads++
            val page = entries.drop(offset).take(limit)
            entered.complete(Unit)
            gate?.await()
            if (fail) return Result.failure(IllegalStateException("Unavailable"))
            return Result.success(PaginatedSecretsWithSharingData(page, offset + page.size < entries.size))
        }
    }

    private fun env(root: File) = EnvResolver(
        root, processEnv = { null }, systemProperty = { null }, useLaunchctl = false,
    )

    @Test fun `both machine lists require a configured shared connection and loaded catalog`() {
        val loaded = CatalogState.Loaded(listOf(AiModel("model", "Model")), 0)
        assertTrue(isProviderListed(descriptor, connection, loaded))
        assertFalse(isProviderListed(descriptor, connection.copy(apiKey = ""), loaded))
        assertFalse(isProviderListed(descriptor, connection, CatalogState.NotConfigured))
        assertFalse(isProviderListed(descriptor, connection, CatalogState.Loading))
    }

    @Test fun `stale ids cannot wedge startup and shared defaults do not preempt configured keys`() {
        val openai = ProviderRegistry.find(ProviderRegistry.OPENAI)!!
        val own = ProviderConnection(openai.id, "own-key", CredentialSource.STORED, selectedModelId = "own")
        val descriptors = ProviderRegistry.all + descriptor
        val connections = mapOf(descriptor.id to connection, openai.id to own)
        assertEquals(openai.id, initialProviderId("shared:revoked", descriptors, connections))
        assertEquals(openai.id, initialProviderId(null, descriptors, connections))
        assertEquals(descriptor.id, initialProviderId(descriptor.id, descriptors, connections))
        assertEquals(descriptor.id, initialProviderId(null, descriptors, mapOf(descriptor.id to connection)))
    }

    @Test fun `shared discovery caches success and empty results until expiry or invalidation`() = runBlocking {
        val root = Files.createTempDirectory("shared-cache").toFile()
        var now = 0L
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root), monotonicNanos = { now })
            .also { it.brokeredKeys = broker }
        try {
            assertNotNull(store.loadAll().connections[descriptor.id])
            repeat(4) { store.loadAll() }
            assertEquals(1, vault.reads)
            vault.entries = emptyList()
            now = 5 * 60 * 1_000_000_000L
            assertNull(store.loadAll().connections[descriptor.id])
            store.loadAll()
            assertEquals(2, vault.reads)
            vault.entries = listOf(entry())
            store.invalidate()
            assertNotNull(store.loadAll().connections[descriptor.id])
            assertEquals(3, vault.reads)
        } finally { root.deleteRecursively() }
    }

    @Test fun `hosts without shared broker support never scan the vault`() = runBlocking {
        val root = Files.createTempDirectory("shared-unsupported").toFile()
        val vault = Vault()
        val store = ProviderCredentialStore(vault, env(root))
        try {
            store.loadAll()
            store.brokeredKeys = BrokeredKeySource { Result.success(BrokeredKey("token", 600)) }
            store.loadAll()
            assertEquals(0, vault.reads)
        } finally { root.deleteRecursively() }
    }

    @Test fun `scan cap retains discovered providers and read errors do not accuse credential storage`() = runBlocking {
        val root = Files.createTempDirectory("shared-cap").toFile()
        val vault = Vault().also { it.entries = (0..2000).map { index -> entry("$index") } }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val capped = store.loadAll()
            assertEquals(2000, capped.descriptors.count { SharedProviderDefinition.isShared(it.id) })
            assertEquals(20, vault.reads)
            assertNotNull(capped.sharedDiscoveryWarning)
            assertFalse(capped.storeReadFailed)
            vault.fail = true
            store.invalidate()
            val failed = store.loadAll()
            assertNotNull(failed.sharedDiscoveryWarning)
            assertFalse(failed.storeReadFailed)
            store.loadAll()
            assertEquals(21, vault.reads, "A failed scan is also backed off")
        } finally { root.deleteRecursively() }
    }

    @Test fun `an invalidated discovery cannot reseat the old share cache`() = runBlocking {
        val root = Files.createTempDirectory("shared-race").toFile()
        val gate = CompletableDeferred<Unit>()
        val vault = Vault().also { it.entries = listOf(entry()); it.gate = gate }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        try {
            val old = async { store.loadAll() }
            vault.entered.await()
            store.invalidate()
            vault.entries = emptyList()
            gate.complete(Unit)
            assertNull(old.await().connections[descriptor.id])
            assertNull(store.loadAll().connections[descriptor.id])
            assertEquals(2, vault.reads)
        } finally { gate.complete(Unit); root.deleteRecursively() }
    }

    @Test fun `shared catalog is never written to disk`() = runBlocking {
        val root = Files.createTempDirectory("shared-no-write").toFile()
        try {
            val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
                200 to """{"data":[{"id":"model"}]}""",
            ))), root)
            catalog.refresh(descriptor, "minted", nowEpochMs = 1000)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded)
            assertFalse(File(root, "ai-model-catalog.json").exists())
            assertFalse(catalog.isStale(descriptor.id, 61_000))
            assertTrue(catalog.isStale(descriptor.id, 61_001))
        } finally { root.deleteRecursively() }
    }

    @Test fun `shared catalog is never seeded from disk`() = runBlocking {
        val root = Files.createTempDirectory("shared-no-seed").toFile()
        try {
            File(root, "ai-model-catalog.json").writeText("""{"version":1,"providers":{
                "${descriptor.id}":{"models":[{"id":"private","displayName":"Private"}],"fetchedAtEpochMs":1000}}}""")
            val catalog = ModelCatalog(cacheDir = root)
            catalog.seedFromCache()
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        } finally { root.deleteRecursively() }
    }

    @Test fun `removing an active share clears its catalog and stops the active request path`() = runBlocking {
        val root = Files.createTempDirectory("shared-removal").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vault = Vault().also { it.entries = listOf(entry()) }
        val store = ProviderCredentialStore(vault, env(root)).also { it.brokeredKeys = broker }
        val catalog = ModelCatalog(ModelCatalogClient(QueuedHttpClient(listOf(
            200 to """{"data":[{"id":"model","is_default":true}]}""",
        ))))
        try {
            val vm = AiProvidersViewModel(
                store = store, catalog = catalog, prefs = ActiveProviderPrefs(root), legacyImport = null,
                splitViewOperations = null, scope = scope, envResolver = env(root),
                ollamaSystemCheck = noOllamaOnThisMachine(),
            )
            val api = LlmProviderSettingsApiImpl(vm)
            api.activeConfig()
            withTimeout(10_000) { vm.catalogsLoaded.first { it } }
            assertNotNull(api.activeConfig())
            vault.entries = emptyList()
            vm.refreshConnections()
            withTimeout(10_000) { vm.state.first { state -> state.providers.none { it.id == descriptor.id } } }
            assertNull(vm.state.value.activeProviderId)
            assertNull(api.activeConfig())
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
            assertTrue(api.configuredProviders().none { it.providerId == descriptor.id })
            assertTrue(api.availableModels().none { it.providerId == descriptor.id })
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
