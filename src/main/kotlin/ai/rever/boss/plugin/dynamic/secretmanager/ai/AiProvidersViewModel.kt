package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Everything the AI providers panel renders. */
data class AiProvidersUiState(
    val providers: List<ProviderDescriptor> = ProviderRegistry.all,
    /** Provider whose detail is expanded in the panel. */
    val selectedProviderId: String = ProviderRegistry.default.id,
    /** Provider served to other plugins as the active config. */
    val activeProviderId: String? = null,
    val connections: Map<String, ProviderConnection> = emptyMap(),
    val catalogs: Map<String, CatalogState> = emptyMap(),
    /** In-progress key edits, keyed by provider id. Never persisted until saved. */
    val keyDrafts: Map<String, String> = emptyMap(),
    val busyProviderIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    /** False when the secret store is unavailable — env keys still work. */
    val storeAvailable: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val legacyOffer: LegacyImportOffer? = null,
) {
    fun connectionOf(providerId: String): ProviderConnection =
        connections[providerId]
            ?: ProviderConnection(providerId = providerId, apiKey = "", source = CredentialSource.NONE)

    fun catalogOf(providerId: String): CatalogState = catalogs[providerId] ?: CatalogState.NotConfigured
}

/**
 * Drives the AI providers panel: resolves credentials, keeps model lists current, and
 * persists changes.
 *
 * Model lists are fetched from the providers themselves — on first load for anything
 * stale, and on demand from the Refresh action. Nothing here falls back to a built-in
 * list, which is what let the previous implementation drift years out of date.
 */
