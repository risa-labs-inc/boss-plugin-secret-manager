package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretMetadataData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Integration test for [VaultBackupService] against the shared fake provider: export/restore, dedup, and failure. */
class VaultBackupServiceTest {
    private val passphrase = "correct horse".toCharArray()

    private fun secret(
        id: String,
        site: String,
        user: String,
        password: String,
        metadata: SecretMetadataData? = null,
    ) = SecretEntryData(
        id = id,
        website = site,
        username = user,
        password = password,
        metadata = metadata,
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    @Test
    fun `export then restore writes every entry into a fresh vault, preserving fields`() =
        runTest {
            val source =
                FakeSecretDataProvider(
                    listOf(
                        secret(
                            "1", "github.com", "john", "hunter2",
                            SecretMetadataData(
                                twofaEnabled = true,
                                twofaType = "totp",
                                twofaSecret = "JBSWY3DPEHPK3PXP",
                                recoveryCodes = listOf("r1", "r2"),
                            ),
                        ),
                        secret("2", "bank.example", "jane", "s3cr3t"),
                    ),
                )
            val blob = VaultBackupService.exportVault(source, passphrase)

            val dest = FakeSecretDataProvider(emptyList())
            val outcome = VaultBackupService.importVault(blob, passphrase, dest)

            assertEquals(2, outcome.imported)
            assertEquals(0, outcome.skipped)
            val restored = dest.created.first { it.website == "github.com" }
            assertEquals("hunter2", restored.password)
            assertEquals(true, restored.twofaEnabled)
            assertEquals(listOf("r1", "r2"), restored.recoveryCodes)
        }

    @Test
    fun `restore skips an entry already present`() =
        runTest {
            val source = FakeSecretDataProvider(listOf(secret("1", "github.com", "john", "hunter2")))
            val blob = VaultBackupService.exportVault(source, passphrase)

            // Destination already holds the same website + username.
            val dest = FakeSecretDataProvider(listOf(secret("9", "github.com", "john", "old")))
            val outcome = VaultBackupService.importVault(blob, passphrase, dest)

            assertEquals(0, outcome.imported)
            assertEquals(1, outcome.skipped)
            assertTrue(dest.created.isEmpty(), "nothing was written for a duplicate")
        }

    @Test
    fun `a failed read during export throws rather than exporting a partial vault`() =
        runTest {
            val provider = FakeSecretDataProvider(emptyList(), failReads = true)
            assertFailsWith<IllegalStateException> { VaultBackupService.exportVault(provider, passphrase) }
        }
}
