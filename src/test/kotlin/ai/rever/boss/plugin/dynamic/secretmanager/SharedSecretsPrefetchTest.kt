package ai.rever.boss.plugin.dynamic.secretmanager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scroll prefetch decision, which used to live inside a `LaunchedEffect` where nothing could
 * reach it.
 *
 * It was extracted because the bug it had was only findable by reasoning about `snapshotFlow`
 * dedup in prose: a section showing a couple of shares out of a large vault prefetched on its
 * first frame with nothing scrolled, and then again every time the spinner appearing and
 * disappearing changed the last visible index - roughly 200 sequential RPCs from one tab switch,
 * each materialising 50 decrypted passwords. `MAX_AUTO_PAGES` did not bound it, because that cap
 * governs the ViewModel's first-load auto-continue and every one of these was a separate
 * `loadMore()`.
 */
class SharedSecretsPrefetchTest {
    @Test
    fun `a list that fits on screen never prefetches`() {
        // The whole finding. Two shares, both visible, nothing scrolled: "near the end" is true
        // by arithmetic (1 >= 2 - 3) and meaningless in fact.
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = 1, visibleItemCount = 3, renderedItemCount = 3),
                loadedCount = 2,
                hasMore = true,
                isLoadingMore = false,
            ),
        )
    }

    @Test
    fun `a scrolled list near its end prefetches`() {
        // The case the trigger is actually for: 50 loaded, 10 on screen, scrolled to row 48.
        assertTrue(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = 48, visibleItemCount = 10, renderedItemCount = 51),
                loadedCount = 50,
                hasMore = true,
                isLoadingMore = false,
            ),
        )
    }

    @Test
    fun `a scrolled list far from its end does not prefetch`() {
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = 12, visibleItemCount = 10, renderedItemCount = 51),
                loadedCount = 50,
                hasMore = true,
                isLoadingMore = false,
            ),
        )
    }

    @Test
    fun `nothing prefetches while a page is already in flight`() {
        // Without this the spinner item's own index change re-triggers the fetch it belongs to.
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = 48, visibleItemCount = 10, renderedItemCount = 51),
                loadedCount = 50,
                hasMore = true,
                isLoadingMore = true,
            ),
        )
    }

    @Test
    fun `nothing prefetches once the server says there is no more`() {
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = 48, visibleItemCount = 10, renderedItemCount = 51),
                loadedCount = 50,
                hasMore = false,
                isLoadingMore = false,
            ),
        )
    }

    @Test
    fun `an empty list does not prefetch`() {
        // The empty state has its own control; this list is not rendered at all.
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = null, visibleItemCount = 0, renderedItemCount = 0),
                loadedCount = 0,
                hasMore = true,
                isLoadingMore = false,
            ),
        )
    }

    @Test
    fun `a list measured before layout does not prefetch`() {
        // First composition: layoutInfo is empty, so there is no last visible index to compare.
        assertFalse(
            shouldPrefetchMore(
                window = PrefetchWindow(lastVisibleIndex = null, visibleItemCount = 0, renderedItemCount = 51),
                loadedCount = 50,
                hasMore = true,
                isLoadingMore = false,
            ),
        )
    }
}
