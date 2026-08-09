package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SettingsProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Secret Manager panel component (Dynamic Plugin)
 *
 * Provides full secret management with CRUD and sharing operations.
 * Uses SecretDataProvider, SupabaseDataProvider, and PluginStoreApiKeyProvider from PluginContext.
 */
class SecretManagerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val secretDataProvider: SecretDataProvider?,
    private val supabaseDataProvider: SupabaseDataProvider?,
    private val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?,
    private val scope: CoroutineScope,
    private val aiProviderStore: ProviderCredentialStore? = null,
    private val settingsProvider: SettingsProvider? = null,
    private val windowId: String? = null,
    private val splitViewOperations: SplitViewOperations? = null,
    private val authDataProvider: AuthDataProvider? = null
) : PanelComponentWithUI, ComponentContext by ctx {

    // Created once per panel instance (not per composition), so secrets stay
    // cached across panel switches — reopening renders instantly instead of
    // refetching. Same pattern as the Role Creation plugin; the Refresh
    // button refetches on demand.
    private val viewModel = SecretManagerViewModel(
        secretDataProvider,
        supabaseDataProvider,
        pluginStoreApiKeyProvider,
        scope,
        aiProviderStore,
        settingsProvider,
        windowId,
        splitViewOperations,
        authDataProvider
    ).also { it.initialize() }

    @Composable
    override fun Content() {
        SecretManagerContent(viewModel)
    }
}
