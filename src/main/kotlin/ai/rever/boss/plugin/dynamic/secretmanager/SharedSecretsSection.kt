package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "Shared with me" section: what other people have shared with the signed-in user.
 *
 * Read-only by design. There is no edit, delete or re-share control anywhere below this
 * point - management lives in the other section, on secrets the caller owns. The panel's
 * own header, background and padding are supplied by [SecretManagerContent], so this starts
 * at the filter field.
 */
@Composable
internal fun SharedSecretsSection(
    state: SharedSecretsState,
    listState: LazyListState,
    onSearch: (String) -> Unit,
    onToggleMetadata: (String) -> Unit,
    onCopySecret: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PanelSearchField(
            query = state.searchQuery,
            onQueryChange = onSearch,
            placeholder = "Filter shared secrets",
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        Text(
            sharedSecretsSummary(state),
            color = BossThemeColors.TextSecondary,
            style = SecretPanelType.meta,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // A failed *continuation* must not replace rows that are already on screen, so the error
        // is a banner over the retained list and only takes the whole section when there is
        // nothing to show. `SecretsSection` still does the latter unconditionally; that is worth
        // fixing too, but changing it here alone would put two behaviours in one panel.
        val errorMessage = state.errorMessage
        if (errorMessage != null && state.shared.isNotEmpty()) {
            SharedSecretsErrorBanner(message = errorMessage, onDismiss = onDismissError)
        }

        when {
            // Also true on the very first frame: ensureLoaded() runs from a LaunchedEffect after
            // the first composition, so without the hasLoadedOnce half the section opens on
            // "Nothing shared with you - none in the 0 secrets scanned so far" with a button,
            // then replaces it. Nothing had been checked at that point.
            state.isLoading || !state.hasLoadedOnce -> SharedSecretsLoadingView()
            errorMessage != null && state.shared.isEmpty() ->
                SharedSecretsErrorView(
                    message = errorMessage,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                )
            state.shared.isEmpty() -> SharedSecretsEmptyView(state = state, onLoadMore = onLoadMore)
            else ->
                SharedSecretList(
                    secrets = state.shared,
                    listState = listState,
                    expandedSecretIds = state.expandedSecretIds,
                    onToggleMetadata = onToggleMetadata,
                    onCopySecret = onCopySecret,
                    onLoadMore = onLoadMore,
                    isLoadingMore = state.isLoadingMore,
                    hasMore = state.hasMore,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

/**
 * What the prefetch decision reads off the layout. A value class rather than four parameters so
 * `snapshotFlow` emits one comparable thing and dedupes on all of it together.
 */
internal data class PrefetchWindow(
    val lastVisibleIndex: Int?,
    val visibleItemCount: Int,
    /** Items the LazyColumn is currently rendering - shares plus the spinner and footer rows. */
    val renderedItemCount: Int,
)

/**
 * Whether scrolling near the end should fetch the next page.
 *
 * **The overflow test is the point.** Without it a section showing 2 shares out of a
 * 10,000-secret vault prefetches on its very first frame with nothing scrolled, because
 * `lastVisibleIndex (1) >= loadedCount (2) - 3` is true - and then keeps going: the spinner
 * appearing and disappearing changes the last visible index, so `snapshotFlow` emits again,
 * `loadMore()` fires again, and one tab switch turns into ~200 sequential RPCs materialising 50
 * decrypted passwords each. `MAX_AUTO_PAGES` does not bound that, because the cap lives in the
 * ViewModel's first-load auto-continue and each of these is a separate user-initiated-looking
 * `loadMore()`.
 *
 * So: prefetch only while the list genuinely scrolls, and let the footer button carry the
 * "there is more, but you have to ask" case. This is a pure function because the alternative
 * was reasoning about `snapshotFlow` dedup in review comments, which is how the loop above
 * survived one round of it.
 */
internal fun shouldPrefetchMore(
    window: PrefetchWindow,
    loadedCount: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
): Boolean {
    if (!hasMore || isLoadingMore || loadedCount == 0) return false
    val lastVisible = window.lastVisibleIndex ?: return false
    // Everything rendered is on screen: nothing to scroll, so a "near the end" test is
    // meaningless and would be true forever.
    if (window.renderedItemCount <= window.visibleItemCount) return false
    return lastVisible >= loadedCount - PREFETCH_THRESHOLD
}

/** How close to the last loaded share counts as "near the end". */
private const val PREFETCH_THRESHOLD = 3

/**
 * The count line. Says how many were *scanned* as well as how many were found, because the
 * two differ here: a page is 50 of everything readable, filtered down to the shares.
 */
private fun sharedSecretsSummary(state: SharedSecretsState): String =
    if (state.searchQuery.isNotBlank()) {
        "${state.shared.size} result${if (state.shared.size != 1) "s" else ""} for '${state.searchQuery}'"
    } else {
        val found = "${state.shared.size} shared with you"
        val scanned = if (state.rowsScanned > 0) " · scanned ${state.rowsScanned}" else ""
        val fetch = state.lastLoadDurationMs?.let { " · last fetch ${formatFetchDuration(it)}" } ?: ""
        found + scanned + fetch
    }

/**
 * [listState] is hoisted, not remembered here, for the same reason `SecretsSection`'s is: this
 * composable leaves composition whenever the other section is on screen - and also on every
 * Refresh, since the loading view replaces it - so a local `rememberLazyListState` would drop
 * the scroll position each time.
 */
@Composable
private fun SharedSecretList(
    secrets: List<SecretEntryWithSharingData>,
    listState: LazyListState,
    expandedSecretIds: Set<String>,
    onToggleMetadata: (String) -> Unit,
    onCopySecret: (String) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    modifier: Modifier = Modifier,
) {
    // Keyed on `listState` alone, this effect never restarts - so the values it reads would be
    // frozen at the composition that launched it. rememberUpdatedState makes it read what it
    // says it reads.
    val currentCount by rememberUpdatedState(secrets.size)
    val currentHasMore by rememberUpdatedState(hasMore)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)

    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            PrefetchWindow(
                lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index,
                visibleItemCount = info.visibleItemsInfo.size,
                renderedItemCount = info.totalItemsCount,
            )
        }.collect { window ->
            if (shouldPrefetchMore(
                    window = window,
                    loadedCount = currentCount,
                    hasMore = currentHasMore,
                    isLoadingMore = currentIsLoadingMore,
                )
            ) {
                onLoadMore()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig(),
                ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        items(items = secrets, key = { it.id }) { secret ->
            SharedSecretCard(
                secret = secret,
                isMetadataExpanded = expandedSecretIds.contains(secret.id),
                onToggleMetadata = { onToggleMetadata(secret.id) },
                onCopySecret = onCopySecret,
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = BossThemeColors.AccentColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // An explicit control, not a fallback nobody reaches. Auto-prefetch deliberately does
        // not fire on a list that fits on screen (see shouldPrefetchMore), so for a handful of
        // shares in a large vault this button is the only way on - and when the list does
        // scroll, it is also the recovery from a prefetch that stalled because the last visible
        // index stopped changing.
        if (hasMore && !isLoadingMore && secrets.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = onLoadMore) {
                        Text(
                            "Keep looking for more",
                            color = BossThemeColors.AccentColor,
                            style = SecretPanelType.meta,
                        )
                    }
                }
            }
        }

        if (!hasMore && secrets.isNotEmpty()) {
            item {
                Text(
                    "- End of list -",
                    color = BossThemeColors.TextSecondary,
                    style = SecretPanelType.meta,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/**
 * One shared secret. The password is never rendered, revealed or copyable here: a share can
 * carry read-only access, and this section deliberately offers no control that would need it.
 * An API key is the exception the panel already made - its value IS what you do with it, so
 * that one stays copyable, as it was in the panel this replaces.
 */
@Composable
private fun SharedSecretCard(
    secret: SecretEntryWithSharingData,
    isMetadataExpanded: Boolean,
    onToggleMetadata: () -> Unit,
    onCopySecret: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isApiKey = secret.tags.contains(ProviderCredentialStore.TAG_API_KEY)

    BossCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (isApiKey) Icons.Default.Api else Icons.Default.Language,
                            contentDescription = if (isApiKey) "Service" else "Website",
                            tint = if (isApiKey) BossThemeColors.WarningColor else BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = secret.website,
                            color = BossThemeColors.TextPrimary,
                            style = SecretPanelType.bodyStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (isApiKey) Icons.Default.Key else Icons.Default.Person,
                            contentDescription = if (isApiKey) "Key Name" else "Username",
                            tint = BossThemeColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = secret.username,
                            color = BossThemeColors.TextSecondary,
                            style = SecretPanelType.meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    if (isApiKey) {
                        SharedSecretBadge(
                            // Upper-cased like the access level directly below it: both are
                            // `label`, and half a column in title case is not a second register.
                            label = "API KEY",
                            icon = Icons.Default.Key,
                            color = BossThemeColors.WarningColor,
                        )
                    }

                    // The access level alone. Every entry in this section is a share - the tab
                    // it sits under says so - and the useful fact is what the share lets you
                    // do. "Shared · read" spent the widest chip in the card restating the tab.
                    SharedSecretBadge(
                        label = secret.accessLevel.ifBlank { "shared" }.uppercase(),
                        icon = Icons.Default.Share,
                        color = BossThemeColors.AccentColor,
                    )
                }
            }

            Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)

            if (isApiKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        // Through the ViewModel, so the value is wiped from the clipboard again
                        // on the same 45s timer the managed list uses.
                        onClick = { onCopySecret(secret.password) },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossThemeColors.WarningColor,
                                contentColor = BossThemeColors.TextPrimary,
                            ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Icon(Icons.Default.Key, contentDescription = "Copy API key", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy API key", style = SecretPanelType.meta)
                    }
                    QuietCopyButton(
                        label = "Copy key name",
                        onClick = { scope.launch { clipboardManager.setText(AnnotatedString(secret.username)) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                QuietCopyButton(
                    label = "Copy username",
                    onClick = { scope.launch { clipboardManager.setText(AnnotatedString(secret.username)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val sharedByEmail = secret.sharedByEmail
            if (sharedByEmail != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Shared by",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "Shared by $sharedByEmail",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.meta,
                    )
                }
            }

            if (secret.tags.isNotEmpty() || secret.notes != null || secret.expirationDate != null) {
                // Same treatment as the managed section's card, deliberately: the two lists
                // sit one tab apart, so a disclosure that is green and full-width in one and
                // quiet and left-aligned in the other reads as two different controls.
                Row(
                    modifier =
                        Modifier
                            .clickable { onToggleMetadata() }
                            .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isMetadataExpanded) "Hide details" else "Show details",
                        color = BossThemeColors.TextSecondary,
                        style = SecretPanelType.meta,
                    )
                    Icon(
                        if (isMetadataExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isMetadataExpanded) "Hide" else "Show",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }

                if (isMetadataExpanded) {
                    SharedSecretDetails(secret)
                }
            }
        }
    }
}

/**
 * Copy-to-clipboard for a name, which is not a credential and should not look like one.
 *
 * This was a full-width `Button` with an accent label on a dark fill - the shape the eye reads
 * as the card's primary action, repeated on every card, for the least consequential thing on it.
 * An outline says "control" without competing with the API-key copy above it, which is the one
 * button in this section that genuinely is primary: in a read-only list it is the only route to
 * the value.
 */
@Composable
private fun QuietCopyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, BossThemeColors.BorderColor),
        colors =
            ButtonDefaults.outlinedButtonColors(
                backgroundColor = Color.Transparent,
                contentColor = BossThemeColors.TextSecondary,
            ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // null, not `label`: the Text beside it already carries that, and a described icon
        // makes a screen reader say it twice.
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = SecretPanelType.meta)
    }
}

/**
 * A tinted chip, not a filled pill.
 *
 * These were solid accent and solid warning at full strength, one on every card, so the loudest
 * thing in the list was a label that said the same word on every row - louder than the name of
 * the secret it belonged to. A fill is the design system's "signal", which is worth spending on
 * something that changes between rows. The 12% wash keeps the colour as the category and gives
 * the glyph and the text the readable weight.
 */
@Composable
private fun SharedSecretBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Row(
            // Asymmetric on purpose: `label`'s 1.5sp tracking is applied after the final glyph
            // as well, so 6dp on both sides renders as 6 left and 7.5 right.
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = label,
                color = color,
                style = SecretPanelType.label,
            )
        }
    }
}

@Composable
private fun SharedSecretDetails(secret: SecretEntryWithSharingData) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (secret.tags.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Label,
                    contentDescription = "Tags",
                    tint = BossThemeColors.AccentColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(secret.tags.joinToString(", "), color = BossThemeColors.TextPrimary, style = SecretPanelType.meta)
            }
        }

        val notes = secret.notes
        if (notes != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Notes,
                    contentDescription = "Notes",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                )
                Text(notes, color = BossThemeColors.TextSecondary, style = SecretPanelType.meta)
            }
        }

        val expirationDate = secret.expirationDate
        if (expirationDate != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = "Expires",
                    tint = BossThemeColors.WarningColor,
                    modifier = Modifier.size(14.dp),
                )
                Text("Expires: $expirationDate", color = BossThemeColors.WarningColor, style = SecretPanelType.meta)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = "Created",
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Text("Created: ${secret.createdAt}", color = BossThemeColors.TextSecondary, style = SecretPanelType.caption)
        }
    }
}

