package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.dynamic.secretmanager.VaultRow
import ai.rever.boss.plugin.dynamic.secretmanager.VaultRows
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Runs [VaultHealth] over the whole vault by paging the provider to exhaustion.
 *
 * Reuse is a property of the entire set, so a health report over a single page
 * silently understates it. This pages `getUserSecretsWithSharingAccess` until `hasMore` is false
 * (verified personal owner entries only; organisation markers override owner labels). It checks for cancellation between pages, and a failed page throws
 * rather than analysing a partial set, so the caller shows an error instead of a
 * wrong "all clear".
 */
class VaultHealthScanException(message: String) : Exception(message)

object VaultHealthScanner {
    private const val DEFAULT_PAGE_SIZE = 100

    /** Upper bound so a very large vault cannot spin here forever. */
    private const val SCAN_CAP = 5000

    suspend fun scan(
        provider: SecretDataProvider,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        cap: Int = SCAN_CAP,
    ): VaultHealth.Report {
        require(pageSize > 0 && cap > 0) { "Page size and scan limit must be positive" }
        val records = mutableListOf<VaultHealth.PasswordRecord>()
        var offset = 0
        while (offset < cap) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecretsWithSharingAccess(limit = minOf(pageSize, cap - offset), offset = offset).getOrThrow()
            coroutineContext.ensureActive()
            if (page.data.isEmpty() && page.hasMore) {
                throw VaultHealthScanException("Vault enumeration returned an empty page before completion")
            }
            if (offset + page.data.size > cap) throw VaultHealthScanException("Vault exceeds the health check limit of $cap entries")
            page.data.filter(PersonalVaultOwnership::includes).map { it.secret }.filter { isPasswordRow(it.website, it.notes, it.tags) }.forEach {
                records.add(VaultHealth.PasswordRecord(it.id, it.website, it.password, it.username))
            }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
            if (offset >= cap) throw VaultHealthScanException("Vault exceeds the health check limit of $cap entries; no partial report was produced")
        }
        coroutineContext.ensureActive()
        return VaultHealth.analyze(records)
    }
}

/** Card numbers, security codes and address filler are not passwords; rating them is noise. */
internal fun isPasswordRow(website: String, notes: String?, tags: List<String>): Boolean =
    when (VaultRows.classify(website, "", notes, tags)) {
        is VaultRow.Card, is VaultRow.CardCode, is VaultRow.Address -> false
        else -> true
    }
