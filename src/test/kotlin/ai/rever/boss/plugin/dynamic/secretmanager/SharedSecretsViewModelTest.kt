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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    ) : SecretDataProvider {
        val offsets = mutableListOf<Int>()

        /** Held open to keep a fetch in flight while the test does something else. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun getUserSecretsWithSharingInfo(
            limit: Int,
            offset: Int,
        ): Result<PaginatedSecretsWithSharingData> {
            gate?.await()
            offsets += offset
            failWith?.let { return Result.failure(IllegalStateException(it)) }
            val page = all.drop(offset).take(limit)
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
}