@Composable
private fun SharedSecretsLoadingView() {
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = BossThemeColors.AccentColor)
            Text(
                if (elapsedSeconds < 3) {
                    "Looking for shared secrets..."
                } else {
                    "Looking for shared secrets... ${elapsedSeconds}s"
                },
                color = BossThemeColors.TextSecondary,
                style = SecretPanelType.bodyStrong,
            )
            if (elapsedSeconds >= 10) {
                Text(
                    "Still waiting on the server - the network may be slow",
                    color = BossThemeColors.TextSecondary.copy(alpha = 0.6f),
                    style = SecretPanelType.caption,
                )
            }
        }
    }
}

/** The error over a list that still has rows in it - a banner, not a replacement. */
@Composable
private fun SharedSecretsErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .padding(bottom = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            color = BossThemeColors.ErrorColor,
            style = SecretPanelType.meta,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = BossThemeColors.TextSecondary, style = SecretPanelType.meta)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SharedSecretsErrorView(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("Error", color = BossThemeColors.ErrorColor, style = SecretPanelType.title)
            Text(message, color = BossThemeColors.TextSecondary, style = SecretPanelType.bodyStrong)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.AccentColor),
                ) {
                    Text("Retry", color = BossThemeColors.TextPrimary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = BossThemeColors.TextSecondary)
                }
            }
        }
    }
}

