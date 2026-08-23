package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long a copied credential may sit on the system clipboard. Same value the managed list
 * uses ([SecretManagerViewModel] has its own copy, private to that file).
 */
private const val CLIPBOARD_CLEAR_DELAY_MS = 45_000L

/**
 * How a secret reached the signed-in caller, as reported by `get_user_secrets_with_shared`.
 *
 * The RPC's `access_level` is the ONLY reliable partition key, and `isOwner` is not:
 * source 4 of that UNION returns organisation-owned secrets with
 * `is_owner = (s.user_id = auth.uid())`, so a colleague's organisation secret arrives with
 * `isOwner = false` while being nobody's share. Splitting on `isOwner` would file it under
 * "Shared with me" and claim someone shared it with you.
 *
 * See BossConsole `supabase/migrations/20260802000000_secrets_org_ownership.sql`, which
 * assigns these literals per source with an explicit dedup priority so the value cannot
 * flap between calls when a secret is reachable by several routes.
 */
internal object SecretAccess {
    /** Source 1: the caller created it. */
    const val OWNER = "owner"

    /** Source 4: owned by an organisation the caller belongs to. */
    const val ORG = "org"

    /**
     * True when the secret reached the caller through an actual share - by user, by role, or
     * by organisation (sources 2, 3 and 5, whose level is the share's own `read` / `write`).
     *
     * Anything unrecognised counts as a share rather than as ownership: a new UNION source
     * added server-side should surface in the read-only section, where no management control
     * can act on it, instead of silently joining the list that offers Edit and Delete.
     */
    fun isShare(accessLevel: String): Boolean =
        !accessLevel.equals(OWNER, ignoreCase = true) && !accessLevel.equals(ORG, ignoreCase = true)
}

/** True when this entry belongs in the "Shared with me" section. See [SecretAccess.isShare]. */
internal fun SecretEntryWithSharingData.isSharedWithMe(): Boolean = SecretAccess.isShare(accessLevel)

/**
 * The read-only "Shared with me" section of the Secret Manager panel: the secrets other
 * people have shared with the signed-in user, with no management controls at all.
 *
 * Ported from the retired `user-secret-list` plugin, whose panel listed everything the caller
 * could read - including their own secrets, which the other section of this panel already
 * shows. Keeping only shares is what makes one panel out of two non-overlapping halves.
 *
 * Reads `getUserSecretsWithSharingInfo`, which is a strict superset of the `getUserSecrets`
 * call behind the managed list: same columns plus `isOwner`, `sharedByEmail` and
 * `accessLevel`.
 */
