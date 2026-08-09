package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.LlmApiFormat
import ai.rever.boss.plugin.api.LlmConfig
import ai.rever.boss.plugin.api.LlmProviderSettingsAPI
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * Serves AI provider configuration to the host and to other plugins.
 *
 * **This is the only file in the plugin that references api symbols introduced in
 * 1.0.71** ([LlmProviderSettingsAPI], [LlmApiFormat.GOOGLE_GENERATIVE]). Keeping the
 * new-api surface confined here is what lets `SecretManagerDynamicPlugin` register it
 * inside a `LinkageError` guard: on a host shipping an older api jar this class fails
 * to link, registration is skipped, and everything else in the plugin still works.
 * Referencing those symbols from the registry or the panel would instead take the
 * whole plugin down on such a host.
 *
 * Reads state from [AiProvidersViewModel] rather than the store directly, so the
 * panel and API can never disagree about which provider is active.
 */
class LlmProviderSettingsApiImpl(
    private val viewModel: AiProvidersViewModel,
) : LlmProviderSettingsAPI {

    /** This implementation does render a panel, so the host shouldn't show its notice. */
    override val supportsSettingsPanel: Boolean = true

    @Composable
    override fun LlmProviderSettingsPanel(modifier: Modifier) {
        // The host renders this section on demand; loading here (rather than at
        // registration) keeps provider fetches off app startup.
        LaunchedEffect(Unit) { viewModel.load() }
        AiProvidersPanel(viewModel = viewModel, modifier = modifier)
    }

    /**
     * The active provider's configuration, or null when nothing usable is set up.
     *
     * Null whenever there is no key or no chosen model: a config missing either is not
     * something a caller can send a request with, and returning a half-populated one
     * would push that check onto every consumer.
     */
    override fun activeConfig(): LlmConfig? {
        // Callers can reach this before the settings panel has ever been rendered, so
        // credentials are loaded on demand rather than only on panel entry.
        viewModel.ensureConnectionsLoaded()
        val state = viewModel.state.value
        val providerId = state.activeProviderId ?: return null
        return configFor(providerId)
    }

    override fun configuredProviders(): List<LlmConfig> {
        viewModel.ensureConnectionsLoaded()
        val state = viewModel.state.value
        return state.providers.mapNotNull { descriptor -> configFor(descriptor.id) }
    }

    private fun configFor(providerId: String): LlmConfig? {
        // Every path that hands out a credential goes through here - `activeConfig` and
        // `configuredProviders` both - so this is where a lapsed brokered credential has to be
        // noticed. Hooking only `activeConfig` left `configuredProviders` handing out the same
        // dead token, which is the identical wedge one method over. A no-op for providers that
        // are not brokered, so the fan-out is safe.
        viewModel.refreshLapsedBrokeredCredential(providerId)
        val state = viewModel.state.value
        val descriptor = ProviderRegistry.find(providerId) ?: return null
        val connection = state.connections[providerId] ?: return null
        if (!connection.isConfigured) return null

        val modelId = connection.selectedModelId?.takeIf { it.isNotBlank() } ?: return null

        val endpoint =
            when {
                // A custom provider's endpoint is user-supplied; without it there is
                // nothing to call.
                descriptor.id == ProviderRegistry.CUSTOM ->
                    connection.customEndpoint?.takeIf { it.isNotBlank() } ?: return null

                else -> descriptor.chatEndpointFor(modelId)
            }

        // A format this host's api copy does not have is not a config a caller could
        // send a request with, so it reports as unconfigured rather than half-usable.
        val apiFormat = descriptor.wireFormat.toApiFormat() ?: return null

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
     * Map the plugin-local wire format onto the api enum. Safe to reference
     * [LlmApiFormat.GOOGLE_GENERATIVE] here: it shipped in the same api release as
     * [LlmProviderSettingsAPI], so any host that could link this class has both.
     *
     * [LlmApiFormat.OPENAI_RESPONSES] is newer (1.0.74) and so is *not* covered by
     * that argument: a host on 1.0.71 links this class but has no such constant, and
     * reading it throws `NoSuchFieldError`. It is therefore resolved reflectively and
     * the provider is reported as unusable rather than crashing the section. Only
     * `RISA_GLM` speaks this format, and it needs a host new enough to have the
     * broker relay anyway, so on an older host it could not have worked regardless.
     */
    private fun WireFormat.toApiFormat(): LlmApiFormat? =
        when (this) {
            WireFormat.ANTHROPIC_MESSAGES -> LlmApiFormat.ANTHROPIC_MESSAGES
            WireFormat.OPENAI_CHAT -> LlmApiFormat.OPENAI_CHAT
            WireFormat.GOOGLE_GENERATIVE -> LlmApiFormat.GOOGLE_GENERATIVE
            WireFormat.OPENAI_RESPONSES -> openAiResponsesOrNull
        }

    private val openAiResponsesOrNull: LlmApiFormat? by lazy {
        try {
            LlmApiFormat.valueOf("OPENAI_RESPONSES")
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
