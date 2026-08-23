package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.EnvResolver
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the MCP write tools' effect on the AI provider cache.
 *
 * These existed with no tests at all, which is how a miswired constructor shipped: the
 * provider took an `aiProviderStore` with a `= null` default, the sole call site was never
 * updated, and both `invalidate()` calls were unreachable in the released jar while the
 * change looked done. Behaviour is asserted here; the parameter is non-null so the wiring
 * itself cannot regress without a compile error.
 */
class SecretManagerMcpToolsTest {
    private fun storeWith(entries: List<SecretEntryData>): Pair<ProviderCredentialStore, FakeSecrets> {
        val provider = FakeSecrets(entries)
        val dir: File = Files.createTempDirectory("mcp-env").toFile()
        File(dir, "env_vars").writeText("")
        val resolver =
            EnvResolver(
                bossRootDir = dir,
                processEnv = { null },
                systemProperty = { null },
                useLaunchctl = false,
            )
        return ProviderCredentialStore(provider, resolver) to provider
    }

    private fun tool(
        store: ProviderCredentialStore,
        secrets: SecretDataProvider,
        name: String,
    ) = SecretManagerMcpToolProvider("test", secrets, store)
        .tools()
        .single { it.name == name }

    @Test
    fun `secret_delete invalidates the provider cache`() =
        runTest {
            // An agent deleting an ai-provider entry must not leave activeConfig() serving the
            // revoked credential for the rest of the session.
            val (store, secrets) = storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live")))
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertFalse(result.isError, result.text)
            assertEquals(listOf("1"), secrets.deleted)
            assertTrue(store.invalidations.value > before, "delete did not invalidate the cache")
        }

    @Test
    fun `secret_create invalidates the provider cache`() =
        runTest {
            // Invalidation is unconditional on a successful write. Note what this does NOT
            // prove: secret_create hardcodes tags = emptyList(), and loadStoredSecrets filters
            // on TAG_AI_PROVIDER, so an agent-created entry cannot be recognised as provider
            // configuration today — the invalidation here only costs a re-page. Kept because it
            // is the cheap, future-proof side, and it becomes load-bearing the moment
            // secret_create accepts tags. secret_delete is the half that matters now.
            val (store, secrets) = storeWith(emptyList())
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_create")
                    .handler
                    .call(
                        McpToolArgs(
                            mapOf(
                                "website" to "OPENAI",
                                "username" to "OPENAI_API_KEY",
                                "password" to "sk-new",
                            ),
                        ),
                    )

            assertFalse(result.isError, result.text)
            assertEquals(1, secrets.created.size)
            assertTrue(store.invalidations.value > before, "create did not invalidate the cache")
        }

    @Test
    fun `a failed write does not invalidate`() =
        runTest {
            // Invalidation costs a full re-page of the secret store, so it should follow a
            // change that actually happened.
            val (store, secrets) = storeWith(emptyList())
            secrets.failWrites = true
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertEquals(before, store.invalidations.value, "invalidated after a failed write")
        }

    @Test
    fun `a missing argument is rejected before any write`() =
        runTest {
            val (store, secrets) = storeWith(emptyList())
            val before = store.invalidations.value

            val result =
                tool(store, secrets, "secret_delete")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertTrue(result.isError)
            assertEquals(0, secrets.deleted.size)
            assertEquals(before, store.invalidations.value)
        }

    @Test
    fun `secret_get withholds an AI provider key`() =
        runTest {
            // secrets_list hands out ids and secret_get hands out plaintext, so ungated this is
            // two model-directed calls from a prompt-injected agent to every provider key.
            val (store, secrets) = storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live-secret")))

            val result =
                tool(store, secrets, "secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertFalse(result.text.contains("sk-live-secret"), "the key leaked: ${result.text}")
        }

    @Test
    fun `secret_get still returns an ordinary secret`() =
        runTest {
            // The gate must be scoped to provider keys — this tool's whole purpose otherwise.
            val ordinary =
                SecretEntryData(
                    id = "2",
                    website = "example.com",
                    username = "me",
                    password = "hunter2",
                    tags = listOf("personal"),
                    createdAt = "2026-01-01",
                    updatedAt = "2026-01-01",
                )
            val (store, secrets) = storeWith(listOf(ordinary))

            val result =
                tool(store, secrets, "secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "2")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("hunter2"))
        }

    @Test
    fun `my_secret_get withholds an AI provider key`() =
        runTest {
            // The sibling gate. This tool came from the retired user-secret-list plugin, where
            // it had no such check for three days: same vault, same secret.read gate, so it
            // read exactly the keys secret_get refuses. A gate on one tool and not the other
            // is no gate, which is why both now call one function.
            val (store, secrets) =
                storeWith(listOf(aiProviderSecret("1", "OPENAI", "sk-live-secret")))
            secrets.sharingEntries = listOf(sharedProviderKey("1", "OPENAI", "sk-live-secret"))

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "1")))