class SharedSecretsViewModel(
    private val secretDataProvider: SecretDataProvider?,
    private val scope: CoroutineScope,
) {
    private val logger = BossLogger.forComponent("SharedSecrets")

    private val _state = MutableStateFlow(SharedSecretsState())
    val state: StateFlow<SharedSecretsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Cancelling is not enough on its own, which is why this exists as well as [loadJob].
     *
     * Cancellation is cooperative and only lands at a suspension point; the last one in the
     * scan loop is the provider call itself. A page that has already returned when
     * `lifecycle.doOnDestroy` fires would otherwise run the terminal `_state.update` and seat
     * `allShared` - a list of entries each carrying a decrypted `password` - onto a ViewModel
     * the panel has just destroyed. Same reason [SecretManagerViewModel] has its own flag.
     */
    private var disposed = false

    /** Invalidates a pending clipboard wipe so a re-copy gets its own full window. */
    private var clipboardCopyGeneration = 0L

    /**
     * Load on first entry into the section, then never again on its own.
     *
     * Deliberately not an `init` load: this ViewModel is constructed with the panel, and
     * fetching here would fire a second secrets RPC on every panel open for a section the
     * user may not visit. The panel's Refresh button drives [refresh].
     */
    fun ensureLoaded() {
        val current = _state.value
        if (current.hasLoadedOnce || current.isLoading || loadJob?.isActive == true) return
        load(reset = true)
    }

    /** Discard what is loaded and fetch from the first page again. */
    fun refresh() = load(reset = true)

    /** Continue scanning past what is loaded, when the server says there is more. */
    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.isLoading || current.isLoadingMore) return
        if (current.searchQuery.isNotBlank()) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        if (disposed) return
        val provider =
            secretDataProvider ?: run {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        hasLoadedOnce = true,
                        errorMessage = "Secret data provider not available",
                    )
                }
                return
            }

        if (reset) {
            loadJob?.cancel()
        } else if (loadJob?.isActive == true) {
            return
        }

        _state.update {
            if (reset) {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    errorMessage = null,
                    // The filter is deliberately KEPT. The panel this section came from cleared
                    // it on every load, which means the header Refresh silently discards what
                    // the user typed with nothing to say so. The terminal update re-applies it
                    // to the fresh page.
                    lastLoadDurationMs = null,
                )
            } else {
                it.copy(isLoadingMore = true, errorMessage = null)
            }
        }

        loadJob =
            scope.launch {
                var offset = if (reset) 0 else _state.value.rowsScanned
                var shares = if (reset) emptyList() else _state.value.allShared
                var hasMore = true
                var pages = 0
                var elapsedMs = 0L

                // Auto-continue while a page yields no shares. A page is 50 entries of
                // EVERYTHING the caller can read, so someone with 60 of their own secrets and
                // two shared ones gets a first page filtered down to nothing - and a section
                // reporting "no shared secrets" while the server still has some is a lie the
                // user cannot tell from the truth. Capped so a large vault cannot turn one
                // section switch into an unbounded scan; the Load more control takes over.
                while (true) {
                    val startedAt = System.nanoTime()
                    val result = provider.getUserSecretsWithSharingInfo(limit = PAGE_SIZE, offset = offset)
                    elapsedMs = elapsedMsSince(startedAt)

                    val page =
                        result.getOrElse { error ->
                            if (error is CancellationException) throw error
                            val message = error.message ?: "Unknown error"
                            logTiming("getUserSecretsWithSharingInfo(offset=$offset)", elapsedMs, message, failed = true)
                            if (disposed) return@launch
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    isLoadingMore = false,
                                    hasLoadedOnce = true,
                                    errorMessage = message,
                                )
                            }
                            return@launch
                        }

                    pages++
                    // Advance by the RAW row count, not the filtered one: the offset addresses
                    // the server's full accessible set, so counting only shares would re-read
                    // the same page forever.
                    offset += page.data.size
                    shares = shares + page.data.filter { it.isSharedWithMe() }
                    // An empty page ends the scan whatever `hasMore` says - the host derives
                    // that flag from `size >= limit`, but trusting it alone would spin here if
                    // it were ever true for a page with no rows.
                    hasMore = page.hasMore && page.data.isNotEmpty()

                    logTiming(
                        "getUserSecretsWithSharingInfo(offset=${offset - page.data.size})",
                        elapsedMs,
                        "${page.data.size} accessible, ${shares.size} shared so far",
                    )

                    // Note the asymmetry between the two entry points, which is deliberate:
                    // `shares` is prefilled from what is already loaded, so once anything has
                    // been found this breaks on the first iteration and a scroll-triggered
                    // loadMore fetches exactly one page. That page can add nothing and the list
                    // just does not change - fine there, because the scroll trigger fires again
                    // and the user can see it is still paging. The auto-continue is for the
                    // *first* load, where an empty result is indistinguishable from "you have
                    // nothing shared with you".
                    if (!hasMore || shares.isNotEmpty() || pages >= MAX_AUTO_PAGES) break
                }

                if (disposed) return@launch
                val settled = shares
                _state.update {
                    it.copy(
                        allShared = settled,
                        shared = if (it.searchQuery.isBlank()) settled else settled.filterBy(it.searchQuery),
                        isLoading = false,
                        isLoadingMore = false,
                        hasLoadedOnce = true,
                        // A load that succeeded must not leave the previous failure's banner
                        // standing: both entry points clear it on the way in, which misses a
                        // cancelled load whose failure update lands after a fresh one started.
                        errorMessage = null,
                        rowsScanned = offset,
                        hasMore = hasMore,
                        lastLoadDurationMs = elapsedMs,
                    )
                }
            }
    }

    /**
     * Copy a shared secret's value and wipe it from the clipboard again after
     * [CLIPBOARD_CLEAR_DELAY_MS].
     *
     * The same policy [SecretManagerViewModel.copyPasswordToClipboard] applies to the managed
     * list, and for the same reason: one panel holding two clipboard policies for the same
     * class of data is not a decision, it is an oversight - and the read-only half is not the
     * one that should have the weaker rule. The panel this section replaces had no wipe at all.
     *
     * The generation token is what makes a re-copy get its own full window and stops a later,
     * unrelated copy being clobbered; the value check stops the wipe clearing something the
     * user copied from somewhere else in the meantime.
     *
     * The pending wipe deliberately outlives the panel. [dispose] bumps the generation but does
     * not cancel it: leaving a credential on the system clipboard indefinitely is an unbounded
     * OS-level exposure, traded for nothing.
     */
    fun copySecretToClipboard(value: String, clipboard: ClipboardManager) {
        val generation = ++clipboardCopyGeneration
        clipboard.setText(AnnotatedString(value))
        scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            if (generation == clipboardCopyGeneration && clipboard.getText()?.text == value) {
                clipboard.setText(AnnotatedString(""))
            }
        }
    }

    /** Filter what is loaded by website or username. Client-side, like the section it replaces. */
    fun search(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                shared = if (query.isBlank()) it.allShared else it.allShared.filterBy(query),
            )
        }
    }

    fun toggleMetadataExpanded(secretId: String) {
        _state.update { state ->
            state.copy(
                expandedSecretIds =
                    if (state.expandedSecretIds.contains(secretId)) {
                        state.expandedSecretIds - secretId
                    } else {
                        state.expandedSecretIds + secretId
                    },
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Stop the scan when the panel goes away, and drop what it found.
     *
     * The load runs on the *plugin* scope, which outlives this instance, so an in-flight
     * auto-continue would otherwise keep a list of decrypted secrets alive for the plugin's
     * whole lifetime. Same reason [SecretManagerViewModel.dispose] exists - and, like it,
     * cancelling alone is not enough: a completed load leaves the whole list in state, so
     * this clears it too.
     *
     * The pending clipboard wipe is deliberately *not* cancelled - see [copySecretToClipboard].
     */
    fun dispose() {
        disposed = true
        loadJob?.cancel()
        loadJob = null
        // Deliberately does NOT touch clipboardCopyGeneration: bumping it here invalidates the
        // pending wipe's generation check, so the credential stays on the clipboard forever -
        // the exact outcome the wipe exists to prevent. Caught by
        // `dispose does not cancel a pending clipboard wipe`.
        //
        // Cancelling stops a scan in flight; it does nothing about a scan that already
        // finished, whose result is sitting in state with every password in it.
        _state.update {
            it.copy(
                allShared = emptyList(),
                shared = emptyList(),
                expandedSecretIds = emptySet(),
            )
        }
    }

    /** Monotonic, so a wall-clock (NTP) jump cannot produce a negative duration. */
    private fun elapsedMsSince(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    /** Elapsed time per fetch, so an intermittently slow load is diagnosable from the host console. */
    private fun logTiming(
        operation: String,
        elapsedMs: Long,
        outcome: String,
        failed: Boolean = false,
    ) {
        val message = "$operation: ${if (failed) "FAILED ($outcome)" else outcome} in $elapsedMs ms"
        if (failed) {
            logger.warn(LogCategory.NETWORK, message)
        } else {
            logger.info(LogCategory.NETWORK, message)
        }
    }

    internal companion object {
        const val PAGE_SIZE = 50

        /** Pages one section switch may scan before handing back to the Load more control. */
        const val MAX_AUTO_PAGES = 5
    }
}

private fun List<SecretEntryWithSharingData>.filterBy(query: String) =
    filter { it.website.contains(query, ignoreCase = true) || it.username.contains(query, ignoreCase = true) }

/**
 * State of the "Shared with me" section.
 *
 * [rowsScanned] counts entries read from the server, not shares found: it is the pagination
 * offset, and it is what tells the empty state "nothing shared in the first 250 secrets"
 * apart from "you have nothing shared with you".
 */
data class SharedSecretsState(
    val allShared: List<SecretEntryWithSharingData> = emptyList(),
    val shared: List<SecretEntryWithSharingData> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val expandedSecretIds: Set<String> = emptySet(),
    val rowsScanned: Int = 0,
    val hasMore: Boolean = true,
    val lastLoadDurationMs: Long? = null,
)
