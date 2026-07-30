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

        return LlmConfig(
            providerId = descriptor.id,
            displayName = descriptor.displayName,
            apiFormat = descriptor.wireFormat.toApiFormat(),
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
     */
    private fun WireFormat.toApiFormat(): LlmApiFormat =
        when (this) {
            WireFormat.ANTHROPIC_MESSAGES -> LlmApiFormat.ANTHROPIC_MESSAGES
            WireFormat.OPENAI_CHAT -> LlmApiFormat.OPENAI_CHAT
            WireFormat.GOOGLE_GENERATIVE -> LlmApiFormat.GOOGLE_GENERATIVE
        }
}
