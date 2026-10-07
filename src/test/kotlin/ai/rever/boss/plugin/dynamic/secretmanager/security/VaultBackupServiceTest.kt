package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretEntryWithSharingAccessData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingAccessData
import ai.rever.boss.plugin.api.CreateSecretRequestData
import kotlinx.coroutines.CancellationException
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
    fun `export keeps 2FA seed but restore refuses lossy seeded entries`() =
        runTest {
            val source =
                FakeSecretDataProvider(
                    listOf(
                        secret(
                            "1", "github.com", "john", "hunter2",
                            SecretMetadataData(
                                twofaEnabled = true,
                                twofaType = "app",
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

            assertEquals(1, outcome.imported)
            assertEquals(1, outcome.unsupportedTwofa)
            assertEquals("JBSWY3DPEHPK3PXP", VaultBackupCodec.import(blob, passphrase).first().twofaSecret)
            assertEquals(0, outcome.skipped)
            assertEquals(listOf("bank.example"), dest.created.map { it.website })
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
    @Test
    fun `export over limit fails instead of writing partial backup`() = runTest {
        val provider = FakeSecretDataProvider((1..5001).map { secret("$it", "site", "$it", "pw") })
        assertFailsWith<VaultBackupException> { VaultBackupService.exportVault(provider, passphrase) }
        assertEquals(50, provider.pageRequests.size)
    }

    @Test
    fun `existing vault over limit fails before any restore writes`() = runTest {
        val blob = VaultBackupCodec.export(listOf(BackupEntry("new", "u", "pw")), passphrase)
        val dest = FakeSecretDataProvider((1..5001).map { secret("$it", "site", "$it", "pw") })
        assertFailsWith<VaultBackupException> { VaultBackupService.importVault(blob, passphrase, dest) }
        assertTrue(dest.created.isEmpty())
    }

    @Test
    fun `case-sensitive usernames and URL paths remain separate entries`() = runTest {
        val blob = VaultBackupCodec.export(listOf(BackupEntry("site/Path", "User", "pw"),
            BackupEntry("site/path", "user", "pw")), passphrase)
        val dest = FakeSecretDataProvider(listOf(secret("1", "site/Path", "user", "old")))
        val outcome = VaultBackupService.importVault(blob, passphrase, dest)
        assertEquals(2, outcome.imported)
    }

    @Test
    fun `failed writes are counted but cancellation is propagated`() = runTest {
        val blob = VaultBackupCodec.export(listOf(BackupEntry("site", "u", "pw")), passphrase)
        val fake = FakeSecretDataProvider(emptyList(), failWrites = true)
        assertEquals(1, VaultBackupService.importVault(blob, passphrase, fake).failed)
        val provider = object : SecretDataProvider by fake {
            override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> =
                Result.failure(CancellationException("cancelled"))
        }
        assertFailsWith<CancellationException> { VaultBackupService.importVault(blob, passphrase, provider) }
    }

    @Test
    fun `an inconsistent empty page fails before import creates anything`() = runTest {
        val blob = VaultBackupCodec.export(listOf(BackupEntry("site", "u", "pw")), passphrase)
        val fake = FakeSecretDataProvider(emptyList())
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int) =
                Result.success(PaginatedSecretsWithSharingAccessData(emptyList(), true))
        }
        assertFailsWith<VaultBackupException> { VaultBackupService.importVault(blob, passphrase, provider) }
        assertTrue(fake.created.isEmpty())
    }

    @Test
    fun `ordinary metadata is preserved on restore and in-file duplicates are skipped`() = runTest {
        val entry = BackupEntry("site", "u", "pw", notes = "note", expirationDate = "2030-01-01",
            tags = listOf("work"), twofaEnabled = true, twofaType = "sms", recoveryCodes = listOf("r1"))
        val blob = VaultBackupCodec.export(listOf(entry, entry), passphrase)
        val dest = FakeSecretDataProvider(emptyList())
        val outcome = VaultBackupService.importVault(blob, passphrase, dest)
        assertEquals(1, outcome.imported)
        assertEquals(1, outcome.skipped)
        assertEquals("note", dest.created.single().notes)
        assertEquals("2030-01-01", dest.created.single().expirationDate)
        assertEquals(listOf("work"), dest.created.single().tags)
        assertEquals("sms", dest.created.single().twofaType)
        assertEquals(listOf("r1"), dest.created.single().recoveryCodes)
    }

    @Test
    fun `only personal owner source is included even when org creator isOwner is true`() = runTest {
        val rows = listOf("owner" to true, "org-owner-labelled" to true, "org-creator" to true, "org-colleague" to false,
            "shared" to false, "unknown" to true).map { (label, owner) ->
            SecretEntryWithSharingData(id = label, website = label, username = "user", password = "pw",
                createdAt = "now", updatedAt = "now", isOwner = owner,
                accessLevel = when (label) { "owner", "org-owner-labelled" -> "owner"; "org-creator", "org-colleague" -> "org";
                    "shared" -> "read"; else -> "new-source" })
        }
        val fake = FakeSecretDataProvider(emptyList())
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingAccessData> =
                Result.success(PaginatedSecretsWithSharingAccessData(rows.drop(offset).take(limit).map { row ->
                    SecretEntryWithSharingAccessData(row, isOrgOwned = row.website.startsWith("org-"), canManage = true)
                }, offset + limit < rows.size))
        }
        val blob = VaultBackupService.exportVault(provider, passphrase)
        assertEquals(listOf("owner"), VaultBackupCodec.import(blob, passphrase).map { it.website })
        val restored = VaultBackupService.importVault(
            VaultBackupCodec.export(listOf(BackupEntry("org-creator", "user", "pw")), passphrase),
            passphrase, provider)
        assertEquals(1, restored.imported, "an org entry must not hide a personal restore")
    }

    @Test
    fun `published legacy fallback cannot export owner rows without ownership proof`() = runTest {
        val legacy = LegacySecretDataProvider(FakeSecretDataProvider(listOf(secret("creator", "org-site", "u", "pw"))))
        assertFailsWith<PersonalVaultOwnershipException> { VaultBackupService.exportVault(legacy, passphrase) }
    }

    @Test
    fun `published legacy fallback fails duplicate discovery before creating restore entries`() = runTest {
        val fake = FakeSecretDataProvider(listOf(secret("creator", "org-site", "u", "pw")))
        val blob = VaultBackupCodec.export(listOf(BackupEntry("personal", "u", "pw")), passphrase)
        assertFailsWith<PersonalVaultOwnershipException> {
            VaultBackupService.importVault(blob, passphrase, LegacySecretDataProvider(fake))
        }
        assertTrue(fake.created.isEmpty())
    }

    @Test
    fun `raw offsets cross org-only pages before exporting personal credentials`() = runTest {
        val fake = FakeSecretDataProvider((1..201).map { secret("$it", "site-$it", "u", "pw") })
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int) =
                fake.getUserSecretsWithSharingAccess(limit, offset).map { page ->
                    page.copy(data = page.data.map { it.copy(isOrgOwned = it.secret.id != "201") })
                }
        }
        val blob = VaultBackupService.exportVault(provider, passphrase)
        assertEquals(listOf("site-201"), VaultBackupCodec.import(blob, passphrase).map { it.website })
        assertEquals(listOf(100 to 0, 100 to 100, 100 to 200), fake.pageRequests)
    }

}
