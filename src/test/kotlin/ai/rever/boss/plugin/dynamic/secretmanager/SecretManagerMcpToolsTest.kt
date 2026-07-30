package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
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
            // Symmetric case: a key an agent adds has to become visible without a restart.
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
        ): Result<PaginatedSecretsWithSharingData> = Result.failure(UnsupportedOperationException())

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