            assertTrue(result.isError)
            assertFalse(result.text.contains("sk-live-secret"), "the key leaked: ${result.text}")
        }

    @Test
    fun `my_secret_get returns a secret shared with me`() =
        runTest {
            // The gate must not cost this tool its purpose: reading something a colleague
            // shared, which is the one thing secret_get cannot see at all.
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries =
                listOf(
                    SecretEntryWithSharingData(
                        id = "9",
                        website = "stripe.com",
                        username = "ops",
                        password = "shared-pw",
                        tags = listOf("billing"),
                        createdAt = "2026-01-01",
                        updatedAt = "2026-01-01",
                        isOwner = false,
                        sharedByEmail = "anu@example.com",
                        accessLevel = "read",
                    ),
                )

            val result =
                tool(store, secrets, "my_secret_get")
                    .handler
                    .call(McpToolArgs(mapOf("id" to "9")))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("shared-pw"))
            assertTrue(result.text.contains("shared(read)"), result.text)
        }

    @Test
    fun `my_secrets_list reports how each secret was reached`() =
        runTest {
            val (store, secrets) = storeWith(emptyList())
            secrets.sharingEntries =
                listOf(
                    sharingEntry("1", "mine.com", accessLevel = "owner", isOwner = true),
                    sharingEntry("2", "theirs.com", accessLevel = "read", isOwner = false),
                )

            val result =
                tool(store, secrets, "my_secrets_list")
                    .handler
                    .call(McpToolArgs(emptyMap()))

            assertFalse(result.isError, result.text)
            assertTrue(result.text.contains("[owner]"), result.text)
            assertTrue(result.text.contains("[shared(read)]"), result.text)
        }

    private fun sharedProviderKey(
        id: String,
        providerId: String,
        password: String,
    ) = SecretEntryWithSharingData(
        id = id,
        website = providerId,
        username = "${providerId}_API_KEY",
        password = password,
        tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, providerId),
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        isOwner = true,
        accessLevel = "owner",
    )

    private fun sharingEntry(
        id: String,
        website: String,
        accessLevel: String,
        isOwner: Boolean,
    ) = SecretEntryWithSharingData(
        id = id,
        website = website,
        username = "user",
        password = "pw",
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        isOwner = isOwner,
        accessLevel = accessLevel,
    )

    private fun aiProviderSecret(
        id: String,
        providerId: String,
        password: String,
    ) = SecretEntryData(
        id = id,
        website = providerId,
        username = "${providerId}_API_KEY",
        password = password,
        tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, providerId),
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    /** Records writes; only the members the tools touch do anything. */
    private class FakeSecrets(
        var entries: List<SecretEntryData>,
        /**
         * What `getUserSecretsWithSharingInfo` serves, separate from [entries] because the
         * two RPCs behind them return different sets: `get_user_secrets` is own + organisation
         * secrets, `get_user_secrets_with_shared` adds everything shared with the caller.
         */
        var sharingEntries: List<SecretEntryWithSharingData> = emptyList(),
    ) : SecretDataProvider {
        val created = mutableListOf<CreateSecretRequestData>()
        val deleted = mutableListOf<String>()
        var failWrites = false

        override suspend fun getUserSecrets(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> {
            val page = entries.drop(offset).take(limit)
            return Result.success(PaginatedSecretsData(page, hasMore = offset + page.size < entries.size))
        }

        override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            created += request
            return Result.success(Unit)
        }

        override suspend fun deleteSecret(id: String): Result<Unit> {
            if (failWrites) return Result.failure(IllegalStateException("write refused"))
            deleted += id
            entries = entries.filterNot { it.id == id }
            return Result.success(Unit)
        }

        override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> =
            Result.success(Unit)

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithSharingData> {
            val page = sharingEntries.drop(offset).take(limit)
            return Result.success(
                PaginatedSecretsWithSharingData(page, hasMore = offset + page.size < sharingEntries.size),
            )
        }

        override suspend fun searchSecrets(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> = Result.failure(UnsupportedOperationException())

        override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
            Result.failure(UnsupportedOperationException())

        override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }
}
