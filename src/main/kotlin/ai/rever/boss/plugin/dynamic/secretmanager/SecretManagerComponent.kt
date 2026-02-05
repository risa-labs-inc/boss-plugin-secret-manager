package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.UserManagementProvider
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Secret Manager panel component (Dynamic Plugin)
 *
 * Provides full secret management with CRUD and sharing operations.
 * Uses SecretDataProvider, UserManagementProvider, and PluginStoreApiKeyProvider from PluginContext.
 */
class SecretManagerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val secretDataProvider: SecretDataProvider?,
    private val userManagementProvider: UserManagementProvider?,
    private val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?,
    private val scope: CoroutineScope
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        SecretManagerContent(
            secretDataProvider = secretDataProvider,
            userManagementProvider = userManagementProvider,
            pluginStoreApiKeyProvider = pluginStoreApiKeyProvider,
            scope = scope
        )
    }
}
