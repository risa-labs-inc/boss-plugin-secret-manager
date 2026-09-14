package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.AiAvailableModel
import ai.rever.boss.plugin.api.AiModelPricing
import ai.rever.boss.plugin.api.AiProviderModels
import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmModelPricingAPI
import ai.rever.boss.plugin.api.LlmProviderSettingsAPI
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Serves AI provider configuration to the host and to other plugins.
 *
 * [availableModels] references [AiProviderModels]/[AiAvailableModel], and this class implements
 * [LlmModelPricingAPI], assigned to API 1.0.92 by PR #59. That manifest floor must be retained
 * when publishing: a `LinkageError` guard around construction cannot protect a lazily resolved
 * method signature or a host's binary compatibility scan.
 *
 * Reads state from [AiProvidersViewModel] rather than the store directly, so the
 * panel and API can never disagree about which provider is active.
 */
class LlmProviderSettingsApiImpl(
    private val viewModel: AiProvidersViewModel,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) : LlmProviderSettingsAPI, LlmModelPricingAPI {

    private companion object {
        val logger = BossLogger.forComponent("LlmProviderSettingsApi")
    }

    /** This implementation does render a panel, so the host shouldn't show its notice. */
    override val supportsSettingsPanel: Boolean = true

    @Composable
    override fun LlmProviderSettingsPanel(modifier: Modifier) {
        // The shared panel owns entry loading for both rendering paths.
        AiProvidersPanel(viewModel = viewModel, modifier = modifier)
    }

    /**
     * The active provider's configuration, or null when nothing usable is set up.
     *
     * Null whenever there is no key or no chosen model: a config missing either is not
     * something a caller can send a request with, and returning a half-populated one
     * would push that check onto every consumer. [configuredProviders] deliberately has
     * a different contract for consumers that own model selection.
     * Reads also request a stale-catalog sweep for all providers on the ViewModel's
     * retry floor. Fresh catalogs do not make HTTP requests; this keeps non-shared
     * model discovery current as well as discovering shared defaults.
     */
    override fun activeConfig(): LlmConfig? {
        // Callers can reach this before the settings panel has ever been rendered, so
        // credentials and catalogs are loaded on demand. Shared defaults are catalog-derived;
        // loading credentials alone would leave the first consumer without a model forever.
        viewModel.ensureCatalogsLoaded()
        val state = viewModel.state.value
        val providerId = state.activeProviderId ?: return null
        return configFor(providerId)
    }

    override fun configuredProviders(): List<LlmConfig> {
        // This also starts model discovery, so keyless providers do not appear or disappear
        // depending on whether another consumer happened to ask for models first.
        viewModel.ensureCatalogsLoaded()
        val state = viewModel.state.value
        // Consumers choosing a model may receive an empty modelId for model-independent
        // endpoints. Consumers that need a ready-to-send default use activeConfig instead.
        return state.providers.mapNotNull { descriptor ->
            // Build first: configFor is also the bounded retry hook for a failed brokered
            // mint. Filtering an unconfigured broker before this call made one failed mint
            // terminal on the configuredProviders path.
            val config = configFor(descriptor.id, requireModel = false) ?: return@mapNotNull null
            if (isProviderListed(
                    descriptor,
                    state.connectionOf(descriptor.id),
                    viewModel.catalogStateOf(descriptor.id),
                    // addedProviderIds is a panel-only affordance. A machine-readable list
                    // must not publish an unreachable local daemon because the user clicked Add.
                    wasAddedByUser = false,
                )) config else null
        }
    }

    /**
     * Every configured provider's models, credential-free — see the api doc on
     * [LlmProviderSettingsAPI.availableModels] for why this exists separately from
     * [configuredProviders].
     *
     * Sourced from whatever this plugin already fetched into its live model catalog
     * ([AiProvidersUiState.catalogs]), the same data the settings panel's picker reads.
     * A provider needing manual entry (only [ProviderRegistry.CUSTOM] today) has no
     * catalog to ask, so its one model comes from whatever the user typed instead.
     * Discovery is asynchronous because this api is non-suspending. An empty result can mean
     * that the first bounded sweep is still running, so consumers should read again rather than
     * cache absence forever.
     */
    override fun availableModels(): List<AiProviderModels> {
        viewModel.ensureCatalogsLoaded()
        val state = viewModel.state.value
        return state.providers.mapNotNull { descriptor ->
            val connection = state.connectionOf(descriptor.id)
            val catalog = viewModel.catalogStateOf(descriptor.id)
            if (!isProviderListed(descriptor, connection, catalog, wasAddedByUser = false)) {
                return@mapNotNull null
            }
            // Model discovery does not need a sendable completions URL. In particular, Google
            // model ids must be discoverable before one has been selected for its URL path.
            if (descriptor.id == ProviderRegistry.CUSTOM && connection.customEndpoint.isNullOrBlank()) {
                return@mapNotNull null
            }

            val models =
                if (ProviderRegistry.needsManualModel(descriptor)) {
                    connection.selectedModelId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { listOf(AiAvailableModel(id = it, displayName = it)) }
                        ?: return@mapNotNull null
                } else {
                    when (catalog) {
                        is CatalogState.Loaded -> catalog.models
                        is CatalogState.Failed -> catalog.lastKnown?.models
                        else -> null
                    }
                        ?.map { AiAvailableModel(id = it.id, displayName = it.displayName, contextLength = it.contextLength) }
                        ?: return@mapNotNull null
                }

            AiProviderModels(providerId = descriptor.id, providerName = descriptor.displayName, models = models)
        }
    }

    /**
     * Return a fresh, complete rate card for one exact provider/model pair.
     *
     * This never falls back to [CatalogState.Failed.lastKnown]: an old model name is useful in
     * a picker, while an old rate must not authorize another budgeted call. Catalog refresh is
     * requested asynchronously; until it lands, the safe synchronous answer is null.
     * Consumers pricing a catalog should retain returned cards through [AiModelPricing.validUntilEpochMs]
     * rather than repeat this provider/model lookup for every rendered row.
     * This API is synchronous and non-throwing by contract. Its containment includes a
     * synchronously thrown `CancellationException`; there is no suspending work to cancel here.
     */
    override fun modelPricing(providerId: String, modelId: String): AiModelPricing? =
        // Non-suspending on purpose: the API boundary contains even malformed host linkage.
        runCatching {
            viewModel.ensureCatalogsLoaded()
            val state = viewModel.state.value
            // Ambiguous ids must not authorize a budgeted call, even if the picker shows one.
            val descriptor =
                state.providers.singleOrNull { it.id == providerId } ?: return@runCatching null
            val catalog =
                viewModel.catalogStateOf(providerId) as? CatalogState.Loaded
                    ?: return@runCatching null
            val connection = state.connectionOf(providerId)
            if (!isProviderListed(descriptor, connection, catalog, wasAddedByUser = false)) {
                return@runCatching null
            }
            val ttl = ModelCatalog.ttlFor(providerId)
            val validUntil = catalog.fetchedAtEpochMs + ttl
            val now = nowEpochMs()
            if (validUntil < catalog.fetchedAtEpochMs || catalog.fetchedAtEpochMs > now || now > validUntil) {
                return@runCatching null
            }
            val pricing =
                catalog.models.singleOrNull { it.id == modelId }?.pricing
                    ?: return@runCatching null
            AiModelPricing(
                providerId = providerId,
                modelId = modelId,
                inputUsdPer1M = pricing.inputUsdPer1M,
                outputUsdPer1M = pricing.outputUsdPer1M,
                source = AiModelPricing.SOURCE_PROVIDER_CATALOG,
                fetchedAtEpochMs = catalog.fetchedAtEpochMs,
                validUntilEpochMs = validUntil,
            )
        }.fold(onSuccess = { it }, onFailure = {
            // No ids, payloads, exception messages or credentials cross this log boundary.
            val exceptionClass = it.javaClass.simpleName
            runCatching {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Model pricing lookup failed",
                    mapOf("exception" to exceptionClass),
                )
            }
            null
        })

    private fun configFor(providerId: String, requireModel: Boolean = true): LlmConfig? {
        // Every path that hands out a credential goes through here - `activeConfig` and
        // `configuredProviders` both - so this is where a lapsed brokered credential has to be
        // noticed. Hooking only `activeConfig` left `configuredProviders` handing out the same
        // dead token, which is the identical wedge one method over. A no-op for providers that
        // are not brokered, so the fan-out is safe.
        viewModel.refreshLapsedBrokeredCredential(providerId)
        val state = viewModel.state.value
        val descriptor = viewModel.descriptorOf(providerId) ?: return null
        val storedConnection = state.connections[providerId] ?: return null
        val connection = effectiveSharedConnection(storedConnection, viewModel.catalogStateOf(providerId))
        if (!hasUsableProviderConnection(descriptor, connection, requireModel)) return null

        val modelId = connection.selectedModelId.orEmpty().trim()

        val endpoint =
            when {
                // A custom provider's endpoint is user-supplied; without it there is
                // nothing to call.
                descriptor.id == ProviderRegistry.CUSTOM ->
                    connection.customEndpoint?.takeIf { it.isNotBlank() }?.trim() ?: return null

                else -> descriptor.chatEndpointFor(modelId)
            }

        val apiFormat = descriptor.wireFormat.toApiFormat()

        return LlmConfig(
            providerId = descriptor.id,
            displayName = descriptor.displayName,
            apiFormat = apiFormat,
            apiKey = connection.apiKey,
            baseUrl = endpoint,
            modelId = modelId,
            temperature = connection.temperature,
            maxTokens = connection.maxTokens,
        )
    }

    /**
     * Map the plugin-local wire format onto the api enum. Every constant here predates
     * the manifest's 1.0.92 floor, so reflective compatibility branches would be dead code.
     */
    private fun WireFormat.toApiFormat(): LlmApiFormat =
        when (this) {
            WireFormat.ANTHROPIC_MESSAGES -> LlmApiFormat.ANTHROPIC_MESSAGES
            WireFormat.OPENAI_CHAT -> LlmApiFormat.OPENAI_CHAT
            WireFormat.GOOGLE_GENERATIVE -> LlmApiFormat.GOOGLE_GENERATIVE
            WireFormat.OPENAI_RESPONSES -> LlmApiFormat.OPENAI_RESPONSES
        }
}
