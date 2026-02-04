package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.scrollbar.getPanelScrollbarConfig
import ai.rever.boss.plugin.scrollbar.lazyListScrollbar
import ai.rever.boss.plugin.ui.BossDarkBackground
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.boss.plugin.ui.BossDarkTextSecondary
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
 * UI matches the bundled plugin's Card-based design.
 */
@Composable
fun SecretManagerContent(
    secretDataProvider: SecretDataProvider?,
    userManagementProvider: UserManagementProvider?,
    scope: CoroutineScope
) {
    val viewModel = remember(secretDataProvider, userManagementProvider, scope) {
        SecretManagerViewModel(secretDataProvider, userManagementProvider, scope)
    }

    if (!viewModel.isAvailable()) {
        NoProviderMessage()
    } else {
        SecretManagerView(viewModel)
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }
}

@Composable
private fun NoProviderMessage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2B2D30))
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
                tint = BossDarkTextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                "Secret Manager",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Secret provider not available",
                color = BossDarkTextSecondary,
                fontSize = 13.sp
            )
            Text(
                "Please ensure the host provides secret management access",
                color = BossDarkTextSecondary.copy(alpha = 0.6f),
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2B2D30))
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
                    color = Color.White,
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
                        tint = if (state.isLoading) Color.Gray else Color.White
                    )
                }

                // Add button
                IconButton(
                    onClick = { viewModel.showCreateDialog() },
                    enabled = !state.isLoading
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add Secret",
                        tint = Color(0xFF4CAF50)
                    )
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
                color = Color.Gray,
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
                                        color = Color(0xFF4CAF50),
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
        textStyle = MaterialTheme.typography.body2.copy(color = Color.White),
        cursorBrush = SolidColor(Color(0xFF4CAF50)),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                    .border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = BossDarkTextSecondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search secrets...",
                            style = MaterialTheme.typography.body2,
                            color = BossDarkTextSecondary,
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
                            tint = BossDarkTextSecondary
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
            CircularProgressIndicator(color = Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Loading secrets...", color = BossDarkTextSecondary, fontSize = 12.sp)
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
                tint = Color(0xFFE57373),
                modifier = Modifier.size(32.dp)
            )
            Text(message, color = BossDarkTextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                ) {
                    Text("Retry", color = Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = BossDarkTextSecondary, fontSize = 12.sp)
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
                tint = BossDarkTextSecondary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                if (searchQuery.isBlank()) "No secrets yet" else "No results found",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (searchQuery.isBlank()) "Add your first secret to get started"
                else "Try a different search term",
                color = BossDarkTextSecondary,
                fontSize = 12.sp
            )
            if (searchQuery.isBlank()) {
                Button(
                    onClick = onAddSecret,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Secret", color = Color.White, fontSize = 12.sp)
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
        backgroundColor = Color(0xFF3C3F41),
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
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Username
                    Text(
                        text = secret.username,
                        color = Color.Gray,
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
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Password field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2B2D30), RoundedCornerShape(4.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPasswordVisible) secret.password else "••••••••",
                    color = if (isPasswordVisible) Color.White else Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onTogglePassword, modifier = Modifier.size(24.dp)) {
                    Icon(
                        if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                        tint = Color.Gray,
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
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Expires: ${secret.expirationDate}",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )
                }
            }

            // "More" button to show metadata
            val metadata = secret.metadata
            if (metadata != null && metadata.twofaEnabled) {
                Divider(color = Color(0xFF4E5254), thickness = 1.dp)

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
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "2FA Details",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Hide details" else "Show details",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Expanded metadata
                if (isExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2B2D30), RoundedCornerShape(4.dp))
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
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            metadata.recoveryCodes.forEach { code ->
                                Text(
                                    text = "• $code",
                                    color = Color.White,
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
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = notes,
                        color = Color.White,
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
        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
    ) {
        Text(
            text = tag,
            color = Color(0xFF4CAF50),
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
            color = Color.Gray,
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color.White,
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = Color(0xFF2D2D2D),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Add New Secret",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = "Website",
                    placeholder = "e.g., github.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username / Email",
                    placeholder = "e.g., user@example.com"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Enter password",
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

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossDarkTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                onConfirm(CreateSecretRequestData(
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() }
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Create", color = Color.White)
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(400.dp),
            color = Color(0xFF2D2D2D),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Edit Secret",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextField(
                    value = website,
                    onValueChange = { website = it },
                    label = "Website"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username / Email"
                )

                Spacer(modifier = Modifier.height(12.dp))

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
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

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isLoading) {
                        Text("Cancel", color = BossDarkTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (website.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                onConfirm(UpdateSecretRequestData(
                                    secretId = secret.id,
                                    website = website,
                                    username = username,
                                    password = password,
                                    notes = notes.takeIf { it.isNotBlank() }
                                ))
                            }
                        },
                        enabled = !isLoading && website.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4CAF50))
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = Color.White)
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
            Text("Delete Secret?", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Are you sure you want to delete this secret?",
                    color = Color.White
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
                    color = Color(0xFFF44336),
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFF44336))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Delete", color = Color.White)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Cancel", color = BossDarkTextSecondary)
            }
        },
        backgroundColor = Color(0xFF2D2D2D)
    )
}

@Composable
private fun ShareSecretDialog(
    secret: SecretEntryData,
    shares: List<SecretShareData>,
    availableUsers: List<UserWithRolesData>,
    availableRoles: List<RoleInfoData>,
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
            color = Color(0xFF2D2D2D),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Share Secret",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "${secret.website} - ${secret.username}",
                    color = BossDarkTextSecondary,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current shares
                if (shares.isNotEmpty() || isLoadingShares) {
                    Text(
                        "Currently shared with:",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoadingShares) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFF4CAF50),
                            strokeWidth = 2.dp
                        )
                    } else {
                        shares.forEach { share ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        share.sharedWithUserEmail ?: share.sharedWithRoleName ?: "Unknown",
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        if (share.sharedWithUserId != null) "User" else "Role",
                                        color = BossDarkTextSecondary,
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
                                        tint = Color(0xFFF44336),
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
                    backgroundColor = Color(0xFF1E1F22),
                    contentColor = Color(0xFF4CAF50)
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
                            .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                            .border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(color = Color.White),
                        cursorBrush = SolidColor(Color(0xFF4CAF50)),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = BossDarkTextSecondary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Search users by email...",
                                            color = BossDarkTextSecondary,
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
                                color = Color(0xFF4CAF50),
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
                                        tint = BossDarkTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        user.email,
                                        color = Color.White,
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
                                    tint = BossDarkTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        role.name,
                                        color = Color.White,
                                        fontSize = 12.sp
                                    )
                                    val description = role.description
                                    if (description != null) {
                                        Text(
                                            description,
                                            color = BossDarkTextSecondary,
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
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF424242))
                    ) {
                        Text("Done", color = Color.White)
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
            color = BossDarkTextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1F22), RoundedCornerShape(4.dp))
                .border(1.dp, BossDarkBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.body2.copy(color = Color.White),
                cursorBrush = SolidColor(Color(0xFF4CAF50)),
                visualTransformation = if (isPassword && !showPassword)
                    PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                color = BossDarkTextSecondary,
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
                        tint = BossDarkTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
