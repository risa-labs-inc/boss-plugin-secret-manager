package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the migration of plaintext keys out of the two files that predate this plugin: the
 * host's old `llm_settings.json` (nested `apiKeys` map) and `llmrpa`'s `llm-settings.json`
 * (one flat field per provider).
 *
 * The invariants worth pinning are all about *not losing a key*: an offer must appear while
 * keys are still only in a file, a file must not be retired until everything it held is
 * safely stored, and a key shadowed by an environment variable must not be copied into
 * storage (which would recreate the plaintext-persistence leak this feature removed).
 */
class LegacySettingsImportTest {
    private fun tempDir(name: String): File = Files.createTempDirectory(name).toFile()

    /**
     * A hermetic [EnvResolver] — the fixture file is the only source of variables.
     *
     * The file alone isn't enough: it is consulted after the process environment, system
     * properties and `launchctl`, and these tests use the registry's real variable names, so
     * a developer with `ANTHROPIC_API_KEY` exported would otherwise see the env-shadowing
     * path taken everywhere and the import assertions fail.
     */
    private fun envWith(contents: String = ""): EnvResolver {
        val dir = tempDir("legacy-import-env")
        File(dir, "env_vars").writeText(contents)
        return EnvResolver(
            bossRootDir = dir,
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )
    }

    private fun hostSource(file: File) =
        LegacySettingsImport.LegacySource(file) { text ->
            // Same parse the production host source uses, restated here so the fixture path
            // is explicit rather than depending on defaultSources' file layout.
            Regex("\"([A-Z_]+)\"\\s*:\\s*\"([^\"]*)\"")
                .findAll(text.substringAfter("\"apiKeys\""))
                .associate { it.groupValues[1] to it.groupValues[2] }
        }

    /** The real llmrpa parser, reached through [LegacySettingsImport.defaultSources]. */
    private fun llmRpaSource(file: File): LegacySettingsImport.LegacySource {
        val real = LegacySettingsImport.defaultSources(tempDir("unused"))[1]
        return LegacySettingsImport.LegacySource(file, real.parse)
    }

    private fun importer(
        store: ProviderCredentialStore,
        env: EnvResolver,
        sources: List<LegacySettingsImport.LegacySource>,
    ) = LegacySettingsImport(
        store = store,
        envResolver = env,
        bossRootDir = tempDir("legacy-import-root"),
        sources = sources,
    )

    // ==================== llmrpa's flat file ====================

    @Test
    fun `llmrpa's flat settings file is offered and imported`() =
        runTest {
            val dir = tempDir("llmrpa")
            val file = File(dir, "llm-settings.json")
            file.writeText(
                """
                {
                  "selectedProvider": "ANTHROPIC",
                  "selectedModelId": "claude-3-5-sonnet-20240620",
                  "anthropicApiKey": "sk-ant-legacy",
                  "openaiApiKey": "",
                  "togetherApiKey": "tog-legacy",
                  "customApiKey": "",
                  "customEndpoint": "https://example.test/v1/chat/completions",
                  "maxTokens": 4096,
                  "temperature": 0.7
                }
                """.trimIndent(),
            )
            val env = envWith()
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, env)
            val importer = importer(store, env, listOf(llmRpaSource(file)))

            val offer = importer.inspectAndRetireIfEmpty()
            assertNotNull(offer)
            // Only the two non-blank keys; the empty fields must not produce an offer entry.
            assertEquals(listOf("ANTHROPIC", "TOGETHER"), offer.providerIds)
            assertEquals(listOf(file.absolutePath), offer.sourcePaths)

