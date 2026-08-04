package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [ProviderCredentialStore]'s invariants, which are the security story of this feature:
 * environment-then-stored-then-none precedence, never writing an env-supplied key back to
 * storage, and paging far enough into the secret list that a heavy user doesn't appear to
 * have nothing configured.
 *
 * Every case here goes through a fake [SecretDataProvider] rather than a live store, so the
 * precedence rules are exercised without a signed-in session.
 */
class ProviderCredentialStoreTest {
    private val together = ProviderRegistry.find(ProviderRegistry.TOGETHER)!!
    private val openai = ProviderRegistry.find(ProviderRegistry.OPENAI)!!

    /**
     * A hermetic [EnvResolver]: the fixture file is the *only* source of variables.
     *
     * The file alone isn't enough — it is consulted last, after the process environment,
     * system properties and `launchctl`. These tests use the registry's real variable names
     * (they have to: that is what the store looks up), so a developer with `OPENAI_API_KEY`
     * exported would otherwise have the real value beat the fixture. Stubbing all three
     * makes the suite independent of whoever runs it.
     */
    private fun envWith(contents: String = ""): EnvResolver {
        val dir: File = Files.createTempDirectory("cred-store-env").toFile()
        File(dir, "env_vars").writeText(contents)
        return EnvResolver(
            bossRootDir = dir,
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )
    }

