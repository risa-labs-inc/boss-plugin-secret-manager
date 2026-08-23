package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossThemeColors
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSearch: (String) -> Unit,
    onToggleMetadata: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SharedSecretSearchBar(
            query = state.searchQuery,
            onQueryChange = onSearch,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        )

        Text(
            sharedSecretsSummary(state),
            color = BossThemeColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        when {
            state.isLoading -> SharedSecretsLoadingView()
            state.errorMessage != null ->
                SharedSecretsErrorView(
                    message = state.errorMessage,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                )
            state.shared.isEmpty() -> SharedSecretsEmptyView(state = state, onLoadMore = onLoadMore)
            else ->
                SharedSecretList(
                    secrets = state.shared,
                    expandedSecretIds = state.expandedSecretIds,
                    onToggleMetadata = onToggleMetadata,
                    onLoadMore = onLoadMore,
                    isLoadingMore = state.isLoadingMore,
                    hasMore = state.hasMore,
                    modifier = Modifier.weight(1f),
                )
        }
    }
}

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

@Composable
private fun SharedSecretSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            modifier
                .height(36.dp)
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(6.dp))
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
        cursorBrush = SolidColor(BossThemeColors.SuccessColor),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = BossThemeColors.TextSecondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Filter by website or username...",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    innerTextField()
                }
            }
        },
    )
}

@Composable
private fun SharedSecretList(
    secrets: List<SecretEntryWithSharingData>,
    expandedSecretIds: Set<String>,
    onToggleMetadata: (String) -> Unit,
    onLoadMore: () -> Unit,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= secrets.size - 3 &&
                    hasMore &&
                    !isLoadingMore
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
            )
        }

        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = BossThemeColors.SuccessColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        if (!hasMore && secrets.isNotEmpty()) {
            item {
                Text(
                    "- End of list -",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
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
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val isApiKey = secret.tags.contains("api_key")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
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
                            fontSize = 14.sp,
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
                            label = "API Key",
                            icon = Icons.Default.Key,
                            color = BossThemeColors.WarningColor,
                        )
                    }

                    // The access level, not an Owner/Shared distinction: every entry in this
                    // section is a share, so the useful fact is what the share lets you do.
                    SharedSecretBadge(
                        label = "Shared · ${secret.accessLevel}",
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
                        onClick = { scope.launch { clipboardManager.setText(AnnotatedString(secret.password)) } },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossThemeColors.WarningColor,
                                contentColor = BossThemeColors.TextPrimary,
                            ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Icon(Icons.Default.Key, contentDescription = "Copy API Key", modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy API Key", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { scope.launch { clipboardManager.setText(AnnotatedString(secret.username)) } },
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossThemeColors.BackgroundColor,
                                contentColor = BossThemeColors.AccentColor,
                            ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Key Name",
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Copy Name", fontSize = 12.sp)
                    }
                }
            } else {
                Button(
                    onClick = { scope.launch { clipboardManager.setText(AnnotatedString(secret.username)) } },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossThemeColors.BackgroundColor,
                            contentColor = BossThemeColors.AccentColor,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Username",
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Copy Username", fontSize = 12.sp)
                }
            }

            val sharedByEmail = secret.sharedByEmail
            if (sharedByEmail != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Shared by",
                        tint = BossThemeColors.AccentColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "Shared by: $sharedByEmail",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            if (secret.tags.isNotEmpty() || secret.notes != null || secret.expirationDate != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMetadata() }
                            .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isMetadataExpanded) "Hide Details" else "Show Details",
                        color = BossThemeColors.SuccessColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        if (isMetadataExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isMetadataExpanded) "Hide" else "Show",
                        tint = BossThemeColors.SuccessColor,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (isMetadataExpanded) {
                    SharedSecretDetails(secret)
                }
            }
        }
    }
}

@Composable
private fun SharedSecretBadge(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
) {
    Surface(shape = RoundedCornerShape(4.dp), color = color) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = BossThemeColors.TextPrimary,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                color = BossThemeColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
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
                    tint = BossThemeColors.SuccessColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(secret.tags.joinToString(", "), color = BossThemeColors.TextPrimary, fontSize = 12.sp)
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
                Text(notes, color = BossThemeColors.TextSecondary, fontSize = 12.sp)
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
                Text("Expires: $expirationDate", color = BossThemeColors.WarningColor, fontSize = 12.sp)
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
            Text("Created: ${secret.createdAt}", color = BossThemeColors.TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SharedSecretsLoadingView() {
    var elapsedSeconds by remember { mutableStateOf(0) }
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
            CircularProgressIndicator(color = BossThemeColors.SuccessColor)
            Text(
                if (elapsedSeconds < 3) {
                    "Looking for shared secrets..."
                } else {
                    "Looking for shared secrets... ${elapsedSeconds}s"
                },
                color = BossThemeColors.TextSecondary,
                fontSize = 14.sp,
            )
            if (elapsedSeconds >= 10) {
                Text(
                    "Still waiting on the server - the network may be slow",
                    color = BossThemeColors.TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
        }
    }
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
            Text("Error", color = BossThemeColors.ErrorColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(message, color = BossThemeColors.TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor),
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
 * Empty state. Distinguishes "nothing is shared with you" from "nothing shared in the first
 * N secrets we scanned", because the second one has a next step and the first one does not.
 */
@Composable
private fun SharedSecretsEmptyView(
    state: SharedSecretsState,
    onLoadMore: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (state.searchQuery.isNotBlank()) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "No results",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    "No results found",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Try a different filter", color = BossThemeColors.TextSecondary, fontSize = 14.sp)
            } else {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Nothing shared",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(64.dp),
                )
                Text(
                    "Nothing shared with you",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.hasMore) {
                    Text(
                        "None in the ${state.rowsScanned} secrets scanned so far.",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                    Button(
                        onClick = onLoadMore,
                        enabled = !state.isLoadingMore,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor),
                    ) {
                        Text(
                            if (state.isLoadingMore) "Scanning..." else "Keep looking",
                            color = BossThemeColors.TextPrimary,
                        )
                    }
                } else {
                    Text(
                        "Secrets other people share with you show up here.",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

private fun formatFetchDuration(ms: Long): String =
    when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }
