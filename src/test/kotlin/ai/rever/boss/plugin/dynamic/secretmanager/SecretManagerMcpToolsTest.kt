package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.AccessibleSecretMetadata
import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.PaginatedAccessibleSecrets
import ai.rever.boss.plugin.api.SecretAccessProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretManagerMcpToolsTest {
    @Test
    fun `retains compatibility tool names under their own scoped identities`() {
        val names = SecretManagerMcpToolProvider("secret-manager", FakeScopedSecrets()).tools().map { it.name }

        assertTrue("my_secrets_list" in names)
        assertTrue("my_secret_get" in names)
    }

    @Test
    fun `list returns only scoped metadata and no plaintext`() = runTest {
        val secrets = FakeScopedSecrets()
        val tool = SecretManagerMcpToolProvider("secret-manager", secrets).named("secrets_list")

        val result = tool.handler.call(McpToolArgs(emptyMap()))

        assertFalse(result.isError)
        assertTrue(result.text.contains("example.com"))
        assertFalse(result.text.contains("password-value"))
        assertEquals(0, secrets.getCalls)
    }

    @Test
    fun `get performs one explicit plaintext lookup`() = runTest {
        val secrets = FakeScopedSecrets()
        val tool = SecretManagerMcpToolProvider("secret-manager", secrets).named("secret_get")

        val result = tool.handler.call(McpToolArgs(mapOf("id" to "one")))

        assertFalse(result.isError)
        assertTrue(result.text.contains("password-value"))
        assertEquals(1, secrets.getCalls)
    }

    @Test
    fun `mutations use owned-only scoped operations`() = runTest {
        val secrets = FakeScopedSecrets()
        val provider = SecretManagerMcpToolProvider("secret-manager", secrets)

        provider.named("secret_create").handler.call(
            McpToolArgs(mapOf("website" to "new.example", "username" to "me", "password" to "value")),
        )
        provider.named("secret_delete").handler.call(McpToolArgs(mapOf("id" to "one")))

        assertEquals(1, secrets.creates)
        assertEquals(listOf("one"), secrets.deletes)
    }

    private fun SecretManagerMcpToolProvider.named(name: String) = tools().single { it.name == name }
}

private class FakeScopedSecrets : SecretAccessProvider {
    private val entry =
        SecretEntryData(
            id = "one",
            website = "example.com",
            username = "person",
            password = "password-value",
            createdAt = "2026-09-16T00:00:00Z",
            updatedAt = "2026-09-16T00:00:00Z",
        )
    var getCalls = 0
    var creates = 0
    val deletes = mutableListOf<String>()

    override suspend fun listSecrets(limit: Int, offset: Int) =
        Result.success(
            PaginatedAccessibleSecrets(
                listOf(
                    AccessibleSecretMetadata(
                        id = entry.id,
                        website = entry.website,
                        username = entry.username,
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                        accessLevel = "use",
                    ),
                ),
                hasMore = false,
            ),
        )

    override suspend fun searchSecrets(query: String, limit: Int, offset: Int) = listSecrets(limit, offset)

    override suspend fun getSecret(secretId: String): Result<SecretEntryData?> {
        getCalls++
        return Result.success(entry.takeIf { it.id == secretId })
    }

    override suspend fun createSecret(request: CreateSecretRequestData): Result<String> {
        creates++
        return Result.success("created")
    }

    override suspend fun updateOwnedSecret(request: UpdateSecretRequestData) = Result.success(Unit)

    override suspend fun deleteOwnedSecret(secretId: String): Result<Unit> {
        deletes += secretId
        return Result.success(Unit)
    }
}