            val imported = importer.import()
            assertEquals(listOf("ANTHROPIC", "TOGETHER"), imported.getOrNull()?.sorted())
            assertEquals(
                listOf("sk-ant-legacy", "tog-legacy"),
                provider.created.map { it.password }.sorted(),
            )
            // File retired, not deleted — the keys are still recoverable by hand.
            assertFalse(file.exists())
            assertTrue(File(dir, "llm-settings.json.migrated").exists())
        }

    @Test
    fun `the retired model id and custom endpoint are not imported`() =
        runTest {
            val dir = tempDir("llmrpa-model")
            val file = File(dir, "llm-settings.json")
            file.writeText(
                """
                {
                  "selectedModelId": "claude-3-5-sonnet-20240620",
                  "anthropicApiKey": "sk-ant-legacy",
                  "customApiKey": "custom-legacy",
                  "customEndpoint": "https://example.test/v1/chat/completions"
                }
                """.trimIndent(),
            )
            val env = envWith()
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, env)

            importer(store, env, listOf(llmRpaSource(file))).import()

            // Keys land, but nothing carries the dead model id or the endpoint: importing a
            // model would put a retired id back into a live picker, and writing the endpoint
            // would blank any selectedModelId the user has already chosen for CUSTOM.
            assertEquals(2, provider.created.size)
            val written = provider.created.joinToString("|") { "${it.notes}" }
            assertFalse(written.contains("claude-3-5-sonnet-20240620"), "model id was imported")
            assertFalse(written.contains("example.test"), "custom endpoint was imported")
        }

    // ==================== not losing keys ====================

    @Test
    fun `a key shadowed by an environment variable is neither imported nor retired`() =
        runTest {
            val dir = tempDir("llmrpa-env")
            val file = File(dir, "llm-settings.json")
            file.writeText("""{"anthropicApiKey":"sk-ant-legacy"}""")
            val anthropic = ProviderRegistry.find(ProviderRegistry.ANTHROPIC)!!
            val env = envWith("${anthropic.standardKeyName}=from-env")
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, env)
            val importer = importer(store, env, listOf(llmRpaSource(file)))

            assertNull(importer.inspectAndRetireIfEmpty(), "an env-supplied key needs no import")
            assertEquals(emptyList(), provider.created, "must not persist an env-supplied key")
            // Crucially the file survives: unsetting the variable later has to bring the
            // offer back, and renaming now would strand a real key behind a file the user
            // was never told about.
            assertTrue(file.exists())
        }

    @Test
    fun `a file whose writes all fail is kept so the import can be retried`() =
        runTest {
            val dir = tempDir("llmrpa-fail")
            val file = File(dir, "llm-settings.json")
            file.writeText("""{"anthropicApiKey":"sk-ant-legacy"}""")
            val env = envWith()
            val provider = FakeSecretDataProvider(emptyList(), failWrites = true)
            val store = ProviderCredentialStore(provider, env)

            val result = importer(store, env, listOf(llmRpaSource(file))).import()

            assertTrue(result.isFailure)
            assertTrue(file.exists(), "a failed import must not retire the file")
        }

    @Test
    fun `a file holding nothing for a known provider is retired so the offer stops`() =
        runTest {
            val dir = tempDir("llmrpa-empty")
            val file = File(dir, "llm-settings.json")
            file.writeText("""{"anthropicApiKey":"","openaiApiKey":"","selectedProvider":"ANTHROPIC"}""")
            val env = envWith()
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env)

            assertNull(importer(store, env, listOf(llmRpaSource(file))).inspectAndRetireIfEmpty())

            assertFalse(file.exists())
            assertTrue(File(dir, "llm-settings.json.migrated").exists())
        }

    // ==================== two sources at once ====================

    /**
     * The regression this generalisation exists for: before it, only the host file was read,
     * so migrating llmrpa off its own file would have stranded whatever the user typed there.
     * Each file must also be retired on its own — one unwritable provider in one file must not
     * leave the other file's keys behind.
     */
    @Test
    fun `both legacy files are offered together and retired independently`() =
        runTest {
            val hostDir = tempDir("host")
            val hostFile = File(hostDir, "llm_settings.json")
            hostFile.writeText("""{"apiKeys":{"OPENAI":"sk-openai-host"}}""")

            val rpaDir = tempDir("rpa")
            val rpaFile = File(rpaDir, "llm-settings.json")
            rpaFile.writeText("""{"anthropicApiKey":"sk-ant-rpa"}""")

            val env = envWith()
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, env)
            val importer =
                importer(store, env, listOf(hostSource(hostFile), llmRpaSource(rpaFile)))

            val offer = importer.inspectAndRetireIfEmpty()
            assertNotNull(offer)
            assertEquals(listOf("ANTHROPIC", "OPENAI"), offer.providerIds)
            assertEquals(2, offer.sourcePaths.size)
            assertContains(offer.sourcePaths, rpaFile.absolutePath)

            assertEquals(listOf("ANTHROPIC", "OPENAI"), importer.import().getOrNull()?.sorted())
            assertFalse(hostFile.exists())
            assertFalse(rpaFile.exists())
            assertTrue(File(hostDir, "llm_settings.json.migrated").exists())
            assertTrue(File(rpaDir, "llm-settings.json.migrated").exists())
        }

    @Test
    fun `an unparseable file is skipped without retiring it`() =
        runTest {
            val dir = tempDir("llmrpa-bad")
            val file = File(dir, "llm-settings.json")
            file.writeText("not json at all")
            val env = envWith()
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env)

            assertNull(importer(store, env, listOf(llmRpaSource(file))).inspectAndRetireIfEmpty())
            // Keep it: it may hold keys behind a syntax error a user can still fix by hand.
            assertTrue(file.exists())
        }

    // ==================== the production source list ====================

    @Test
    fun `defaultSources points at both historical paths`() {
        val root = File("/tmp/boss-root-fixture")
        val sources = LegacySettingsImport.defaultSources(root)

        assertEquals(2, sources.size)
        assertEquals(File(root, "llm_settings.json").absolutePath, sources[0].file.absolutePath)
        // llmrpa hardcoded `~/.boss/config` and never honoured boss.dev.mode, so its source
        // deliberately does NOT follow bossRootDir — under a dev host the keys are still in
        // `.boss`, and following the root here would silently miss them.
        val home = System.getProperty("user.home")
        assertEquals(
            File(home, ".boss/config/llm-settings.json").absolutePath,
            sources[1].file.absolutePath,
        )
    }
}
