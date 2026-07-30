package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two things the no-migration story rests on: that [ProviderSettings] survives a
 * JSON round-trip through a secret's notes field, and that garbage in that field degrades
 * to defaults instead of losing the credential it sits next to.
 *
 * Settings ride in `notes` precisely to avoid a database migration, so the format's
 * tolerance is the whole guarantee — and a user can edit that field by hand in the secret
 * list.
 */
class ProviderSettingsSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `round-trips every field`() {
        val original =
            ProviderSettings(
                selectedModelId = "claude-opus-5",
                customEndpoint = "http://localhost:11434/v1/chat/completions",
                temperature = 0.25f,
                maxTokens = 8192,
            )

        val decoded =
            json.decodeFromString(
                ProviderSettings.serializer(),
                json.encodeToString(ProviderSettings.serializer(), original),
            )

        assertEquals(original, decoded)
    }

    @Test
    fun `an entry written by an older build still reads`() {
        // Every field is defaulted so a note saved before a field existed still decodes;
        // without that, adding a field would orphan existing provider entries.
        val decoded = json.decodeFromString(ProviderSettings.serializer(), """{"selectedModelId":"gpt-5"}""")

        assertEquals("gpt-5", decoded.selectedModelId)
        assertEquals(ProviderConnection.DEFAULT_TEMPERATURE, decoded.temperature)
        assertEquals(ProviderConnection.DEFAULT_MAX_TOKENS, decoded.maxTokens)
    }

    @Test
    fun `unknown fields are ignored rather than failing`() {
        val decoded =
            json.decodeFromString(
                ProviderSettings.serializer(),
                """{"selectedModelId":"gpt-5","fieldFromTheFuture":true}""",
            )
        assertEquals("gpt-5", decoded.selectedModelId)
    }
}

/**
 * Pins the TTL and cache-seeding rules.
 *
 * `nowEpochMs` is injectable, so staleness is testable without waiting six hours — and the
 * freshness label in the panel is only meaningful if these hold.
 */
class ModelCatalogStateTest {
    private fun catalog(): ModelCatalog =
        ModelCatalog(cacheDir = Files.createTempDirectory("catalog").toFile())

    private val descriptor = ProviderRegistry.find(ProviderRegistry.OPENAI)!!

    @Test
    fun `an unknown provider is stale`() {
        assertTrue(catalog().isStale(ProviderRegistry.OPENAI, nowEpochMs = 0))
    }

    /**
     * Seeds a Loaded state from a cache file with a known fetch time.
     *
     * Going through the cache is the only way to establish a Loaded state without a
     * network call, and it exercises the seeding path at the same time.
     */
    private fun catalogSeededAt(fetchedAtEpochMs: Long): Pair<ModelCatalog, File> {
        val dir = Files.createTempDirectory("catalog-seed").toFile()
        File(dir, "ai-model-catalog.json").writeText(
            """
            {"providers":{"${descriptor.id}":{"models":[{"id":"gpt-5","displayName":"GPT-5"}],
            "fetchedAtEpochMs":$fetchedAtEpochMs}},"version":1}
            """.trimIndent(),
        )
        return ModelCatalog(cacheDir = dir) to dir
    }

