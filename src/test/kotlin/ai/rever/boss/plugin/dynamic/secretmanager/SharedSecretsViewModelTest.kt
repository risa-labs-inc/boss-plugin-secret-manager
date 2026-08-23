package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.api.SecretShareData
import ai.rever.boss.plugin.api.ShareSecretRequestData
import ai.rever.boss.plugin.api.UnshareSecretRequestData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "Shared with me" section's two non-obvious behaviours: which rows belong to it, and how
 * it pages.
 *
 * Both are new. The panel this section replaces listed *everything* the caller could read and
 * filtered nothing, so neither rule existed to get wrong before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedSecretsViewModelTest {
    @Test
    fun `only secrets actually shared with me appear in the section`() =
        runTest {
            // access_level is the partition key, and these are the three values the RPC's
            // UNION assigns: 'owner' (source 1), 'org' (source 4), and the share's own level
            // (sources 2, 3, 5). Only the last belongs here - the other two are already in the
            // section next door, which is the duplication that merging the two plugins removed.
            val provider =
                FakeSharingProvider(
                    listOf(
                        entry("1", "mine.com", accessLevel = "owner", isOwner = true),
                        entry("2", "org.com", accessLevel = "org", isOwner = true),
                        entry("3", "theirs.com", accessLevel = "read", isOwner = false),
                        entry("4", "alsotheirs.com", accessLevel = "write", isOwner = false),
                    ),
                )
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(listOf("3", "4"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `an organisation secret created by a colleague is not a share`() =
        runTest {
            // The trap this test exists for: source 4 returns is_owner = (s.user_id =
            // auth.uid()), so a colleague's organisation secret arrives with isOwner = false
            // while nobody shared it with anyone. Splitting on isOwner instead of accessLevel
            // files it here and tells the user someone shared it with them.
            val provider =
                FakeSharingProvider(
                    listOf(entry("1", "colleague-org.com", accessLevel = "org", isOwner = false)),
                )
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(emptyList(), viewModel.state.value.shared.map { it.id })
            // This case asserts an absence, so it would pass just as well against a load that
            // never ran. Pin that one actually did.
            assertEquals(listOf(0), provider.offsets)
            assertTrue(viewModel.state.value.hasLoadedOnce)
        }

    @Test
    fun `an unrecognised access level is treated as a share`() =
        runTest {
            // Fails safe: a new UNION source added server-side surfaces in the read-only
            // section, where no control can act on it, rather than joining the list that
            // offers Edit and Delete.
            val provider =
                FakeSharingProvider(listOf(entry("1", "new.com", accessLevel = "some-new-route", isOwner = false)))
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `a first page with no shares keeps scanning`() =
        runTest {
            // A page is 50 of EVERYTHING readable, so someone with 50 of their own secrets and
            // one shared with them gets a first page that filters down to nothing. Stopping
            // there reports "nothing shared with you" while the server still has some.
            val own = (1..SharedSecretsViewModel.PAGE_SIZE).map { entry("own-$it", "mine.com", "owner", true) }
            val provider = FakeSharingProvider(own + entry("shared-1", "theirs.com", "read", false))
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(listOf("shared-1"), viewModel.state.value.shared.map { it.id })
            // Two requests, and the second one starts where the first ended: the offset
            // addresses the server's full set, so advancing by the *filtered* count would
            // re-read the same page forever.
            assertEquals(listOf(0, SharedSecretsViewModel.PAGE_SIZE), provider.offsets)
            assertEquals(SharedSecretsViewModel.PAGE_SIZE + 1, viewModel.state.value.rowsScanned)
        }

    @Test
    fun `the scan stops at the page cap and leaves more to load`() =
        runTest {
            // Bounded so one section switch cannot turn into an unbounded scan of a large
            // vault. The user gets a "Keep looking" control instead.
            val pages = SharedSecretsViewModel.MAX_AUTO_PAGES + 2
            val own =
                (1..SharedSecretsViewModel.PAGE_SIZE * pages)
                    .map { entry("own-$it", "mine.com", "owner", true) }
            val provider = FakeSharingProvider(own)
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(SharedSecretsViewModel.MAX_AUTO_PAGES, provider.offsets.size)
            assertTrue(viewModel.state.value.shared.isEmpty())
            assertTrue(viewModel.state.value.hasMore, "the cap must leave the scan resumable")
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `loadMore resumes where the capped scan stopped`() =
        runTest {
            val own =
                (1..SharedSecretsViewModel.PAGE_SIZE * SharedSecretsViewModel.MAX_AUTO_PAGES)
                    .map { entry("own-$it", "mine.com", "owner", true) }
            val provider = FakeSharingProvider(own + entry("shared-1", "theirs.com", "read", false))
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.shared.isEmpty(), "precondition: the cap was reached")

            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(listOf("shared-1"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `an empty page ends the scan even when the server says there is more`() =
        runTest {
            // hasMore is derived host-side from `size >= limit`, so it should never be true for
            // an empty page - but a scan that trusts it alone spins here if it ever is.
            val provider = FakeSharingProvider(emptyList(), hasMoreOverride = true)
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertEquals(1, provider.offsets.size, "an empty page was scanned more than once")
            assertFalse(viewModel.state.value.hasMore)
        }

    @Test
    fun `ensureLoaded fetches once and refresh refetches`() =
        runTest {
            // Lazily loaded on first entry into the section: a fetch per panel open would be a
            // second secrets RPC for a section most opens never reach.
            val provider = FakeSharingProvider(listOf(entry("1", "theirs.com", "read", false)))
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()
            viewModel.ensureLoaded()
            advanceUntilIdle()
            assertEquals(1, provider.offsets.size, "ensureLoaded refetched")

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(2, provider.offsets.size, "refresh did not refetch")
            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `the filter matches website or username`() =
        runTest {
            val provider =
                FakeSharingProvider(
                    listOf(
                        entry("1", "stripe.com", "read", false, username = "ops"),
                        entry("2", "github.com", "read", false, username = "deploy"),
                    ),
                )
            val viewModel = SharedSecretsViewModel(provider, this)
            viewModel.ensureLoaded()
            advanceUntilIdle()

            viewModel.search("STRIPE")
            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id })

            viewModel.search("deploy")
            assertEquals(listOf("2"), viewModel.state.value.shared.map { it.id })

            viewModel.search("")
            assertEquals(listOf("1", "2"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `a failed load reports the error and stops loading`() =
        runTest {
            val provider = FakeSharingProvider(emptyList(), failWith = "not signed in")
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("not signed in", state.errorMessage)
            assertFalse(state.isLoading)
            // Loaded-once even on failure, or ensureLoaded retries on every recomposition of
            // the section while the failure persists.
            assertTrue(state.hasLoadedOnce)
        }

    @Test
    fun `a missing provider is reported rather than left spinning`() =
        runTest {
            val viewModel = SharedSecretsViewModel(null, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.errorMessage)
            assertFalse(viewModel.state.value.isLoading)
        }

    @Test
    fun `dispose cancels an in-flight scan`() =
        runTest {
            // The load runs on the plugin scope, which outlives the panel, so an auto-continue
            // several round trips deep would otherwise keep this instance - and the decrypted
            // secrets in its state - alive for the plugin's whole lifetime.
            val provider = FakeSharingProvider(listOf(entry("1", "theirs.com", "read", false)))
            val gate = CompletableDeferred<Unit>()
            provider.gate = gate
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isLoading, "precondition: the fetch is in flight")

            viewModel.dispose()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.shared.isEmpty(), "a cancelled scan seated its result")
        }

    @Test
    fun `dispose clears what a completed load left in state`() =
        runTest {
            // Cancelling is not the whole job. A load that finished before the panel went away
            // leaves `allShared` populated, and every entry in it carries a decrypted password.
            val provider = FakeSharingProvider(listOf(entry("1", "theirs.com", "read", false)))
            val viewModel = SharedSecretsViewModel(provider, this)
            viewModel.ensureLoaded()
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.allShared.size, "precondition: the load completed")

            viewModel.dispose()

            assertTrue(viewModel.state.value.allShared.isEmpty(), "dispose left the decrypted list in state")
            assertTrue(viewModel.state.value.shared.isEmpty())
        }

    @Test
    fun `a page that returns after dispose does not seat its result`() =
        runTest {
            // The window cancellation cannot close: it is cooperative and only lands at a
            // suspension point, so a provider call that has already returned runs on to the
            // terminal state update. Staged by disposing from inside the provider, just before
            // it returns - there is no suspension point between there and the update, which is
            // exactly the production race (dispose on the UI thread, scan on the plugin scope).
            lateinit var viewModel: SharedSecretsViewModel
            val provider =
                FakeSharingProvider(
                    listOf(entry("1", "theirs.com", "read", false)),
                    beforeReturning = { viewModel.dispose() },
                )
            viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.allShared.isEmpty(),
                "a load that returned after dispose seated ${viewModel.state.value.allShared.size} decrypted secrets",
            )
        }

    @Test
    fun `loadMore does nothing while a filter is active`() =
        runTest {
            // Deliberate: `shared` is the filtered view and the pages arrive unfiltered, so
            // appending to it mid-filter would show rows the filter excludes. Pinned because it
            // reads like a missing feature rather than a decision.
            val own = (1..SharedSecretsViewModel.PAGE_SIZE).map { entry("own-$it", "mine.com", "owner", true) }
            val provider = FakeSharingProvider(own + entry("shared-1", "theirs.com", "read", false))
            val viewModel = SharedSecretsViewModel(provider, this)
            viewModel.ensureLoaded()
            advanceUntilIdle()
            val pagesSoFar = provider.offsets.size

            viewModel.search("nothing-matches-this")
            viewModel.loadMore()
            advanceUntilIdle()

            assertEquals(pagesSoFar, provider.offsets.size, "loadMore fetched a page while filtering")
        }

    @Test
    fun `a refresh after a failed load shows the list, not the error`() =
        runTest {
            // What this pins is the user-visible property: a transient failure does not leave a
            // full-screen error standing over a list that has since loaded.
            //
            // It does NOT pin the terminal `errorMessage = null` specifically - both entry
            // points also clear on the way in, so either alone satisfies this. That line
            // defends a different case: a cancelled load's failure update landing after a fresh
            // load has already started. Cancellation is cooperative and the writer is the same
            // coroutine, so a single-threaded test dispatcher cannot stage that interleaving.
            // Kept as belt and braces, recorded here as unproven rather than proven.
            val provider = FakeSharingProvider(listOf(entry("1", "theirs.com", "read", false)), failFirstCall = true)
            val viewModel = SharedSecretsViewModel(provider, this)

            viewModel.ensureLoaded()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.errorMessage, "precondition: the first load failed")

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(null, viewModel.state.value.errorMessage)
            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id })
        }

    @Test
    fun `refresh keeps the filter the user typed`() =
        runTest {
            // The panel this came from cleared it on every load, so the header Refresh silently
            // discarded the query with nothing to say so.
            val provider =
                FakeSharingProvider(
                    listOf(
                        entry("1", "stripe.com", "read", false),
                        entry("2", "github.com", "read", false),
                    ),
                )
            val viewModel = SharedSecretsViewModel(provider, this)
            viewModel.ensureLoaded()
            advanceUntilIdle()
            viewModel.search("stripe")
            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id })

            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("stripe", viewModel.state.value.searchQuery, "refresh dropped the filter")
            assertEquals(listOf("1"), viewModel.state.value.shared.map { it.id }, "the filter was not re-applied")
        }

    @Test
    fun `a copied secret is wiped from the clipboard`() =
        runTest {
            // The managed list has cleared after 45s since it shipped; the panel this section
            // came from never did. One panel, two policies for the same data, would be an
            // oversight rather than a decision.
            val clipboard = FakeClipboard()
            val viewModel = SharedSecretsViewModel(FakeSharingProvider(emptyList()), this)

            viewModel.copySecretToClipboard("sk-shared-key", clipboard)
            assertEquals("sk-shared-key", clipboard.getText()?.text)

            advanceTimeBy(46_000)
            assertEquals("", clipboard.getText()?.text, "the credential is still on the clipboard")
        }

    @Test
    fun `re-copying the same value gets a full window, not the remainder of the first`() =
        runTest {
            // What the generation token is actually for. The value check alone covers a re-copy
            // of a DIFFERENT value - the first timer fires, sees something else on the
            // clipboard and leaves it - so testing it that way proves nothing about the token.
            // Copying the same value twice is the case only the token survives: without it the
            // first timer wipes the second copy 2s in instead of 45s, and the user pastes
            // nothing.
            val clipboard = FakeClipboard()
            val viewModel = SharedSecretsViewModel(FakeSharingProvider(emptyList()), this)

            viewModel.copySecretToClipboard("sk-same-key", clipboard)
            advanceTimeBy(44_000)
            viewModel.copySecretToClipboard("sk-same-key", clipboard)
            advanceTimeBy(2_000)

            assertEquals(
                "sk-same-key",
                clipboard.getText()?.text,
                "the first copy's timer wiped the second copy early",
            )

            advanceTimeBy(44_000)
            assertEquals("", clipboard.getText()?.text, "the second copy was never wiped")
        }

    @Test
    fun `a wipe does not clobber something else copied since`() =
        runTest {
            val clipboard = FakeClipboard()
            val viewModel = SharedSecretsViewModel(FakeSharingProvider(emptyList()), this)

            viewModel.copySecretToClipboard("secret", clipboard)
            clipboard.setText(AnnotatedString("a shopping list"))
            advanceTimeBy(46_000)

            assertEquals("a shopping list", clipboard.getText()?.text, "the wipe cleared an unrelated copy")
        }

    @Test
    fun `dispose does not cancel a pending clipboard wipe`() =
        runTest {
            // Deliberate, and the same call SecretManagerViewModel makes: the wipe is meant to
            // outlive the panel. Cancelling it would leave a credential on the system clipboard
            // indefinitely - an unbounded OS-level exposure traded for a bounded in-memory one.
            val clipboard = FakeClipboard()
            val viewModel = SharedSecretsViewModel(FakeSharingProvider(emptyList()), this)

            viewModel.copySecretToClipboard("sk-shared-key", clipboard)
            viewModel.dispose()
            advanceTimeBy(46_000)

            assertEquals("", clipboard.getText()?.text, "dispose cancelled the wipe")
        }

    private fun entry(
        id: String,
        website: String,
        accessLevel: String,
        isOwner: Boolean,
        username: String = "user",
    ) = SecretEntryWithSharingData(
        id = id,
        website = website,
        username = username,
        password = "pw-$id",
        createdAt = "2026-01-01",
        updatedAt = "2026-01-01",
        isOwner = isOwner,
        sharedByEmail = if (isOwner) null else "anu@example.com",
        accessLevel = accessLevel,
    )

    /**
     * Serves [all] in pages and records every offset asked for, which is what makes the
     * pagination arithmetic observable. Only the one member this ViewModel calls does
     * anything.
     */
    private class FakeSharingProvider(
        private val all: List<SecretEntryWithSharingData>,
        private val failWith: String? = null,
        /** Forces `hasMore`, to reach the shape the host should never send. */
        private val hasMoreOverride: Boolean? = null,
        /** Fails only the first call, so a later load can be observed recovering. */
        private val failFirstCall: Boolean = false,
        /**
         * Run just before a successful page is returned.
         *
         * The only way to stage "cancelled while running non-suspending code" on a
         * single-threaded test dispatcher: there is no suspension point between this and the
         * ViewModel's terminal state update, so a `dispose()` here lands in the same window the
         * production race opens.
         */
        private val beforeReturning: (() -> Unit)? = null,
    ) : SecretDataProvider {
        val offsets = mutableListOf<Int>()

        /** Held open to keep a fetch in flight while the test does something else. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithSharingData> {
            gate?.await()
            val firstCall = offsets.isEmpty()
            offsets += offset
            failWith?.let { return Result.failure(IllegalStateException(it)) }
            if (failFirstCall && firstCall) return Result.failure(IllegalStateException("transient"))
            val page = all.drop(offset).take(limit)
            beforeReturning?.invoke()
            return Result.success(
                PaginatedSecretsWithSharingData(
                    data = page,
                    hasMore = hasMoreOverride ?: (offset + page.size < all.size),
                ),
            )
        }

        override suspend fun getUserSecrets(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> = Result.failure(UnsupportedOperationException())

        override suspend fun searchSecrets(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsData> = Result.failure(UnsupportedOperationException())

        override suspend fun createSecret(request: CreateSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun updateSecret(request: UpdateSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun deleteSecret(id: String): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getSecretShares(secretId: String): Result<List<SecretShareData>> =
            Result.failure(UnsupportedOperationException())

        override suspend fun shareSecret(request: ShareSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())

        override suspend fun unshareSecret(request: UnshareSecretRequestData): Result<Unit> =
            Result.failure(UnsupportedOperationException())
    }

    /**
     * Backing field deliberately not called `text`: a `var text: AnnotatedString?` collides with
     * the interface's own `getText`/`setText` JVM signatures and does not compile.
     */
    private class FakeClipboard : ClipboardManager {
        private var stored: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            stored = annotatedString
        }

        override fun getText(): AnnotatedString? = stored
    }
}
