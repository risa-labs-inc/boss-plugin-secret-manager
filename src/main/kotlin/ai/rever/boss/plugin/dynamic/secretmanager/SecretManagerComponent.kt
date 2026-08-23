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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
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
        // Named: nine positional arguments, five of them adjacent nullables, is the call site
        // a reorder mis-binds silently while still compiling.
        secretDataProvider = secretDataProvider,
        supabaseDataProvider = supabaseDataProvider,
        pluginStoreApiKeyProvider = pluginStoreApiKeyProvider,
        scope = scope,
        aiProviderStore = aiProviderStore,
        settingsProvider = settingsProvider,
        windowId = windowId,
        splitViewOperations = splitViewOperations,
        authDataProvider = authDataProvider,
    ).also { it.initialize() }

    /**
     * The read-only "Shared with me" section's state. A second ViewModel rather than more
     * fields on the first: it reads a different RPC
     * (`getUserSecretsWithSharingInfo`), pages independently, and has no write path at all -
     * folding it into a 1,100-line ViewModel that guards credential writes would put the two
     * on one load path for no gain.
     *
     * It does not fetch on construction. See [SharedSecretsViewModel.ensureLoaded].
     */
    private val sharedSecretsViewModel = SharedSecretsViewModel(secretDataProvider, scope)

    /**
     * Which section is on screen. Held here, not `remember`ed in the panel, for the same
     * reason the ViewModels are: the panel leaves composition every time the user switches to
     * another sidebar panel, and a remembered value would drop them back on "Secrets".
     */
    private var selectedSection by mutableStateOf(SecretPanelSection.SECRETS)

    init {
        // The ViewModels are per panel instance but their coroutines run on the *plugin*
        // scope, so the permission collector - which never completes - would keep this
        // instance, and the decrypted secrets in its state, alive for the plugin's whole
        // lifetime. Every other launch in the ViewModel terminates, which is why this
        // hook only became necessary once something collected.
        //
        // The shared-secrets load is cancelled here for the same reason: its auto-continue
        // scan can be several round trips deep when the panel goes away.
        lifecycle.doOnDestroy {
            viewModel.dispose()
            sharedSecretsViewModel.dispose()
        }
    }

    @Composable
    override fun Content() {
        SecretManagerContent(
            viewModel = viewModel,
            sharedSecretsViewModel = sharedSecretsViewModel,
            selectedSection = selectedSection,
            onSelectSection = { selectedSection = it },
        )
    }
}