    @Test
    fun `a result within the TTL is fresh and one past it is stale`() =
        runTest {
            val fetchedAt = 1_000_000L
            val (catalog, _) = catalogSeededAt(fetchedAt)
            catalog.seedFromCache()

            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded)
            assertFalse(catalog.isStale(descriptor.id, fetchedAt))
            assertFalse(catalog.isStale(descriptor.id, fetchedAt + ModelCatalog.CACHE_TTL_MS))
            assertTrue(catalog.isStale(descriptor.id, fetchedAt + ModelCatalog.CACHE_TTL_MS + 1))
        }

    @Test
    fun `a seeded list is marked as coming from the cache`() =
        runTest {
            // The panel distinguishes "live" from "cached" in its freshness line, so a
            // seeded entry must not claim to be a fresh fetch.
            val (catalog, _) = catalogSeededAt(1_000_000L)
            catalog.seedFromCache()

            val loaded = catalog.stateOf(descriptor.id) as CatalogState.Loaded
            assertTrue(loaded.fromCache)
            assertEquals(listOf("gpt-5"), loaded.models.map { it.id })
        }

    @Test
    fun `a cache written by a different format version is discarded`() =
        runTest {
            val dir = Files.createTempDirectory("catalog-version").toFile()
            File(dir, "ai-model-catalog.json").writeText(
                """{"providers":{"${descriptor.id}":{"models":[{"id":"x","displayName":"X"}],
                "fetchedAtEpochMs":1}},"version":999}""".trimIndent(),
            )
            val catalog = ModelCatalog(cacheDir = dir)
            catalog.seedFromCache()

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `a provider with no credential is reported not-configured`() {
        val catalog = catalog()
        catalog.markNotConfigured(descriptor.id)

        assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        // Not-configured counts as stale so adding a key triggers a fetch immediately.
        assertTrue(catalog.isStale(descriptor.id, nowEpochMs = 0))
    }

    @Test
    fun `refresh with a blank key marks not-configured instead of calling out`() =
        runTest {
            val catalog = catalog()
            catalog.refresh(descriptor, apiKey = "  ", force = true)

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `seeding an empty cache leaves state untouched`() =
        runTest {
            val catalog = catalog()
            catalog.seedFromCache()

            assertEquals(CatalogState.NotConfigured, catalog.stateOf(descriptor.id))
        }

    @Test
    fun `a rejected cache version is not laundered back in by the write path`() =
        runTest {
            // The read path discarded a wrong-version file correctly, but the *write* path
            // read that same file without checking, merged the new provider into it, and
            // wrote the result stamped with the current version — so everything just
            // rejected came back on the next open.
            // The stale entry must belong to a *different* provider than the one refreshed:
            // refreshing the same provider replaces its entry on merge, so the laundering
            // would be masked. Here Anthropic's rejected entry is the bystander that used to
            // be rescued by an unrelated OpenAI fetch.
            val bystander = ProviderRegistry.find(ProviderRegistry.ANTHROPIC)!!
            val dir = Files.createTempDirectory("catalog-launder").toFile()
            val cache = File(dir, "ai-model-catalog.json")
            cache.writeText(
                """{"providers":{"${bystander.id}":{"models":[{"id":"stale","displayName":"Stale"}],
                "fetchedAtEpochMs":1}},"version":999}""".trimIndent(),
            )

            // A genuinely successful fetch is required: writeCacheEntry is the read-modify-
            // write that used to launder the rejected file, and only a real result reaches it.
            val fake = QueuedHttpClient(listOf(200 to """{"data":[{"id":"fresh","object":"model"}]}"""))
            val catalog = ModelCatalog(client = ModelCatalogClient(fake), cacheDir = dir)

            catalog.seedFromCache()
            assertEquals(CatalogState.NotConfigured, catalog.stateOf(bystander.id))

            catalog.refresh(descriptor, apiKey = "a-key", force = true)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Loaded, "the fetch did not land")

            val rewritten = cache.readText()
            assertFalse(
                rewritten.contains("stale"),
                "a rejected-version entry was laundered into the current-version cache",
            )
            assertTrue(rewritten.contains("fresh"), "the new result was not cached")
        }

    @Test
    fun `a failed fetch is not papered over by seeding a cached list`() =
        runTest {
            // load() calls seedFromCache on every entry into the section. A Failed state used
            // to be overwritten by a within-TTL cached list, isStale then reported "fresh" so
            // no refetch happened, and the panel showed a working model picker for a
            // credential that does not authenticate — the 401 message simply gone.
            val dir = Files.createTempDirectory("catalog-failed").toFile()
            File(dir, "ai-model-catalog.json").writeText(
                """{"providers":{"${descriptor.id}":{"models":[{"id":"cached","displayName":"Cached"}],
                "fetchedAtEpochMs":1000000}},"version":1}""".trimIndent(),
            )

            val fake = QueuedHttpClient(listOf(401 to """{"error":"bad key"}"""))
            val catalog = ModelCatalog(client = ModelCatalogClient(fake), cacheDir = dir)

            catalog.refresh(descriptor, apiKey = "a-rejected-key", force = true)
            assertTrue(catalog.stateOf(descriptor.id) is CatalogState.Failed, "the 401 did not land")

            // Re-entering the section must not erase the failure.
            catalog.seedFromCache()

            val state = catalog.stateOf(descriptor.id)
            assertTrue(
                state is CatalogState.Failed,
                "seeding replaced a rejected-key failure with a cached list: $state",
            )
            // ...and it stays stale, so the next sweep actually retries.
            assertTrue(catalog.isStale(descriptor.id, nowEpochMs = 1_000_000))
        }

    @Test
    fun `the TTL boundary is six hours`() {
        // Documented as six hours and shown to the user as an age; pin it so a change is
        // deliberate.
        assertEquals(6 * 60 * 60 * 1000L, ModelCatalog.CACHE_TTL_MS)
    }
}

/**
 * Pins the provider registry's invariants.
 *
 * Ids are persisted in secrets and handed out as `LlmConfig.providerId`, so a rename is a
 * silent data break — and the default is derived from list order rather than named, which
 * only works if the list is non-empty and unique.
 */
class ProviderRegistryTest {
    @Test
    fun `ids are unique`() {
        val ids = ProviderRegistry.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate provider ids: $ids")
    }

    @Test
    fun `the default is the first entry`() {
        assertEquals(ProviderRegistry.all.first().id, ProviderRegistry.default.id)
    }

    @Test
    fun `an unknown id falls back to the default rather than throwing`() {
        assertEquals(ProviderRegistry.default.id, ProviderRegistry.findOrDefault("NOT_A_PROVIDER").id)
        assertEquals(ProviderRegistry.default.id, ProviderRegistry.findOrDefault(null).id)
    }

    @Test
    fun `every provider names a standard key that looks like an env var`() {
        ProviderRegistry.all.forEach { descriptor ->
            val name = descriptor.standardKeyName
            assertTrue(
                name.matches(Regex("[A-Z0-9_]+")),
                "${descriptor.id} standard key name is not env-var shaped: $name",
            )
        }
    }

    @Test
    fun `only the custom provider lacks a models endpoint`() {
        // The whole feature rests on live lists, so a provider without an endpoint has to
        // be the hand-configured one — anything else would silently have no models.
        val withoutEndpoint = ProviderRegistry.all.filter { it.modelsEndpoint == null }.map { it.id }
        assertEquals(listOf(ProviderRegistry.CUSTOM), withoutEndpoint)
    }

    @Test
    fun `google puts the model in the path and others do not`() {
        val google = ProviderRegistry.find(ProviderRegistry.GOOGLE)!!
        assertTrue(google.chatEndpointFor("gemini-3-pro").endsWith("/models/gemini-3-pro:generateContent"))

        val openai = ProviderRegistry.find(ProviderRegistry.OPENAI)!!
        assertFalse(openai.chatEndpointFor("gpt-5").contains("gpt-5"))
    }
}
