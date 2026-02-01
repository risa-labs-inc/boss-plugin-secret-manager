package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Secret Manager panel.
 *
 * Manages secret CRUD operations, search, and sharing.
 */
class SecretManagerViewModel(
    private val secretDataProvider: SecretDataProvider?,
    private val scope: CoroutineScope
) {
    private val _secrets = MutableStateFlow<List<SecretEntryData>>(emptyList())
    val secrets: StateFlow<List<SecretEntryData>> = _secrets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSecretId = MutableStateFlow<String?>(null)
    val selectedSecretId: StateFlow<String?> = _selectedSecretId.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _editingSecret = MutableStateFlow<SecretEntryData?>(null)
    val editingSecret: StateFlow<SecretEntryData?> = _editingSecret.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _showShareDialog = MutableStateFlow(false)
    val showShareDialog: StateFlow<Boolean> = _showShareDialog.asStateFlow()

    private val _currentShares = MutableStateFlow<List<SecretShareData>>(emptyList())
    val currentShares: StateFlow<List<SecretShareData>> = _currentShares.asStateFlow()

    private var currentOffset = 0
    private val pageSize = 50

    /**
     * Initialize by loading secrets.
     */
    fun initialize() {
        loadSecrets()
    }

    /**
     * Load secrets from the provider.
     */
    fun loadSecrets(loadMore: Boolean = false) {
        scope.launch {
            try {
                _isLoading.value = true
                if (!loadMore) {
                    currentOffset = 0
                }

                val result = if (_searchQuery.value.isNotEmpty()) {
                    secretDataProvider?.searchSecrets(_searchQuery.value, pageSize, currentOffset)
                } else {
                    secretDataProvider?.getUserSecrets(pageSize, currentOffset)
                }

                result?.onSuccess { paginated ->
                    if (loadMore) {
                        _secrets.value = _secrets.value + paginated.data
                    } else {
                        _secrets.value = paginated.data
                    }
                    _hasMore.value = paginated.hasMore
                    currentOffset += paginated.data.size
                }?.onFailure { error ->
                    _errorMessage.value = "Failed to load secrets: ${error.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error loading secrets: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load more secrets (pagination).
     */
    fun loadMore() {
        if (_hasMore.value && !_isLoading.value) {
            loadSecrets(loadMore = true)
        }
    }

    /**
     * Update search query and reload.
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        loadSecrets()
    }

    /**
     * Select a secret.
     */
    fun select(secretId: String) {
        _selectedSecretId.value = secretId
    }

    /**
     * Start creating a new secret.
     */
    fun startCreate() {
        _editingSecret.value = null
        _showCreateDialog.value = true
    }

    /**
     * Start editing a secret.
     */
    fun startEdit(secret: SecretEntryData) {
        _editingSecret.value = secret
        _showCreateDialog.value = true
    }

    /**
     * Cancel editing.
     */
    fun cancelEdit() {
        _editingSecret.value = null
        _showCreateDialog.value = false
    }

    /**
     * Save a secret (create or update).
     */
    fun saveSecret(
        website: String,
        username: String,
        password: String,
        notes: String? = null,
        tags: List<String> = emptyList()
    ) {
        scope.launch {
            try {
                _isLoading.value = true
                val editing = _editingSecret.value

                val result = if (editing != null) {
                    secretDataProvider?.updateSecret(
                        UpdateSecretRequestData(
                            secretId = editing.id,
                            website = website,
                            username = username,
                            password = password,
                            notes = notes,
                            tags = tags
                        )
                    )
                } else {
                    secretDataProvider?.createSecret(
                        CreateSecretRequestData(
                            website = website,
                            username = username,
                            password = password,
                            notes = notes,
                            tags = tags
                        )
                    )
                }

                result?.onSuccess {
                    _statusMessage.value = if (editing != null) "Secret updated" else "Secret created"
                    _showCreateDialog.value = false
                    _editingSecret.value = null
                    loadSecrets()
                }?.onFailure { error ->
                    _errorMessage.value = "Failed to save: ${error.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error saving: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a secret.
     */
    fun deleteSecret(secretId: String) {
        scope.launch {
            try {
                _isLoading.value = true
                secretDataProvider?.deleteSecret(secretId)
                    ?.onSuccess {
                        _statusMessage.value = "Secret deleted"
                        _selectedSecretId.value = null
                        loadSecrets()
                    }?.onFailure { error ->
                        _errorMessage.value = "Failed to delete: ${error.message}"
                    }
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Show share dialog for a secret.
     */
    fun showShareDialog(secretId: String) {
        _selectedSecretId.value = secretId
        _showShareDialog.value = true
        loadShares(secretId)
    }

    /**
     * Hide share dialog.
     */
    fun hideShareDialog() {
        _showShareDialog.value = false
        _currentShares.value = emptyList()
    }

    /**
     * Load shares for a secret.
     */
    private fun loadShares(secretId: String) {
        scope.launch {
            try {
                secretDataProvider?.getSecretShares(secretId)
                    ?.onSuccess { shares ->
                        _currentShares.value = shares
                    }?.onFailure { error ->
                        _errorMessage.value = "Failed to load shares: ${error.message}"
                    }
            } catch (e: Exception) {
                _errorMessage.value = "Error loading shares: ${e.message}"
            }
        }
    }

    /**
     * Share a secret with a user.
     */
    fun shareWithUser(secretId: String, userId: String, notes: String? = null) {
        scope.launch {
            try {
                secretDataProvider?.shareSecret(
                    ShareSecretRequestData(
                        secretId = secretId,
                        targetUserId = userId,
                        notes = notes
                    )
                )?.onSuccess {
                    _statusMessage.value = "Secret shared"
                    loadShares(secretId)
                }?.onFailure { error ->
                    _errorMessage.value = "Failed to share: ${error.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error sharing: ${e.message}"
            }
        }
    }

    /**
     * Unshare a secret.
     */
    fun unshare(secretId: String, userId: String? = null, roleId: String? = null) {
        scope.launch {
            try {
                secretDataProvider?.unshareSecret(
                    UnshareSecretRequestData(
                        secretId = secretId,
                        targetUserId = userId,
                        targetRoleId = roleId
                    )
                )?.onSuccess {
                    _statusMessage.value = "Share removed"
                    loadShares(secretId)
                }?.onFailure { error ->
                    _errorMessage.value = "Failed to unshare: ${error.message}"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error unsharing: ${e.message}"
            }
        }
    }

    /**
     * Clear status message.
     */
    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Clear error message.
     */
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    /**
     * Check if the provider is available.
     */
    fun isAvailable(): Boolean {
        return secretDataProvider != null
    }

    /**
     * Get a secret by ID.
     */
    fun getSecret(secretId: String): SecretEntryData? {
        return _secrets.value.find { it.id == secretId }
    }
}