    private fun secret(
        id: String,
        providerId: String,
        password: String,
        notes: String? = null,
        tags: List<String> = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, providerId),
    ) = SecretEntryData(
        id = id,
        website = providerId,
        username = "$providerId-account",
        password = password,
        notes = notes,
        tags = tags,
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    // ==================== precedence ====================

    @Test
    fun `an environment key wins over a stored one and is reported as such`() =
        runTest {
            // Precedence is env -> stored -> none. If this inverted, a user who set a
            // variable to take over key management would silently keep using an old
            // stored key.
            val provider = FakeSecretDataProvider(listOf(secret("1", together.id, "stored-key")))
            val store =
                ProviderCredentialStore(provider, envWith("${together.standardKeyName}=env-key"))

            val connection = store.loadAll().connections.getValue(together.id)

            assertEquals("env-key", connection.apiKey)
            assertEquals(CredentialSource.ENVIRONMENT, connection.source)
        }

    @Test
    fun `a stored key is used when the environment has none`() =
        runTest {
            val provider = FakeSecretDataProvider(listOf(secret("1", together.id, "stored-key")))
            val store = ProviderCredentialStore(provider, envWith())

            val connection = store.loadAll().connections.getValue(together.id)

            assertEquals("stored-key", connection.apiKey)
            assertEquals(CredentialSource.STORED, connection.source)
            assertTrue(connection.isConfigured)
        }

    @Test
    fun `a provider with neither is not configured`() =
        runTest {
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), envWith())

            val connection = store.loadAll().connections.getValue(openai.id)

            assertEquals(CredentialSource.NONE, connection.source)
            assertFalse(connection.isConfigured)
            assertEquals("", connection.apiKey)
        }

    // ==================== refusing to write an env key back ====================

    @Test
    fun `saveKey refuses when the environment already supplies the key`() =
        runTest {
            // This is the defect the feature was written to fix: the old host seeded its
            // fields from the environment and wrote every one back, landing an env-only key
            // in plaintext on disk. Nothing may reach the provider here.
            val provider = FakeSecretDataProvider(emptyList())
            val store =
                ProviderCredentialStore(provider, envWith("${together.standardKeyName}=env-key"))

            val result = store.saveKey(together.id, "typed-key")

            assertTrue(result.isFailure)
            assertEquals(0, provider.created.size, "wrote a secret despite an env-supplied key")
            assertEquals(0, provider.updated.size)
        }

    @Test
    fun `saveKey refuses a blank key rather than storing an empty credential`() =
        runTest {
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, envWith())

            assertTrue(store.saveKey(together.id, "   ").isFailure)
            assertEquals(0, provider.created.size)
        }

    @Test
    fun `a new key is created under the provider's standard env var name`() =
        runTest {
            // The user asked for entries named like TOGETHER_API_KEY and tagged as API
            // keys, so they are recognisable in the secret list rather than opaque rows.
            val provider = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(provider, envWith())

            assertTrue(store.saveKey(together.id, "new-key").isSuccess)

            val created = provider.created.single()
            assertEquals(together.id, created.website)
            assertEquals(together.standardKeyName, created.username)
            assertEquals("new-key", created.password)
            assertTrue(created.tags.contains(ProviderCredentialStore.TAG_AI_PROVIDER))
            assertTrue(created.tags.contains(ProviderCredentialStore.TAG_API_KEY))
            assertTrue(created.tags.contains(together.id))
        }

    @Test
    fun `saving over an existing provider updates rather than duplicating`() =
        runTest {
            // Two entries for one provider would make "which key is live" depend on page
            // order, which first-entry-wins then decides silently.
            val provider = FakeSecretDataProvider(listOf(secret("1", together.id, "old-key")))
            val store = ProviderCredentialStore(provider, envWith())

            assertTrue(store.saveKey(together.id, "new-key").isSuccess)

            assertEquals(0, provider.created.size, "created a duplicate entry")
            assertEquals("new-key", provider.updated.single().password)
        }

    // ==================== paging ====================

    @Test
    fun `a provider entry beyond the first page is still found`() =
        runTest {
            // The store pages rather than reading the first page only; a user with a few
            // hundred passwords would otherwise see every provider as unconfigured.
            val filler = (1..120).map { secret("f$it", "not-a-provider-$it", "x", tags = emptyList()) }
            val provider = FakeSecretDataProvider(filler + secret("target", openai.id, "found-me"))
            val store = ProviderCredentialStore(provider, envWith())

            val connection = store.loadAll().connections.getValue(openai.id)

            assertEquals("found-me", connection.apiKey)
            assertTrue(provider.pageRequests.size > 1, "never asked for a second page")
        }

    @Test
    fun `a store read failure is reported without hiding environment keys`() =
        runTest {
            // A signed-out or failing store must not take the environment down with it —
            // for packaged builds env_vars is the documented way a key arrives.
            val provider = FakeSecretDataProvider(emptyList(), failReads = true)
            val store =
                ProviderCredentialStore(provider, envWith("${together.standardKeyName}=env-key"))

            val snapshot = store.loadAll()

            assertTrue(snapshot.storeReadFailed)
            assertEquals("env-key", snapshot.connections.getValue(together.id).apiKey)
        }

    // ==================== settings on the notes field ====================

    @Test
    fun `settings ride along on the stored entry`() =
        runTest {
            val notes = """{"selectedModelId":"gpt-5","temperature":0.3,"maxTokens":4096}"""
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, "k", notes = notes)))
            val store = ProviderCredentialStore(provider, envWith())

            val connection = store.loadAll().connections.getValue(openai.id)

            assertEquals("gpt-5", connection.selectedModelId)
            assertEquals(0.3f, connection.temperature)
            assertEquals(4096, connection.maxTokens)
        }

    @Test
    fun `saveSettings reports false when there is no secret to attach to`() =
        runTest {
            // The three-way result callers depend on: false means "nothing stored to hang
            // this on" (an env-keyed provider), so the caller falls back to prefs instead
            // of creating a blank-password secret.
            val provider = FakeSecretDataProvider(emptyList())
            val store =
                ProviderCredentialStore(provider, envWith("${together.standardKeyName}=env-key"))

            val result = store.saveSettings(together.id, ProviderSettings(selectedModelId = "m"))

            assertEquals(false, result.getOrNull())
            assertEquals(0, provider.created.size, "created a credential-less secret")
        }

    @Test
    fun `saving settings round-trips the password byte-for-byte`() =
        runTest {
            // UpdateSecretRequestData has no partial form, so choosing a model rewrites the
            // whole record — password included, taken from what getUserSecrets returned.
            // This is the highest-consequence write in the feature: if it ever sends
            // anything but the exact stored secret, picking a model destroys the credential.
            val key = "sk-exact-value-with-=-and-padding=="
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, key)))
            val store = ProviderCredentialStore(provider, envWith())

            val result = store.saveSettings(openai.id, ProviderSettings(selectedModelId = "gpt-5"))

            assertEquals(true, result.getOrNull())
            assertEquals(key, provider.updated.single().password)
            assertTrue(provider.updated.single().notes?.contains("gpt-5") == true)
        }

    @Test
    fun `settings are refused rather than written when the stored key reads back empty`() =
        runTest {
            // Guards the case above from the other side: if a host ever redacts passwords in
            // list payloads, a full-record write would silently blank the credential. Fail
            // visibly instead of performing the write.
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, "")))
            val store = ProviderCredentialStore(provider, envWith())

            val result = store.saveSettings(openai.id, ProviderSettings(selectedModelId = "gpt-5"))

            assertTrue(result.isFailure)
            assertEquals(0, provider.updated.size, "overwrote an entry whose secret read back empty")
        }

    // ==================== cache invalidation ====================

    @Test
    fun `invalidate makes the next read see a change made behind the store`() =
        runTest {
            // The cache exists so a save doesn't re-page three times; the cost is that an
            // edit or delete from the secret list must invalidate it, or a removed key
            // keeps being handed out for the rest of the session.
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, "first-key")))
            val store = ProviderCredentialStore(provider, envWith())

            assertEquals("first-key", store.loadAll().connections.getValue(openai.id).apiKey)

            provider.entries = emptyList()
            assertEquals(
                "first-key",
                store.loadAll().connections.getValue(openai.id).apiKey,
                "expected the cached value until invalidated",
            )

            store.invalidate()
            val after = store.loadAll().connections.getValue(openai.id)
            assertEquals("", after.apiKey)
            assertEquals(CredentialSource.NONE, after.source)
        }

    @Test
    fun `invalidate publishes a signal so derived snapshots can re-read`() =
        runTest {
            // Clearing this class's cache is not enough on its own: AiProvidersViewModel holds
            // its own connections map and activeConfig() answers other plugins from that. This
            // flow is how it learns to re-read, so a secret deleted from the list stops being
            // served without the user having to open the AI settings panel.
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, "k")))
            val store = ProviderCredentialStore(provider, envWith())

            val before = store.invalidations.value
            store.invalidate()
            assertTrue(store.invalidations.value > before, "invalidate() published no signal")

            // Writes invalidate too, so a key added here reaches other readers the same way.
            val afterManual = store.invalidations.value
            store.saveKey(together.id, "new-key")
            assertTrue(store.invalidations.value > afterManual, "a write published no signal")
        }

    @Test
    fun `clearKey removes the stored entry`() =
        runTest {
            val provider = FakeSecretDataProvider(listOf(secret("1", openai.id, "k")))
            val store = ProviderCredentialStore(provider, envWith())

            assertTrue(store.clearKey(openai.id).isSuccess)

            assertEquals(listOf("1"), provider.deleted)
            assertNull(store.loadAll().connections.getValue(openai.id).selectedModelId)
        }
}
