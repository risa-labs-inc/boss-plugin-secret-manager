package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

// Long enough to paste into an external tool, short enough that a copied
// secret doesn't linger on the system clipboard indefinitely
private const val CLIPBOARD_CLEAR_DELAY_MS = 45_000L

@Serializable
data class ShareUserRow(val id: String, val email: String)

@Serializable
data class ShareRoleRow(val id: String, val name: String, val description: String? = null)

/**
 * ViewModel for Secret Manager panel.
 *
 * Uses SecretDataProvider, SupabaseDataProvider, and PluginStoreApiKeyProvider
 * interfaces for data operations. Matches the bundled plugin's state management pattern.
 */
class SecretManagerViewModel(
    private val secretDataProvider: SecretDataProvider?,
    private val supabaseDataProvider: SupabaseDataProvider?,
    private val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?,
    private val scope: CoroutineScope
) {
    private val logger = BossLogger.forComponent("SecretManager")

    // Job tracking to prevent race conditions
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    // Lazy-load guards for share-dialog data and the API-key permission check
    private var usersLoaded = false
    private var rolesLoaded = false
    // True while availableUsers holds a search-filtered subset rather than the full list
    private var usersListFiltered = false
    private var apiKeyPermissionChecked = false

    // State
    var state by mutableStateOf(SecretManagerState())
        private set

    /**
     * Initialize by loading secrets.
     *
     * Users and roles are NOT loaded here — they are only needed by the share
     * dialog and are fetched lazily when it opens, so opening the panel costs
     * a single network round-trip instead of three.
     */
    fun initialize() {
        if (secretDataProvider != null) {
            loadSecrets()
        }
    }

    /**
     * Elapsed milliseconds since a System.nanoTime() mark — monotonic, so
     * durations are immune to wall-clock (NTP) jumps.
     */
    private fun elapsedMsSince(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    /**
     * Log elapsed time for a data operation so intermittent slow loads are
     * diagnosable from the host console (search for "SecretManager").
     */
    private fun logTiming(operation: String, elapsedMs: Long, outcome: String, failed: Boolean = false) {
        val message = "$operation: ${if (failed) "FAILED ($outcome)" else outcome} in $elapsedMs ms"
        if (failed) {
            logger.warn(LogCategory.NETWORK, message)
        } else {
            logger.info(LogCategory.NETWORK, message)
        }
    }

    /**
     * Check if the provider is available.
     */
    fun isAvailable(): Boolean {
        return secretDataProvider != null
    }

    /**
     * Load all secrets for the current user
     */
    fun loadSecrets() {
        state = state.copy(
            isLoading = true,
            errorMessage = null,
            searchQuery = "",
            currentOffset = 0,
            hasMore = true,
            lastLoadDurationMs = null
        )

        scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getUserSecrets(limit = state.pageSize, offset = 0)
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                val secrets = paginatedResult.data
                logTiming("getUserSecrets", elapsedMs, "${secrets.size} secrets")
                state = state.copy(
                    secrets = secrets,
                    isLoading = false,
                    currentOffset = secrets.size,
                    hasMore = paginatedResult.hasMore,
                    lastLoadDurationMs = elapsedMs
                )
                // Pre-warm the API-key permission check off the critical open
                // path so the Add menu is populated by the time it's opened
                checkApiKeyPermission()
            }?.onFailure { exception ->
                val error = exception.message ?: "Unknown error"
                logTiming("getUserSecrets", elapsedMs, error, failed = true)
                state = state.copy(
                    isLoading = false,
                    errorMessage = error
                )
            }
        }
    }

    /**
     * Load more secrets (pagination)
     */
    fun loadMoreSecrets() {
        if (state.isLoadingMore || !state.hasMore || state.isLoading || state.searchQuery.isNotBlank()) {
            return
        }
        if (searchJob?.isActive == true) {
            return
        }

        loadJob?.cancel()
        state = state.copy(isLoadingMore = true)

        loadJob = scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getUserSecrets(
                limit = state.pageSize,
                offset = state.currentOffset
            )
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                val newSecrets = paginatedResult.data
                logTiming("getUserSecrets(offset=${state.currentOffset})", elapsedMs, "${newSecrets.size} secrets")
                state = state.copy(
                    secrets = state.secrets + newSecrets,
                    isLoadingMore = false,
                    currentOffset = state.currentOffset + newSecrets.size,
                    hasMore = paginatedResult.hasMore,
                    lastLoadDurationMs = elapsedMs
                )
            }?.onFailure { exception ->
                if (exception is CancellationException) return@onFailure
                state = state.copy(
                    isLoadingMore = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Search secrets by website or username
     */
    fun searchSecrets(query: String) {
        loadJob?.cancel()
        loadJob = null
        searchJob?.cancel()

        state = state.copy(
            searchQuery = query,
            isLoading = true,
            isLoadingMore = false,
            errorMessage = null,
            currentOffset = 0,
            hasMore = false,
            lastLoadDurationMs = null
        )

        if (query.isBlank()) {
            searchJob = null
            loadSecrets()
            return
        }

        searchJob = scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.searchSecrets(query = query, limit = 100, offset = 0)
            val elapsedMs = elapsedMsSince(startedAt)

            result?.onSuccess { paginatedResult ->
                logTiming("searchSecrets", elapsedMs, "${paginatedResult.data.size} secrets")
                state = state.copy(
                    secrets = paginatedResult.data,
                    isLoading = false,
                    isLoadingMore = false,
                    currentOffset = 0,
                    hasMore = false,
                    lastLoadDurationMs = elapsedMs
                )
            }?.onFailure { exception ->
                if (exception is CancellationException) return@onFailure
                state = state.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun showCreateDialog() {
        state = state.copy(showCreateDialog = true, selectedSecret = null)
    }

    fun hideCreateDialog() {
        state = state.copy(showCreateDialog = false)
    }

    fun showEditDialog(secret: SecretEntryData) {
        state = state.copy(showEditDialog = true, selectedSecret = secret)
    }

    fun hideEditDialog() {
        state = state.copy(showEditDialog = false, selectedSecret = null)
    }

    fun showDeleteDialog(secret: SecretEntryData) {
        state = state.copy(showDeleteDialog = true, selectedSecret = secret)
    }

    fun hideDeleteDialog() {
        state = state.copy(showDeleteDialog = false, selectedSecret = null)
    }

    fun showShareDialog(secret: SecretEntryData) {
        state = state.copy(
            showShareDialog = true,
            selectedSecret = secret,
            secretShares = emptyList(),
            isLoadingShares = false
        )
        loadSecretShares(secret.id)
        // Share targets are fetched lazily on first dialog open (see initialize);
        // the loaded flags avoid re-fetching a legitimately empty list. A reload
        // is also needed when a previous search left a filtered subset behind.
        if ((!usersLoaded || usersListFiltered) && !state.isLoadingUsers) {
            loadAvailableUsers()
        }
        if (!rolesLoaded && !state.isLoadingRoles) {
            loadAvailableRoles()
        }
    }

    fun hideShareDialog() {
        state = state.copy(
            showShareDialog = false,
            selectedSecret = null,
            secretShares = emptyList(),
            isLoadingShares = false
        )
    }

    /**
     * Create a new secret
     */
    fun createSecret(request: CreateSecretRequestData) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.createSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideCreateDialog()
                loadSecrets()
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Update an existing secret
     */
    fun updateSecret(request: UpdateSecretRequestData) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.updateSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideEditDialog()
                loadSecrets()
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * Delete a secret
     */
    fun deleteSecret(secretId: String) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.deleteSecret(secretId)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                hideDeleteDialog()
                state = state.copy(secrets = state.secrets.filter { it.id != secretId })
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    // Generation token: bumped per copy so only the latest copy's timer may clear
    private var clipboardCopyGeneration = 0L

    /**
     * Copy a secret's password/API key to the clipboard, then best-effort
     * clear it after a delay. Runs in the ViewModel scope so the pending
     * clear survives the card scrolling out of composition. The clear only
     * fires from the most recent copy's timer, and only if the clipboard
     * still holds that value — so re-copies get their full window and
     * anything the user copied afterwards is never clobbered.
     */
    fun copyPasswordToClipboard(secret: SecretEntryData, clipboard: ClipboardManager) {
        val copied = secret.password
        val generation = ++clipboardCopyGeneration
        clipboard.setText(AnnotatedString(copied))
        scope.launch {
            delay(CLIPBOARD_CLEAR_DELAY_MS)
            if (generation == clipboardCopyGeneration && clipboard.getText()?.text == copied) {
                clipboard.setText(AnnotatedString(""))
            }
        }
    }

    fun togglePasswordVisibility(secretId: String) {
        val current = state.visiblePasswordIds
        state = if (current.contains(secretId)) {
            state.copy(visiblePasswordIds = current - secretId)
        } else {
            state.copy(visiblePasswordIds = current + secretId)
        }
    }

    fun toggleMetadataExpanded(secretId: String) {
        val current = state.expandedSecretIds
        state = if (current.contains(secretId)) {
            state.copy(expandedSecretIds = current - secretId)
        } else {
            state.copy(expandedSecretIds = current + secretId)
        }
    }

    fun clearError() {
        state = state.copy(errorMessage = null)
    }

    fun loadSecretShares(secretId: String) {
        state = state.copy(isLoadingShares = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = secretDataProvider?.getSecretShares(secretId)

            result?.onSuccess { shares ->
                logTiming("getSecretShares", elapsedMsSince(startedAt), "${shares.size} shares")
                state = state.copy(secretShares = shares, isLoadingShares = false)
            }?.onFailure { exception ->
                state = state.copy(
                    isLoadingShares = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun shareSecret(request: ShareSecretRequestData) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = secretDataProvider?.shareSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                loadSecretShares(request.secretId)
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun unshareSecret(secretId: String, userId: String? = null, roleId: String? = null) {
        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val request = UnshareSecretRequestData(
                secretId = secretId,
                targetUserId = userId,
                targetRoleId = roleId
            )

            val result = secretDataProvider?.unshareSecret(request)

            result?.onSuccess {
                state = state.copy(isOperationInProgress = false)
                loadSecretShares(secretId)
            }?.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun loadAvailableUsers() {
        state = state.copy(isLoadingUsers = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(
                table = "users_with_roles",
                columns = "id,email",
                range = QueryRange(0, 9)
            )

            result?.onSuccess { jsonStr ->
                val users = json.decodeFromString<List<ShareUserRow>>(jsonStr)
                logTiming("select(users_with_roles)", elapsedMsSince(startedAt), "${users.size} users")
                usersLoaded = true
                usersListFiltered = false
                state = state.copy(availableUsers = users, isLoadingUsers = false)
            }?.onFailure { exception ->
                state = state.copy(
                    isLoadingUsers = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun searchUsersForSharing(query: String) {
        state = state.copy(isLoadingUsers = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(
                table = "users_with_roles",
                columns = "id,email",
                filters = listOf(QueryFilter("email", FilterOperator.ILIKE, "%$query%")),
                range = QueryRange(0, 9)
            )

            result?.onSuccess { jsonStr ->
                val users = json.decodeFromString<List<ShareUserRow>>(jsonStr)
                logTiming("select(users_with_roles, search)", elapsedMsSince(startedAt), "${users.size} users")
                usersListFiltered = query.isNotBlank()
                state = state.copy(availableUsers = users, isLoadingUsers = false)
            }?.onFailure { exception ->
                state = state.copy(
                    isLoadingUsers = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    fun loadAvailableRoles() {
        state = state.copy(isLoadingRoles = true)

        scope.launch {
            val startedAt = System.nanoTime()
            val result = supabaseDataProvider?.select(table = "roles", columns = "id,name,description")

            result?.onSuccess { jsonStr ->
                val roles = json.decodeFromString<List<ShareRoleRow>>(jsonStr)
                logTiming("select(roles)", elapsedMsSince(startedAt), "${roles.size} roles")
                rolesLoaded = true
                state = state.copy(availableRoles = roles, isLoadingRoles = false)
            }?.onFailure { exception ->
                state = state.copy(
                    isLoadingRoles = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
    }

    // ============================================================================
    // Plugin Store API Key Management
    // ============================================================================

    /**
     * Check if the current user can manage API keys.
     *
     * Kept off the panel's critical open path: pre-warmed after the first
     * successful secrets load, with the Add dropdown's open as a fallback
     * trigger. Runs at most once; a failed check allows a retry.
     */
    fun checkApiKeyPermission() {
        if (apiKeyPermissionChecked) return
        apiKeyPermissionChecked = true // also dedupes concurrent triggers
        scope.launch {
            val canManage = try {
                pluginStoreApiKeyProvider?.canManageApiKeys() ?: false
            } catch (e: CancellationException) {
                apiKeyPermissionChecked = false
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.NETWORK, "canManageApiKeys FAILED: ${e.message}")
                apiKeyPermissionChecked = false // allow the next trigger to retry
                return@launch
            }
            state = state.copy(canManageApiKeys = canManage)
        }
    }

    /**
     * Show the Create API Key dialog.
     */
    fun showCreateApiKeyDialog() {
        state = state.copy(showCreateApiKeyDialog = true)
    }

    /**
     * Hide the Create API Key dialog.
     */
    fun hideCreateApiKeyDialog() {
        state = state.copy(
            showCreateApiKeyDialog = false,
            apiKeyCreatedSuccessfully = false
        )
    }

    /**
     * Load all API keys for the current user.
     */
    fun loadApiKeys() {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isLoadingApiKeys = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.listApiKeys()

            result.onSuccess { keys ->
                state = state.copy(
                    apiKeys = keys,
                    isLoadingApiKeys = false
                )
            }.onFailure { exception ->
                state = state.copy(
                    isLoadingApiKeys = false,
                    errorMessage = exception.message ?: "Failed to load API keys"
                )
            }
        }
    }

    /**
     * Create a new API key and automatically store it as a secret.
     *
     * @param name Display name for the key
     * @param scopes List of scopes
     * @param expiresInDays Optional expiration in days
     */
    fun createApiKey(
        name: String,
        scopes: List<String> = listOf("publish", "version", "finalize"),
        expiresInDays: Int? = null
    ) {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.createApiKey(name, scopes, expiresInDays)

            result.onSuccess { creationResult ->
                // Automatically store the API key as a secret
                val secretRequest = CreateSecretRequestData(
                    website = "boss_plugin_store_api_key",
                    username = name,
                    password = creationResult.apiKey,
                    notes = "Plugin Store API Key\nScopes: ${scopes.joinToString(", ")}" +
                            (expiresInDays?.let { "\nExpires in: $it days" } ?: ""),
                    tags = listOf("api_key")
                )

                val secretResult = secretDataProvider?.createSecret(secretRequest)

                secretResult?.onSuccess {
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo
                    )
                    // Reload secrets to show the newly created one
                    loadSecrets()
                }?.onFailure { secretException ->
                    // API key created but secret storage failed - still show as partial success
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo,
                        errorMessage = "API key created but failed to store as secret: ${secretException.message}"
                    )
                }

                // If secretDataProvider is null, just mark as success
                if (secretDataProvider == null) {
                    state = state.copy(
                        isOperationInProgress = false,
                        apiKeyCreatedSuccessfully = true,
                        apiKeys = state.apiKeys + creationResult.keyInfo
                    )
                }
            }.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Failed to create API key"
                )
            }
        }
    }

    /**
     * Clear the API key created success flag.
     */
    fun clearApiKeyCreatedFlag() {
        state = state.copy(apiKeyCreatedSuccessfully = false)
    }

    /**
     * Revoke an API key.
     */
    fun revokeApiKey(keyId: String) {
        if (pluginStoreApiKeyProvider == null) return

        state = state.copy(isOperationInProgress = true)

        scope.launch {
            val result = pluginStoreApiKeyProvider.revokeApiKey(keyId)

            result.onSuccess {
                state = state.copy(
                    isOperationInProgress = false,
                    apiKeys = state.apiKeys.filter { it.id != keyId }
                )
            }.onFailure { exception ->
                state = state.copy(
                    isOperationInProgress = false,
                    errorMessage = exception.message ?: "Failed to revoke API key"
                )
            }
        }
    }

    /**
     * Show the API Keys list dialog.
     */
    fun showApiKeysListDialog() {
        state = state.copy(showApiKeysListDialog = true)
        loadApiKeys()
    }

    /**
     * Hide the API Keys list dialog.
     */
    fun hideApiKeysListDialog() {
        state = state.copy(showApiKeysListDialog = false)
    }
}

/**
 * State for Secret Manager panel - matches bundled plugin state
 */
data class SecretManagerState(
    val secrets: List<SecretEntryData> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isOperationInProgress: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val showCreateDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val selectedSecret: SecretEntryData? = null,
    val expandedSecretIds: Set<String> = emptySet(),
    val visiblePasswordIds: Set<String> = emptySet(),
    val pageSize: Int = 50,
    val currentOffset: Int = 0,
    val hasMore: Boolean = true,
    val lastLoadDurationMs: Long? = null,
    // Sharing-related state
    val showShareDialog: Boolean = false,
    val secretShares: List<SecretShareData> = emptyList(),
    val isLoadingShares: Boolean = false,
    // Available users and roles for sharing
    val availableUsers: List<ShareUserRow> = emptyList(),
    val availableRoles: List<ShareRoleRow> = emptyList(),
    val isLoadingUsers: Boolean = false,
    val isLoadingRoles: Boolean = false,
    // Plugin Store API Key management
    val canManageApiKeys: Boolean = false,
    val showCreateApiKeyDialog: Boolean = false,
    val showApiKeysListDialog: Boolean = false,
    val apiKeys: List<ApiKeyInfo> = emptyList(),
    val isLoadingApiKeys: Boolean = false,
    val apiKeyCreatedSuccessfully: Boolean = false // Flag to show success message
)