class AiProvidersViewModel(
    private val store: ProviderCredentialStore?,
    private val catalog: ModelCatalog,
    private val prefs: ActiveProviderPrefs,
    private val legacyImport: LegacySettingsImport?,
    private val splitViewOperations: SplitViewOperations?,
    private val scope: CoroutineScope,
) {
    private val logger = BossLogger.forComponent("AiProvidersViewModel")

    private val _state = MutableStateFlow(AiProvidersUiState(storeAvailable = store != null))
    val state: StateFlow<AiProvidersUiState> = _state.asStateFlow()

    /** Guards [ensureConnectionsLoaded] so concurrent callers load credentials once. */
    private val connectionsLoadStarted = AtomicBoolean(false)

    private val _connectionsLoaded = MutableStateFlow(false)

    init {
        scope.launch { catalog.states.collect { states -> _state.update { it.copy(catalogs = states) } } }
    }

    /**
     * Load credentials only — no cache seeding, no model fetches, runs at most once.
     *
     * `activeConfig()` is answered from this state, and other plugins can ask for it
     * before the settings panel has ever been opened, so this warms it at registration.
     *
     * **It does not close the race.** This launches and returns; a caller on the very
     * next line still reads empty state, because the load does a paginated store read.
     * [connectionsLoaded] is the signal for callers that can wait — `activeConfig()`
     * cannot (it is a non-suspend api member), so a null from it may mean "not loaded
     * yet" rather than "nothing configured".
     */
    fun ensureConnectionsLoaded() {
        if (!connectionsLoadStarted.compareAndSet(false, true)) return
        scope.launch { loadConnections() }
    }

    /**
     * Whether credentials have been read at least once. Callers that can suspend should
     * await this before treating a null `activeConfig()` as "nothing configured".
     */
    val connectionsLoaded: StateFlow<Boolean> = _connectionsLoaded.asStateFlow()

    private suspend fun loadConnections(): Map<String, ProviderConnection> {
        val storedActive = prefs.read()
        val snapshot = store?.loadAll()
        val connections = withPreferredModels(snapshot?.connections ?: envOnlyConnections())

        _state.update { current ->
            current.copy(
                connections = connections,
                activeProviderId = current.activeProviderId ?: storedActive ?: firstConfigured(connections),
                storeAvailable = store != null && snapshot?.storeReadFailed != true,
            )
        }
        _connectionsLoaded.value = true
        return connections
    }

    /**
     * Overlay model selections held in prefs.
     *
     * A provider keyed by an environment variable has no secret to carry its settings,
     * so its chosen model lives in prefs; the stored value still wins where one exists.
     */
    private suspend fun withPreferredModels(
        connections: Map<String, ProviderConnection>,
    ): Map<String, ProviderConnection> {
        val preferred = prefs.readModels()
        if (preferred.isEmpty()) return connections
        return connections.mapValues { (providerId, connection) ->
            if (connection.selectedModelId != null) {
                connection
            } else {
                preferred[providerId]?.let { connection.copy(selectedModelId = it) } ?: connection
            }
        }
    }

    /** Load credentials, seed cached model lists, then refresh anything stale. */
    fun load() {
        connectionsLoadStarted.set(true)
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            catalog.seedFromCache()
            val connections = loadConnections()

            _state.update { current ->
                current.copy(
                    isLoading = false,
                    // Keep whichever provider the user had expanded. This runs from a
                    // LaunchedEffect on every entry into the section, so resetting the
                    // selection here discarded their place each time.
                    selectedProviderId =
                        current.selectedProviderId.takeIf { ProviderRegistry.find(it) != null }
                            ?: current.activeProviderId
                            ?: firstConfigured(connections)
                            ?: ProviderRegistry.default.id,
                    error =
                        if (!current.storeAvailable && store != null) {
                            "Stored credentials are unavailable — environment variables still apply."
                        } else {
                            null
                        },
                )
            }

            refreshStale(connections)
            checkLegacyImport()
        }
    }

    /**
     * When the secret store cannot be read, providers configured by environment
     * variable must still work. Building the map directly from the resolver keeps the
     * panel useful in that state instead of showing everything as unconfigured.
     */
    private suspend fun envOnlyConnections(): Map<String, ProviderConnection> {
        val resolver = EnvResolver()
        return ProviderRegistry.all.associate { descriptor ->
            val key = resolver.resolve(descriptor.envVarNames)
            descriptor.id to
                ProviderConnection(
                    providerId = descriptor.id,
                    apiKey = key.orEmpty(),
                    source = if (key.isNullOrBlank()) CredentialSource.NONE else CredentialSource.ENVIRONMENT,
                    label = resolver.resolveSourceName(descriptor.envVarNames),
                )
        }
    }

    private fun firstConfigured(connections: Map<String, ProviderConnection>): String? =
        ProviderRegistry.all.firstOrNull { connections[it.id]?.isConfigured == true }?.id

    /**
     * Refresh every stale provider concurrently.
     *
     * Sequentially, at a 20 s per-request timeout, the last provider's list could appear
     * minutes after the panel opened; in parallel the sweep settles in roughly one
     * request's time.
     */
    private suspend fun refreshStale(connections: Map<String, ProviderConnection>) =
        coroutineScope {
            ProviderRegistry.all.map { descriptor ->
                async {
                    val connection = connections[descriptor.id] ?: return@async
                    if (!connection.isConfigured) {
                        catalog.markNotConfigured(descriptor.id)
                        return@async
                    }
                    if (descriptor.modelsEndpoint == null) return@async
                    catalog.refresh(descriptor, connection.apiKey, force = false)
                }
            }.awaitAll()
            Unit
        }

    fun selectProvider(providerId: String) {
        _state.update { it.copy(selectedProviderId = providerId, notice = null, error = null) }
    }

    /** Make [providerId] the provider other plugins get from `activeConfig()`. */
    fun setActiveProvider(providerId: String) {
        scope.launch {
            prefs.write(providerId)
            _state.update { it.copy(activeProviderId = providerId, notice = null) }
        }
    }

    fun updateKeyDraft(providerId: String, value: String) {
        _state.update { it.copy(keyDrafts = it.keyDrafts + (providerId to value), error = null, notice = null) }
    }

    /** Persist the draft key for [providerId], then refresh its model list. */
    fun saveKey(providerId: String) {
        val draft = _state.value.keyDrafts[providerId]?.trim().orEmpty()
        val currentStore = store
        if (currentStore == null) {
            _state.update { it.copy(error = "Sign in to store credentials.") }
            return
        }
        if (draft.isBlank()) {
            _state.update { it.copy(error = "Enter an API key first.") }
            return
        }

        scope.launch {
            withBusy(providerId) {
                currentStore
                    .saveKey(providerId, draft)
                    .onSuccess {
                        // Drop the draft so the field stops holding key material.
                        _state.update {
                            it.copy(
                                keyDrafts = it.keyDrafts - providerId,
                                notice = "Saved.",
                            )
                        }
                        reloadConnections()
                        refreshOne(providerId, force = true)
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not save the key.") }
                    }
            }
        }
    }

    /** Remove the stored key for [providerId]. */
    fun clearKey(providerId: String) {
        val currentStore = store ?: return
        scope.launch {
            withBusy(providerId) {
                currentStore
                    .clearKey(providerId)
                    .onSuccess {
                        _state.update {
                            it.copy(keyDrafts = it.keyDrafts - providerId, notice = "Removed.")
                        }
                        reloadConnections()
                        catalog.markNotConfigured(providerId)
                    }.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not remove the key.") }
                    }
            }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        val currentStore = store
        val existing = _state.value.connectionOf(providerId)

        // Reflect immediately; persistence follows. The picker should not appear to
        // reject a choice while a round-trip to the store completes.
        _state.update {
            it.copy(
                connections = it.connections + (providerId to existing.copy(selectedModelId = modelId)),
                notice = null,
            )
        }

        scope.launch {
            val settings =
                ProviderSettings(
                    selectedModelId = modelId,
                    customEndpoint = existing.customEndpoint,
                    temperature = existing.temperature,
                    maxTokens = existing.maxTokens,
                )

            val written =
                currentStore?.saveSettings(providerId, settings)
                    ?.onFailure { error ->
                        // Revert rather than leaving the picker showing an unsaved choice.
                        _state.update {
                            it.copy(
                                connections = it.connections + (providerId to existing),
                                error = error.message ?: "Could not save the model choice.",
                            )
                        }
                    }?.getOrNull()

            // No secret to attach settings to (an env-keyed provider), so the choice goes
            // to prefs — see ActiveProviderPrefs.readModels.
            if (written == false) prefs.writeModel(providerId, modelId)
        }
    }

    /** Persist a custom provider's endpoint, which has no models endpoint to discover. */
    fun setCustomEndpoint(providerId: String, endpoint: String) {
        val existing = _state.value.connectionOf(providerId)
        val trimmed = endpoint.trim()
        _state.update {
            it.copy(connections = it.connections + (providerId to existing.copy(customEndpoint = trimmed)))
        }
        scope.launch {
            val settings =
                ProviderSettings(
                    selectedModelId = existing.selectedModelId,
                    customEndpoint = trimmed.takeIf { it.isNotBlank() },
                    temperature = existing.temperature,
                    maxTokens = existing.maxTokens,
                )
            store?.saveSettings(providerId, settings)?.onFailure { error ->
                _state.update { it.copy(error = error.message ?: "Could not save the endpoint.") }
            }
        }
    }

    /**
     * Set a model id by hand, for a provider with no models endpoint to ask.
     *
     * Without this a custom endpoint could never be used: `activeConfig()` requires a
     * model id, and the picker is fed only from a fetched list.
     */
    fun setManualModelId(providerId: String, modelId: String) = selectModel(providerId, modelId.trim())

    /** Re-fetch [providerId]'s model list, bypassing the TTL. */
    fun refreshModels(providerId: String) {
        scope.launch { withBusy(providerId) { refreshOne(providerId, force = true) } }
    }

    /**
     * Probe the credential by asking the provider for its model list — the cheapest
     * authenticated call that proves the key works, with no completion tokens spent.
     */
    fun testConnection(providerId: String) {
        scope.launch {
            withBusy(providerId) {
                val descriptor = ProviderRegistry.find(providerId) ?: return@withBusy
                val connection = _state.value.connectionOf(providerId)
                if (!connection.isConfigured) {
                    _state.update { it.copy(error = "Add an API key first.") }
                    return@withBusy
                }
                refreshOne(providerId, force = true)
                val outcome = catalog.stateOf(providerId)
                _state.update {
                    when (outcome) {
                        is CatalogState.Loaded ->
                            it.copy(
                                notice = "${descriptor.displayName} responded — ${outcome.models.size} models available.",
                                error = null,
                            )
                        is CatalogState.Failed -> it.copy(error = outcome.message, notice = null)
                        else -> it
                    }
                }
            }
        }
    }

    /** Open the provider's console so the user can create a key. */
    fun openProviderConsole(providerId: String) {
        val descriptor = ProviderRegistry.find(providerId) ?: return
        val url = descriptor.consoleUrl
        if (url == null) {
            _state.update { it.copy(error = "${descriptor.displayName} has no key console.") }
            return
        }
        val operations = splitViewOperations
        if (operations == null) {
            _state.update { it.copy(notice = "Open $url to create a key.") }
            return
        }
        operations.openUrlInActivePanel(url, "${descriptor.displayName} API keys", forceNewTab = true)
    }

    fun importLegacyKeys() {
        val importer = legacyImport ?: return
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            importer
                .import()
                .onSuccess { imported ->
                    _state.update {
                        it.copy(
                            legacyOffer = null,
                            isLoading = false,
                            notice =
                                if (imported.isEmpty()) {
                                    "Nothing to import."
                                } else {
                                    "Imported ${imported.size} key(s). Pick a model for each provider."
                                },
                        )
                    }
                    reloadConnections()
                    refreshStale(_state.value.connections)
                }.onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, error = error.message ?: "Import failed.")
                    }
                }
        }
    }

    fun dismissLegacyOffer() {
        _state.update { it.copy(legacyOffer = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    private suspend fun checkLegacyImport() {
        val offer = legacyImport?.inspect() ?: return
        _state.update { it.copy(legacyOffer = offer) }
        logger.info(
            LogCategory.SYSTEM,
            "Legacy AI provider keys available to import",
            mapOf("providers" to offer.providerIds.size),
        )
    }

    private suspend fun reloadConnections() {
        val reloaded = store?.loadAll() ?: return
        _state.update { it.copy(connections = withPreferredModels(reloaded.connections)) }
    }

    private suspend fun refreshOne(providerId: String, force: Boolean) {
        val descriptor = ProviderRegistry.find(providerId) ?: return
        val connection = _state.value.connectionOf(providerId)
        if (!connection.isConfigured) {
            catalog.markNotConfigured(providerId)
            return
        }
        catalog.refresh(descriptor, connection.apiKey, force = force)
    }

    private suspend fun withBusy(providerId: String, block: suspend () -> Unit) {
        _state.update { it.copy(busyProviderIds = it.busyProviderIds + providerId) }
        try {
            block()
        } finally {
            _state.update { it.copy(busyProviderIds = it.busyProviderIds - providerId) }
        }
    }
}
