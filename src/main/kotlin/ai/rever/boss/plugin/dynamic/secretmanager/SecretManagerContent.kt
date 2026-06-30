package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope

/**
 * Secret Manager panel content (Dynamic Plugin).
 *
 * Displays and manages user secrets with CRUD and sharing operations.
 * Also supports Plugin Store API key management for admin/plugin_admin users.
 * UI matches the bundled plugin's Card-based design.
 */
@Composable
fun SecretManagerContent(
    secretDataProvider: SecretDataProvider?,
    supabaseDataProvider: SupabaseDataProvider?,
    pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?,
    scope: CoroutineScope
) {
    val viewModel = remember(secretDataProvider, supabaseDataProvider, pluginStoreApiKeyProvider, scope) {
        SecretManagerViewModel(secretDataProvider, supabaseDataProvider, pluginStoreApiKeyProvider, scope)
    }

    BossTheme {
        if (!viewModel.isAvailable()) {
            NoProviderMessage()
        } else {
            SecretManagerView(viewModel)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
        viewModel.checkApiKeyPermission()
    }
}

@Composable
private fun NoProviderMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.SurfaceColor)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Secret Manager",
                color = BossThemeColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Secret provider not available",
                color = BossThemeColors.TextSecondary,
                fontSize = 13.sp
            )
            Text(
                "Please ensure the host provides secret management access",
                color = BossThemeColors.TextSecondary.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Main view composable for Secret Manager panel
 */
@Composable
private fun SecretManagerView(viewModel: SecretManagerViewModel) {
    val state = viewModel.state
    val listState = rememberLazyListState()
    var showAddDropdown by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BossThemeColors.SurfaceColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with title and refresh button
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Secret Manager",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Refresh button
                IconButton(
                    onClick = { viewModel.loadSecrets() },
                    enabled = !state.isLoading
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = if (state.isLoading) BossThemeColors.TextSecondary else BossThemeColors.TextPrimary
                    )
                }

                // Add button with dropdown menu
                Box {
                    IconButton(
                        onClick = { showAddDropdown = true },
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                            tint = BossThemeColors.SuccessColor
                        )
                    }

                    DropdownMenu(
                        expanded = showAddDropdown,
                        onDismissRequest = { showAddDropdown = false },
                        modifier = Modifier.background(BossThemeColors.SurfaceColor)
                    ) {
                        // Add Secret option (always visible)
                        DropdownMenuItem(
                            onClick = {
                                showAddDropdown = false
                                viewModel.showCreateDialog()
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text("Add Secret", color = BossThemeColors.TextPrimary, fontSize = 13.sp)
                            }
                        }

                        // Create API Key option (visible for admin/plugin_admin)
                        if (state.canManageApiKeys) {
                            Divider(color = BossThemeColors.BorderColor)

                            DropdownMenuItem(
                                onClick = {
                                    showAddDropdown = false
                                    viewModel.showCreateApiKeyDialog()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VpnKey,
                                        contentDescription = null,
                                        tint = BossThemeColors.WarningColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Create API Key", color = BossThemeColors.TextPrimary, fontSize = 13.sp)
                                }
                            }

                            DropdownMenuItem(
                                onClick = {
                                    showAddDropdown = false
                                    viewModel.showApiKeysListDialog()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.List,
                                        contentDescription = null,
                                        tint = BossThemeColors.AccentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Manage API Keys", color = BossThemeColors.TextPrimary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Search bar
            SearchBar(
                query = state.searchQuery,
                onQueryChange = { viewModel.searchSecrets(it) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            )

            // Secret count
            Text(
                "${state.secrets.size} secret${if (state.secrets.size != 1) "s" else ""}",
                color = BossThemeColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Content based on state
            when {
                state.isLoading -> {
                    LoadingView()
                }
                state.errorMessage != null -> {
                    ErrorView(
                        message = state.errorMessage,
                        onRetry = { viewModel.loadSecrets() },
                        onDismiss = { viewModel.clearError() }
                    )
                }
                state.secrets.isEmpty() -> {
                    EmptyView(
                        searchQuery = state.searchQuery,
                        onAddSecret = { viewModel.showCreateDialog() }
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .lazyListScrollbar(
                                listState = listState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig()
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.secrets, key = { it.id }) { secret ->
                            SecretCard(
                                secret = secret,
                                isPasswordVisible = state.visiblePasswordIds.contains(secret.id),
                                isExpanded = state.expandedSecretIds.contains(secret.id),
                                onTogglePassword = { viewModel.togglePasswordVisibility(secret.id) },
                                onToggleExpand = { viewModel.toggleMetadataExpanded(secret.id) },
                                onEdit = { viewModel.showEditDialog(secret) },
                                onDelete = { viewModel.showDeleteDialog(secret) },
                                onShare = { viewModel.showShareDialog(secret) }
                            )
                        }

                        // Load more trigger
                        if (state.hasMore && !state.isLoadingMore) {
                            item {
                                LaunchedEffect(Unit) {
                                    viewModel.loadMoreSecrets()
                                }
                            }
                        }

                        // Loading more indicator
                        if (state.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = BossThemeColors.SuccessColor,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (state.showCreateDialog) {
        CreateSecretDialog(
            onConfirm = { viewModel.createSecret(it) },
            onDismiss = { viewModel.hideCreateDialog() },
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showEditDialog && state.selectedSecret != null) {
        EditSecretDialog(
            secret = state.selectedSecret,
            onConfirm = { viewModel.updateSecret(it) },
            onDismiss = { viewModel.hideEditDialog() },
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showDeleteDialog && state.selectedSecret != null) {
        DeleteConfirmationDialog(
            secret = state.selectedSecret,
            onConfirm = { viewModel.deleteSecret(state.selectedSecret.id) },
            onDismiss = { viewModel.hideDeleteDialog() },
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showShareDialog && state.selectedSecret != null) {
        ShareSecretDialog(
            secret = state.selectedSecret,
            shares = state.secretShares,
            availableUsers = state.availableUsers,
            availableRoles = state.availableRoles,
            onShare = { viewModel.shareSecret(it) },
            onRevoke = { userId, roleId ->
                viewModel.unshareSecret(state.selectedSecret.id, userId, roleId)
            },
            onDismiss = { viewModel.hideShareDialog() },
            onSearchUsers = { query ->
                if (query.isBlank()) viewModel.loadAvailableUsers()
                else viewModel.searchUsersForSharing(query)
            },
            isLoading = state.isOperationInProgress,
            isLoadingShares = state.isLoadingShares,
            isLoadingUsers = state.isLoadingUsers
        )
    }

    // API Key dialogs
    if (state.showCreateApiKeyDialog) {
        CreateApiKeyDialog(
            onConfirm = { name, scopes, expiresInDays ->
                viewModel.createApiKey(name, scopes, expiresInDays)
            },
            onDismiss = { viewModel.hideCreateApiKeyDialog() },
            isSuccess = state.apiKeyCreatedSuccessfully,
            isLoading = state.isOperationInProgress
        )
    }

    if (state.showApiKeysListDialog) {
        ApiKeysListDialog(
            apiKeys = state.apiKeys,
            onRevoke = { keyId -> viewModel.revokeApiKey(keyId) },
            onDismiss = { viewModel.hideApiKeysListDialog() },
            onCreateNew = {
                viewModel.hideApiKeysListDialog()
                viewModel.showCreateApiKeyDialog()
            },
            isLoading = state.isLoadingApiKeys || state.isOperationInProgress
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(32.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
        cursorBrush = SolidColor(BossThemeColors.SuccessColor),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = BossThemeColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search secrets...",
                            style = MaterialTheme.typography.body2,
                            color = BossThemeColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.TextSecondary
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BossThemeColors.SuccessColor)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading secrets...", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(32.dp)
            )
            Text(message, color = BossThemeColors.TextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                ) {
                    Text("Retry", color = BossThemeColors.TextPrimary, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = BossThemeColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyView(
    searchQuery: String,
    onAddSecret: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = BossThemeColors.TextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                if (searchQuery.isBlank()) "No secrets yet" else "No results found",
                color = BossThemeColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (searchQuery.isBlank()) "Add your first secret to get started"
                else "Try a different search term",
                color = BossThemeColors.TextSecondary,
                fontSize = 12.sp
            )
            if (searchQuery.isBlank()) {
                Button(
                    onClick = onAddSecret,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Secret", color = BossThemeColors.TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SecretCard(
    secret: SecretEntryData,
    isPasswordVisible: Boolean,
    isExpanded: Boolean,
    onTogglePassword: () -> Unit,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Website and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Website
                    Text(
                        text = secret.website,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Username
                    Text(
                        text = secret.username,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Action buttons
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = BossThemeColors.SuccessColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = BossThemeColors.ErrorColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Password field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPasswordVisible) secret.password else "••••••••",
                    color = if (isPasswordVisible) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onTogglePassword, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Tags
            if (secret.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    secret.tags.forEach { tag ->
                        TagBadge(tag)
                    }
                }
            }

            // Expiration warning
            if (secret.expirationDate != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        tint = BossThemeColors.WarningColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Expires: ${secret.expirationDate}",
                        color = BossThemeColors.WarningColor,
                        fontSize = 12.sp
                    )
                }
            }

            // "More" button to show metadata
            val metadata = secret.metadata
            if (metadata != null && metadata.twofaEnabled) {
                Divider(color = BossThemeColors.BorderColor, thickness = 1.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleExpand() }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = BossThemeColors.SuccessColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "2FA Details",
                            color = BossThemeColors.SuccessColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Hide details" else "Show details",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Expanded metadata
                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BossThemeColors.SurfaceColor, RoundedCornerShape(4.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 2FA Type
                        metadata.twofaType?.let { twofaType ->
                            MetadataRow(
                                label = "2FA Type",
                                value = twofaType.uppercase()
                            )
                        }

                        // Recovery codes
                        if (metadata.recoveryCodes.isNotEmpty()) {
                            Text(
                                text = "Recovery Codes:",
                                color = BossThemeColors.TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            metadata.recoveryCodes.forEach { code ->
                                Text(
                                    text = "• $code",
                                    color = BossThemeColors.TextPrimary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Notes
            secret.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Notes:",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = notes,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Tag badge component
 */
@Composable
private fun TagBadge(tag: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BossThemeColors.SuccessColor.copy(alpha = 0.2f)
    ) {
        Text(
            text = tag,
            color = BossThemeColors.SuccessColor,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Metadata row component
 */
@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = BossThemeColors.TextSecondary,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = BossThemeColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ==================== DIALOGS ====================

@Composable
private fun CreateSecretDialog(
    onConfirm: (CreateSecretRequestData) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    var website by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isApiKey by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Add New Secret",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = if (isApiKey) "Service Name" else "Website",
                    placeholder = if (isApiKey) "e.g., OpenAI API" else "e.g., github.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = if (isApiKey) "Key Name" else "Username / Email",
                    placeholder = if (isApiKey) "e.g., production-key" else "e.g., user@example.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (isApiKey) "API Key" else "Password",
                    placeholder = if (isApiKey) "Enter API key" else "Enter password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword }
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes (optional)",
                    placeholder = "Additional notes",
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                // API Key checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isApiKey = !isApiKey }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isApiKey,
                        onCheckedChange = { isApiKey = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BossThemeColors.SuccessColor,
                            uncheckedColor = BossThemeColors.TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "This is an API Key",
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossThemeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                onConfirm(CreateSecretRequestData(
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() },
                                    tags = if (isApiKey) listOf("api_key") else emptyList()
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossThemeColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Create", color = BossThemeColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSecretDialog(
    secret: SecretEntryData,
    onConfirm: (UpdateSecretRequestData) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    var website by remember { mutableStateOf(secret.website) }
    var username by remember { mutableStateOf(secret.username) }
    var password by remember { mutableStateOf(secret.password) }
    var notes by remember { mutableStateOf(secret.notes ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var isApiKey by remember { mutableStateOf(secret.tags.contains("api_key")) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Edit Secret",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = if (isApiKey) "Service Name" else "Website"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = if (isApiKey) "Key Name" else "Username / Email"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (isApiKey) "API Key" else "Password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword }
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes (optional)",
                    singleLine = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                // API Key checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isApiKey = !isApiKey }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isApiKey,
                        onCheckedChange = { isApiKey = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = BossThemeColors.SuccessColor,
                            uncheckedColor = BossThemeColors.TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "This is an API Key",
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossThemeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                // Preserve existing tags, add/remove api_key as needed
                                val updatedTags = if (isApiKey) {
                                    if (secret.tags.contains("api_key")) secret.tags else secret.tags + "api_key"
                                } else {
                                    secret.tags.filter { it != "api_key" }
                                }
                                onConfirm(UpdateSecretRequestData(
                                    secretId = secret.id,
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() },
                                    tags = updatedTags
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = BossThemeColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = BossThemeColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    secret: SecretEntryData,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete Secret?", color = BossThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Are you sure you want to delete this secret?",
                    color = BossThemeColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${secret.website} - ${secret.username}",
                    color = Color(0xFF90CAF9),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This action cannot be undone.",
                    color = BossThemeColors.ErrorColor,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.ErrorColor)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BossThemeColors.TextPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Delete", color = BossThemeColors.TextPrimary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel", color = BossThemeColors.TextSecondary)
            }
        },
        backgroundColor = BossThemeColors.SurfaceColor
    )
}

@Composable
private fun ShareSecretDialog(
    secret: SecretEntryData,
    shares: List<SecretShareData>,
    availableUsers: List<ShareUserRow>,
    availableRoles: List<ShareRoleRow>,
    onShare: (ShareSecretRequestData) -> Unit,
    onRevoke: (userId: String?, roleId: String?) -> Unit,
    onDismiss: () -> Unit,
    onSearchUsers: (String) -> Unit,
    isLoading: Boolean,
    isLoadingShares: Boolean,
    isLoadingUsers: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Users, 1 = Roles

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp).heightIn(max = 500.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Share Secret",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "${secret.website} - ${secret.username}",
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current shares
                if (shares.isNotEmpty() || isLoadingShares) {
                    Text(
                        "Currently shared with:",
                        color = BossThemeColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingShares) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BossThemeColors.SuccessColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                        shares.forEach { share ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        share.sharedWithUserEmail ?: share.sharedWithRoleName ?: "Unknown",
                                        color = BossThemeColors.TextPrimary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        if (share.sharedWithUserId != null) "User" else "Role",
                                        color = BossThemeColors.TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onRevoke(share.sharedWithUserId, share.sharedWithRoleId)
                                    },
                                    modifier = Modifier.size(24.dp),
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Revoke",
                                        tint = BossThemeColors.ErrorColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    backgroundColor = BossThemeColors.BackgroundColor,
                    contentColor = BossThemeColors.SuccessColor
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Users", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Roles", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedTab == 0) {
                    // User search
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            onSearchUsers(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                        cursorBrush = SolidColor(BossThemeColors.SuccessColor),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BossThemeColors.TextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search users by email...",
                                            color = BossThemeColors.TextSecondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // User list
                    if (isLoadingUsers) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = BossThemeColors.SuccessColor,
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        val usersListState = rememberLazyListState()
                        LazyColumn(
                            state = usersListState,
                            modifier = Modifier
                                .height(150.dp)
                                .lazyListScrollbar(
                                    listState = usersListState,
                                    direction = Orientation.Vertical,
                                    config = getPanelScrollbarConfig()
                                )
                        ) {
                            items(availableUsers) { user ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onShare(ShareSecretRequestData(
                                                secretId = secret.id,
                                                targetUserId = user.id
                                            ))
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = BossThemeColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        user.email,
                                        color = BossThemeColors.TextPrimary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Roles list
                    val rolesListState = rememberLazyListState()
                    LazyColumn(
                        state = rolesListState,
                        modifier = Modifier
                            .height(150.dp)
                            .lazyListScrollbar(
                                listState = rolesListState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig()
                            )
                    ) {
                        items(availableRoles) { role ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onShare(ShareSecretRequestData(
                                            secretId = secret.id,
                                            targetRoleId = role.id
                                        ))
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        role.name,
                                        color = BossThemeColors.TextPrimary,
                                        fontSize = 12.sp
                                    )
                                    val description = role.description
                                    if (description != null) {
                                        Text(
                                            description,
                                            color = BossThemeColors.TextSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SurfaceColor)
                    ) {
                        Text("Done", color = BossThemeColors.TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    singleLine: Boolean = true
) {
    Column {
        Text(
            label,
            color = BossThemeColors.TextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                cursorBrush = SolidColor(BossThemeColors.SuccessColor),
                visualTransformation = if (isPassword && !showPassword)
                    PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                color = BossThemeColors.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (isPassword && onTogglePassword != null) {
                IconButton(
                    onClick = onTogglePassword,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle password visibility",
                        tint = BossThemeColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ==================== API KEY DIALOGS ====================

/**
 * Dialog for creating a new Plugin Store API key.
 * The API key is automatically stored as a secret after creation.
 */
@Composable
private fun CreateApiKeyDialog(
    onConfirm: (name: String, scopes: List<String>, expiresInDays: Int?) -> Unit,
    onDismiss: () -> Unit,
    isSuccess: Boolean,
    isLoading: Boolean
) {
    var name by remember { mutableStateOf("") }
    var publishScope by remember { mutableStateOf(true) }
    var versionScope by remember { mutableStateOf(true) }
    var finalizeScope by remember { mutableStateOf(true) }
    var hasExpiration by remember { mutableStateOf(false) }
    var expirationDays by remember { mutableStateOf("90") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = if (isSuccess) BossThemeColors.SuccessColor else BossThemeColors.WarningColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            if (isSuccess) "API Key Created" else "Create API Key",
                            color = BossThemeColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isSuccess) {
                    // Success message
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                            .border(1.dp, BossThemeColors.SuccessColor, RoundedCornerShape(4.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = BossThemeColors.SuccessColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "API Key Securely Stored",
                            color = BossThemeColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Your API key has been automatically saved to your secrets.",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 12.sp
                        )
                        Divider(color = BossThemeColors.BorderColor, modifier = Modifier.padding(vertical = 8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Website: boss_plugin_store_api_key",
                                    color = BossThemeColors.TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Username: Your key name",
                                    color = BossThemeColors.TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = BossThemeColors.TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Password: Your API key",
                                    color = BossThemeColors.TextPrimary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Use the X-API-Key header with your key for CI/CD publishing.",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SuccessColor)
                        ) {
                            Text("Done", color = BossThemeColors.TextPrimary)
                        }
                    }
                } else {
                    // Creation form
                    Text(
                        "Create an API key for CI/CD publishing to the Plugin Store. The key will be securely stored in your secrets.",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Name field
                    DialogTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Key Name",
                        placeholder = "e.g., github-actions-release"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Scopes
                    Text(
                        "Scopes",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ScopeCheckbox(
                            checked = publishScope,
                            onCheckedChange = { publishScope = it },
                            label = "publish",
                            description = "Create new plugin versions"
                        )
                        ScopeCheckbox(
                            checked = versionScope,
                            onCheckedChange = { versionScope = it },
                            label = "version",
                            description = "Upload version files"
                        )
                        ScopeCheckbox(
                            checked = finalizeScope,
                            onCheckedChange = { finalizeScope = it },
                            label = "finalize",
                            description = "Finalize version uploads"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Expiration
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = hasExpiration,
                            onCheckedChange = { hasExpiration = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BossThemeColors.SuccessColor,
                                uncheckedColor = BossThemeColors.TextSecondary
                            )
                        )
                        Column {
                            Text(
                                "Set expiration",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp
                            )
                            Text(
                                "Key will expire after specified days",
                                color = BossThemeColors.TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    if (hasExpiration) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = expirationDays,
                                onValueChange = { newValue ->
                                    // Only allow digits
                                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                        expirationDays = newValue
                                    }
                                },
                                modifier = Modifier
                                    .width(80.dp)
                                    .background(BossThemeColors.BackgroundColor, RoundedCornerShape(4.dp))
                                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.body2.copy(color = BossThemeColors.TextPrimary),
                                cursorBrush = SolidColor(BossThemeColors.SuccessColor)
                            )
                            Text(
                                "days",
                                color = BossThemeColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isLoading) {
                            Text("Cancel", color = BossThemeColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val scopes = buildList {
                                    if (publishScope) add("publish")
                                    if (versionScope) add("version")
                                    if (finalizeScope) add("finalize")
                                }
                                val expDays = if (hasExpiration) expirationDays.toIntOrNull() else null
                                onConfirm(name, scopes, expDays)
                            },
                            enabled = !isLoading && name.isNotBlank() && (publishScope || versionScope || finalizeScope),
                            colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.WarningColor)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BossThemeColors.TextPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Create Key", color = Color.Black)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Checkbox component for scope selection.
 */
@Composable
private fun ScopeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = BossThemeColors.SuccessColor,
                uncheckedColor = BossThemeColors.TextSecondary
            )
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                label,
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                color = BossThemeColors.TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Dialog for listing and managing API keys.
 */
@Composable
private fun ApiKeysListDialog(
    apiKeys: List<ApiKeyInfo>,
    onRevoke: (keyId: String) -> Unit,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    isLoading: Boolean
) {
    var keyToRevoke by remember { mutableStateOf<ApiKeyInfo?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(500.dp).heightIn(max = 450.dp),
            color = BossThemeColors.SurfaceColor,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = Color(0xFF64B5F6),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Plugin Store API Keys",
                            color = BossThemeColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onCreateNew,
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Create new",
                            tint = BossThemeColors.SuccessColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = BossThemeColors.SuccessColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else if (apiKeys.isEmpty()) {
                    // Empty state
                    Box(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = BossThemeColors.TextSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "No API keys",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                "Create a key for CI/CD publishing",
                                color = BossThemeColors.TextSecondary,
                                fontSize = 12.sp
                            )
                            Button(
                                onClick = onCreateNew,
                                colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.WarningColor)
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Create API Key", color = Color.Black, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    // Keys list
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .lazyListScrollbar(
                                listState = listState,
                                direction = Orientation.Vertical,
                                config = getPanelScrollbarConfig()
                            ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(apiKeys, key = { it.id }) { key ->
                            ApiKeyCard(
                                apiKey = key,
                                onRevoke = { keyToRevoke = key },
                                isLoading = isLoading
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.SurfaceColor)
                    ) {
                        Text("Close", color = BossThemeColors.TextPrimary)
                    }
                }
            }
        }
    }

    // Revoke confirmation dialog
    keyToRevoke?.let { key ->
        AlertDialog(
            onDismissRequest = { keyToRevoke = null },
            title = {
                Text("Revoke API Key?", color = BossThemeColors.TextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Are you sure you want to revoke this API key?",
                        color = BossThemeColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        key.name,
                        color = Color(0xFF90CAF9),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "This will immediately invalidate the key. CI/CD pipelines using this key will fail.",
                        color = BossThemeColors.ErrorColor,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRevoke(key.id)
                        keyToRevoke = null
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(backgroundColor = BossThemeColors.ErrorColor)
                ) {
                    Text("Revoke", color = BossThemeColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToRevoke = null }) {
                    Text("Cancel", color = BossThemeColors.TextSecondary)
                }
            },
            backgroundColor = BossThemeColors.SurfaceColor
        )
    }
}

/**
 * Card component for displaying a single API key.
 */
@Composable
private fun ApiKeyCard(
    apiKey: ApiKeyInfo,
    onRevoke: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        backgroundColor = BossThemeColors.SurfaceColor,
        elevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        apiKey.name,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Key prefix
                        Text(
                            apiKey.keyPrefix + "...",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 11.sp
                        )
                        // Scopes
                        apiKey.scopes.forEach { scope ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = BossThemeColors.SuccessColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    scope,
                                    color = BossThemeColors.SuccessColor,
                                    fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onRevoke,
                    enabled = !isLoading,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Revoke",
                        tint = BossThemeColors.ErrorColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Created date
                Column {
                    Text(
                        "Created",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 10.sp
                    )
                    Text(
                        formatTimestamp(apiKey.createdAt),
                        color = BossThemeColors.TextPrimary,
                        fontSize = 11.sp
                    )
                }

                // Last used
                apiKey.lastUsedAt?.let { lastUsed ->
                    Column {
                        Text(
                            "Last used",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 10.sp
                        )
                        Text(
                            formatTimestamp(lastUsed),
                            color = BossThemeColors.TextPrimary,
                            fontSize = 11.sp
                        )
                    }
                }

                // Expires
                apiKey.expiresAt?.let { expiresAt ->
                    Column {
                        Text(
                            "Expires",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 10.sp
                        )
                        Text(
                            formatTimestamp(expiresAt),
                            color = BossThemeColors.WarningColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Format a timestamp (milliseconds) to a readable date string.
 */
private fun formatTimestamp(timestamp: Long): String {
    return try {
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        val dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
        dateTime.format(formatter)
    } catch (_: Exception) {
        "Unknown"
    }
}
