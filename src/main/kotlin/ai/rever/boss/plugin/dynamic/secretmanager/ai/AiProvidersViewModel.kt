package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val envResolver: EnvResolver,
    /**
     * Floor on how often a brokered refresh may run.
     *
     * A parameter rather than a constant so the floor itself is testable: with it hard-coded,
     * every test of the refresh had to either wait it out or be written around it, which is how
     * an untested guard ends up wrong.
     */
    private val minBrokeredRefreshIntervalMs: Long = DEFAULT_MIN_BROKERED_REFRESH_INTERVAL_MS,
    // Injectable for the same reason as the interval above: a test that has to wait two minutes to
    // observe a renewal is a test nobody runs.
    private val brokeredRenewalLeadMs: Long = BROKERED_RENEWAL_LEAD_MS,
    private val minBrokeredRenewalDelayMs: Long = MIN_BROKERED_RENEWAL_DELAY_MS,
) {
    private val logger = BossLogger.forComponent("AiProvidersViewModel")

    private val _state = MutableStateFlow(AiProvidersUiState(storeAvailable = store != null))
    val state: StateFlow<AiProvidersUiState> = _state.asStateFlow()

    /** Guards [ensureConnectionsLoaded] so concurrent callers load credentials once. */
    private val connectionsLoadStarted = AtomicBoolean(false)

    /** The armed renewal, replaced on each reload rather than stacked. */
    private var brokeredRenewalJob: Job? = null

    /** Guards [refreshLapsedBrokeredCredential] so a burst of reads triggers one reload. */
    private val brokeredRefreshInFlight = AtomicBoolean(false)

    /**
     * When the last brokered refresh *finished*, as a floor on how often one may run.
     *
     * The in-flight guard serialises refreshes but does not space them. A window that collapses to
     * zero - a gateway minting keys shorter than the safety margin, or a local clock running ahead
     * of the broker's - makes `brokeredCredentialLapsed` true again immediately after every
     * successful mint, so a polling consumer would otherwise drive a continuous back-to-back loop
     * of broker round-trips. (Not a re-page of the secret store: `loadStoredSecrets` returns from
     * `cached` unless `invalidate()` has run.)
     *
     * `nanoTime`, because this measures an elapsed duration and a wall clock that steps backwards
     * would make the difference negative and disable the refresh for the length of the step -
     * which is the same symptom as the wedge this whole change fixes.
     *
     * Stamped on completion rather than at the start, so a reload slower than the interval does
     * not leave the next read immediately eligible.
     */
    private val lastBrokeredRefreshNanos = AtomicLong(Long.MIN_VALUE / 2)

    private val _connectionsLoaded = MutableStateFlow(false)

    init {
        scope.launch { catalog.states.collect { states -> _state.update { it.copy(catalogs = states) } } }

        // Re-read credentials whenever the store is invalidated — which is what the secret
        // list's own create/update/delete does. Clearing the store cache alone was not
        // enough: activeConfig() answers other plugins from the snapshot below, so a secret
        // deleted from the list kept being served for the session unless the user happened
        // to open Settings → AI Providers. drop(1) skips the initial value; only real
        // invalidations should trigger a read.
        store?.let { credentialStore ->
            scope.launch {
                credentialStore.invalidations.drop(1).collect {
                    if (connectionsLoadStarted.get()) reloadConnections()
                }
            }
        }
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
        // Arm the renewal from the *first* load too, not only from later reloads: this is the path
        // a consumer's very first `activeConfig()` takes, and without this the timer would only
        // start once something else happened to reload.
        scheduleBrokeredRenewal()
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
        val endpoints = prefs.readCustomEndpoints()
        if (preferred.isEmpty() && endpoints.isEmpty()) return connections
        return connections.mapValues { (providerId, connection) ->
            // A stored secret's settings win: prefs are the fallback for providers that
            // have no secret to attach settings to, not a second source of truth.
            var merged = connection
            if (merged.selectedModelId == null) {
                merged = preferred[providerId]?.let { merged.copy(selectedModelId = it) } ?: merged
            }
            if (merged.customEndpoint.isNullOrBlank()) {
                merged = endpoints[providerId]?.let { merged.copy(customEndpoint = it) } ?: merged
            }
            merged
        }
    }

    /** Load credentials, seed cached model lists, then refresh anything stale. */
    fun load() {
        connectionsLoadStarted.set(true)
        scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }

            catalog.seedFromCache()
            // Re-read the environment on every entry into the section. The panel tells
            // users they can unset a variable to take key management over in BOSS, and the
            // resolver memoises misses as well as hits — so without this that instruction
            // was only true after an app restart.
            envResolver.invalidate()
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
        // The injected resolver, so this degraded path reuses the memo cache instead of
        // re-spawning launchctl per provider exactly when latency is already bad.
        val resolver = envResolver
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
                    if (!ProviderRegistry.hasKnownModels(descriptor)) return@async
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
        // Blanks are never a valid selection, and nothing downstream but a takeIf in
        // configFor stopped one reaching a caller. Refusing here kills the class.
        if (modelId.isBlank()) return

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

            val result =
                currentStore?.saveSettings(providerId, settings)
                    ?.onFailure { error ->
                        // Revert rather than leaving the picker showing an unsaved choice.
                        _state.update {
                            it.copy(
                                connections = it.connections + (providerId to existing),
                                error = error.message ?: "Could not save the model choice.",
                            )
                        }
                    }

            // Three genuinely distinct outcomes, and the Result has to be kept to tell them
            // apart: null = no store at all (not signed in), success(false) = no secret to
            // attach settings to (an env-keyed provider) — both fall back to prefs, which is
            // the only place the choice could live. A *failure* must not: getOrNull()
            // collapsed it into null, so a save that failed still wrote prefs, and the
            // overlay in withPreferredModels then re-applied the choice the user was just
            // told wasn't saved.
            if (result == null || result.getOrNull() == false) prefs.writeModel(providerId, modelId)
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
            val written =
                store?.saveSettings(providerId, settings)
                    ?.onFailure { error ->
                        _state.update { it.copy(error = error.message ?: "Could not save the endpoint.") }
                    }?.getOrNull()

            // Written unconditionally, not only when the secret write didn't happen — which
            // does mean a *failed* store write still takes effect via the prefs overlay.
            // Accepted deliberately here: an endpoint the user typed is worth keeping over
            // losing it to a transient store failure, and unlike a model id it can be
            // cleared, so a wrong value is recoverable.
            // withPreferredModels re-overlays prefs whenever the stored endpoint is blank,
            // so a write-only fallback let a cleared endpoint come back from prefs on the
            // next load: set A with no key (prefs = A), add a key, clear the endpoint
            // (stored = null, prefs still A), reload -> A returns. Keeping prefs in step
            // makes clearing stick; writeCustomEndpoint deletes the entry on blank.
            prefs.writeCustomEndpoint(providerId, trimmed)
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
     * Re-ask a brokered provider's broker, and report what came back.
     *
     * The credential is cached for as long as the broker said it may be reused, so
     * without this there is no way to retry after signing in - the panel would keep
     * showing the failure it cached. Invalidating the store first is what forces the
     * next resolve to go back to the broker rather than serve the cached answer.
     */
    fun refreshBrokeredCredential(providerId: String) {
        val currentStore = store ?: return
        scope.launch {
            withBusy(providerId) {
                currentStore.invalidate()
                reloadConnections()
                val configured = _state.value.connectionOf(providerId).isConfigured
                _state.update {
                    if (configured) {
                        it.copy(notice = "Access confirmed.", error = null)
                    } else {
                        it.copy(
                            error = "No access yet. Sign in to BOSS with an account that has access.",
                            notice = null,
                        )
                    }
                }
            }
        }
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
        val offer = legacyImport?.inspectAndRetireIfEmpty() ?: return
        _state.update { it.copy(legacyOffer = offer) }
        logger.info(
            LogCategory.SYSTEM,
            "Legacy AI provider keys available to import",
            mapOf("providers" to offer.providerIds.size),
        )
    }

    /**
     * Re-mint [providerId]'s brokered credential if the cached one is past its reuse deadline.
     *
     * [ensureConnectionsLoaded] loads once and never again, so `state.connections` keeps
     * serving whatever token that load captured. The expiry cap in `ProviderCredentialStore`
     * cannot help on its own, because nothing calls `loadAll` between a panel visit and a
     * secret edit - which is exactly the path a consumer like LLM RPA takes, and exactly how a
     * dead key got re-sent for eleven minutes.
     *
     * Asynchronous because `activeConfig()` cannot suspend, so **this call still returns the
     * stale token** and the next one is fresh. That trades one failed request for a wedge
     * lasting the whole window, which is the right way round; making it synchronous would mean
     * blocking a non-suspending api on a network mint.
     *
     * A no-op for providers that are not brokered, and while a refresh is already running.
     */
    fun refreshLapsedBrokeredCredential(providerId: String) {
        val brokerId = ProviderRegistry.find(providerId)?.brokerId ?: return
        val credentials = store ?: return
        if (!credentials.brokeredCredentialLapsed(brokerId)) return
        if (System.nanoTime() - lastBrokeredRefreshNanos.get() < minBrokeredRefreshIntervalMs * NANOS_PER_MILLI) {
            return
        }
        if (!brokeredRefreshInFlight.compareAndSet(false, true)) return
        // A refresh costs a broker round-trip, not a secret-store re-page: `loadStoredSecrets`
        // returns from `cached` unless `invalidate()` has run. The floor is about the mint rate.
        //
        // IO because this can now fire from any consumer read, and `pluginScope` falls back to
        // Dispatchers.Main. runCatching because a host `exchange`/`listSecrets` that throws rather
        // than returning a failed Result would escape and cancel the scope - and a plain
        // CoroutineScope(Main) is not a supervisor, so that would silently kill every later launch
        // in the plugin. invokeOnCompletion rather than finally: if the scope is already cancelled
        // the body never runs, and the flag would latch true forever - the same shape of latch as
        // the bug this PR fixes.
        scope
            .launch(Dispatchers.IO) { runCatching { reloadConnections() } }
            .invokeOnCompletion {
                lastBrokeredRefreshNanos.set(System.nanoTime())
                brokeredRefreshInFlight.set(false)
            }
    }

    private suspend fun reloadConnections() {
        val credentials = store ?: return
        // Mirrors the store's own generation guard, one layer up. The store refuses to seat a
        // `cached`/`brokeredCache` value from before an `invalidate()`, but the value consumers
        // actually read is `_state.connections`, and that write was unguarded: a refresh already
        // in flight when the user signs out or hits "Check access" could land last and re-seat the
        // previous session's brokered token. Pre-existing, and far more likely now that any
        // consumer read can start a reload.
        val startedAt = credentials.invalidations.value
        val reloaded = credentials.loadAll()
        if (credentials.invalidations.value != startedAt) return
        _state.update { it.copy(connections = withPreferredModels(reloaded.connections)) }
        scheduleBrokeredRenewal()
    }

    /**
     * Renew a brokered credential shortly *before* it stops being reusable.
     *
     * Everything else on this path is reactive: the credential is replaced only once a read
     * notices it is already dead, which costs one failed request every time one expires while the
     * app is running - and the user sees "The provider rejected the credential. Check Settings",
     * which reads as something they have to go and fix by hand. They do not: access is already
     * granted, the key is just short-lived, so renewing it is this plugin's job and it should
     * happen before anything asks.
     *
     * Rescheduled from [reloadConnections], so each successful renewal arms the next one. Cancelled
     * and replaced rather than stacked, because several reads can reload in quick succession.
     */
    private fun scheduleBrokeredRenewal() {
        val credentials = store ?: return
        val deadline = credentials.nextBrokeredReuseDeadline() ?: return
        val delayMs =
            (deadline - System.currentTimeMillis() - brokeredRenewalLeadMs)
                // Never zero: a mint that keeps failing, or a deadline already in the past, would
                // otherwise spin this loop as fast as the broker answers.
                .coerceAtLeast(minBrokeredRenewalDelayMs)
        brokeredRenewalJob?.cancel()
        brokeredRenewalJob =
            scope.launch(Dispatchers.IO) {
                delay(delayMs)
                // Drop the cache first, or this renews nothing: resolveBrokered serves the cached
                // token while its reuse window is open, so a reload before the deadline would hand
                // back the same credential. That made the first version of this a poller for
                // lapse rather than a renewal.
                credentials.expireBrokeredCache()
                // runCatching for the same reason as refreshLapsedBrokeredCredential: a host
                // exchange that throws rather than returning a failed Result would escape and
                // cancel this scope, which is not a supervisor.
                runCatching { reloadConnections() }
            }
    }

    private suspend fun refreshOne(providerId: String, force: Boolean) {
        val descriptor = ProviderRegistry.find(providerId) ?: return
        val connection = _state.value.connectionOf(providerId)
        if (!connection.isConfigured) {
            catalog.markNotConfigured(providerId)
            return
        }
        // Same skip refreshStale does. Without it, saving a Custom key fetched against a
        // nonexistent endpoint, logged a NETWORK warn, and parked a Failed state the panel
        // then hides — noise with no symptom. A provider with a FIXED list is not skipped:
        // it has models to seat, just no endpoint to ask.
        if (!ProviderRegistry.hasKnownModels(descriptor)) return
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

    private companion object {
        /**
         * Floor on how often a brokered refresh may run.
         *
         * Bounds the worst case when a credential is always immediately lapsed, which is what a
         * collapsed reuse window means. Short enough that a genuine renewal is not delayed in any
         * way a user would notice.
         */
        const val DEFAULT_MIN_BROKERED_REFRESH_INTERVAL_MS = 5_000L
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * How far ahead of a brokered credential's reuse deadline to renew it.
         *
         * Long enough to cover a mint round-trip and a slow network, short enough that a laptop
         * waking from sleep is usually still inside the window.
         */
        const val BROKERED_RENEWAL_LEAD_MS = 120_000L

        /** Floor on the armed delay, so a failing mint cannot spin the renewal loop. */
        const val MIN_BROKERED_RENEWAL_DELAY_MS = 60_000L
    }
}
