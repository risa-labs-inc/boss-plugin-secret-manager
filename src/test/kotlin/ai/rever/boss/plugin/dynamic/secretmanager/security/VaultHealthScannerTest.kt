package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration test for [VaultHealthScanner] against the shared [FakeSecretDataProvider]:
 * it must page the WHOLE vault (reuse spanning pages is the case a single-page scan
 * would miss) and must fail rather than return a partial report.
 */
class VaultHealthScannerTest {
    private fun secret(
        id: String,
        site: String,
        password: String,
    ) = SecretEntryData(
        id = id,
        website = site,
        username = "user",
        password = password,
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
    )

    @Test
    fun `scans across pages and detects reuse spanning them`() =
        runTest {
            val entries =
                listOf(
                    secret("1", "github.com", "shared-pw"),
                    secret("2", "gitlab.com", "shared-pw"),
                    secret("3", "a.com", "Str0ng!Passphrase"),
                    secret("4", "b.com", "abc"),
                    secret("5", "c.com", "Another9!Strongxyz"),
                )
            val provider = FakeSecretDataProvider(entries)

            val report = VaultHealthScanner.scan(provider, pageSize = 2)

            assertEquals(5, report.analyzedCount, "every page was read")
            assertEquals(1, report.reuseGroups.size, "the reuse across pages 1 is found")
            assertTrue(provider.pageRequests.size >= 3, "the vault was paged, not read once: ${provider.pageRequests}")
        }

    @Test
    fun `a failed read throws rather than returning a partial report`() =
        runTest {
            val provider = FakeSecretDataProvider(emptyList(), failReads = true)
            assertFailsWith<IllegalStateException> { VaultHealthScanner.scan(provider) }
        }
}
