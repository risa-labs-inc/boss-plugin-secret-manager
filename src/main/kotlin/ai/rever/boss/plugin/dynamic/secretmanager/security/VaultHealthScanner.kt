package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretDataProvider
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Runs [VaultHealth] over the whole vault by paging the provider to exhaustion.
 *
 * Reuse is a property of the entire set, so a health report over a single page
 * silently understates it. This pages `getUserSecrets` until `hasMore` is false
 * (owned secrets only - shared and org-owned entries come from a different call and
 * are deliberately out of scope, since a user cannot rotate another party's
 * password). It checks for cancellation between pages, and a failed page throws
 * rather than analysing a partial set, so the caller shows an error instead of a
 * wrong "all clear".
 */
object VaultHealthScanner {
    private const val DEFAULT_PAGE_SIZE = 100

    /** Upper bound so a very large vault cannot spin here forever. */
    private const val SCAN_CAP = 5000

    suspend fun scan(
        provider: SecretDataProvider,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        cap: Int = SCAN_CAP,
    ): VaultHealth.Report {
        val records = mutableListOf<VaultHealth.PasswordRecord>()
        var offset = 0
        while (offset < cap) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecrets(limit = pageSize, offset = offset).getOrThrow()
            page.data.forEach { records.add(VaultHealth.PasswordRecord(it.id, it.website, it.password)) }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
        }
        return VaultHealth.analyze(records)
    }
}