/**
 * Empty state. Distinguishes "nothing is shared with you" from "nothing shared in the first N
 * secrets we scanned", because the second one has a next step and the first one does not.
 *
 * `BossEmptyState` paints the icon, message and description, so this only supplies the words and
 * the one control it needs. Its own empty state used to hand-roll the same three elements at
 * different sizes to the one the rest of BOSS uses.
 */
@Composable
private fun SharedSecretsEmptyView(
    state: SharedSecretsState,
    onLoadMore: () -> Unit,
) {
    // The Box is what centres this in the section; `BossEmptyState` handles its own internal
    // spacing but not that. The Column is only here for the one branch with a second child.
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.searchQuery.isNotBlank() ->
                BossEmptyState(
                    icon = Icons.Default.Search,
                    message = "No results",
                    description = "Try a different filter",
                )

            state.hasMore ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BossEmptyState(
                        icon = Icons.Default.Share,
                        message = "Nothing shared with you yet",
                        description = "None in the ${state.rowsScanned} secrets scanned so far",
                    )
                    TextButton(onClick = onLoadMore, enabled = !state.isLoadingMore) {
                        Text(
                            if (state.isLoadingMore) "Scanning" else "Keep looking",
                            color = BossThemeColors.AccentColor,
                            style = SecretPanelType.body,
                        )
                    }
                }

            else ->
                BossEmptyState(
                    icon = Icons.Default.Share,
                    message = "Nothing shared with you",
                    description = "Secrets other people share with you show up here",
                )
        }
    }
}

private fun formatFetchDuration(ms: Long): String =
    when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
