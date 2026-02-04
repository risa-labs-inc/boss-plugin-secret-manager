package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * ViewModel for Secret Manager panel.
 *
 * Uses SecretDataProvider and UserManagementProvider interfaces for data operations.
 * Matches the bundled plugin's state management pattern.
 */
class SecretManagerViewModel(
    private val secretDataProvider: SecretDataProvider?,
    private val userManagementProvider: UserManagementProvider?,
    private val scope: CoroutineScope
) {
    // Job tracking to prevent race conditions
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    // State
    var state by mutableStateOf(SecretManagerState())
        private set

    /**
     * Initialize by loading secrets.
     */
    fun initialize() {
        if (secretDataProvider != null) {
            loadSecrets()
            loadAvailableUsers()
            loadAvailableRoles()
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
            hasMore = true
        )

        scope.launch {
            val result = secretDataProvider?.getUserSecrets(limit = state.pageSize, offset = 0)

            result?.onSuccess { paginatedResult ->
                val secrets = paginatedResult.data
                state = state.copy(
                    secrets = secrets,
                    isLoading = false,
                    currentOffset = secrets.size,
                    hasMore = paginatedResult.hasMore
                )
            }?.onFailure { exception ->
                val error = exception.message ?: "Unknown error"
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
            val result = secretDataProvider?.getUserSecrets(
                limit = state.pageSize,
                offset = state.currentOffset
            )

            result?.onSuccess { paginatedResult ->
                val newSecrets = paginatedResult.data
                state = state.copy(
                    secrets = state.secrets + newSecrets,
                    isLoadingMore = false,
                    currentOffset = state.currentOffset + newSecrets.size,
                    hasMore = paginatedResult.hasMore
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
            hasMore = false
        )

        if (query.isBlank()) {
            searchJob = null
            loadSecrets()
            return
        }

        searchJob = scope.launch {
            val result = secretDataProvider?.searchSecrets(query = query, limit = 100, offset = 0)

            result?.onSuccess { paginatedResult ->
                state = state.copy(
                    secrets = paginatedResult.data,
                    isLoading = false,
                    isLoadingMore = false,
                    currentOffset = 0,
                    hasMore = false
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
            val result = secretDataProvider?.getSecretShares(secretId)

            result?.onSuccess { shares ->
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
            val result = userManagementProvider?.getAllUsersWithRoles(limit = 10, offset = 0)

            result?.onSuccess { paginatedResult ->
                state = state.copy(availableUsers = paginatedResult.data, isLoadingUsers = false)
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
            val result = userManagementProvider?.searchUsersByEmail(query, limit = 10, offset = 0)

            result?.onSuccess { paginatedResult ->
                state = state.copy(availableUsers = paginatedResult.data, isLoadingUsers = false)
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
            val result = userManagementProvider?.getAllRoles()

            result?.onSuccess { roles ->
                state = state.copy(availableRoles = roles, isLoadingRoles = false)
            }?.onFailure { exception ->
                state = state.copy(
                    isLoadingRoles = false,
                    errorMessage = exception.message ?: "Unknown error"
                )
            }
        }
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
    // Sharing-related state
    val showShareDialog: Boolean = false,
    val secretShares: List<SecretShareData> = emptyList(),
    val isLoadingShares: Boolean = false,
    // Available users and roles for sharing
    val availableUsers: List<UserWithRolesData> = emptyList(),
    val availableRoles: List<RoleInfoData> = emptyList(),
    val isLoadingUsers: Boolean = false,
    val isLoadingRoles: Boolean = false
)
