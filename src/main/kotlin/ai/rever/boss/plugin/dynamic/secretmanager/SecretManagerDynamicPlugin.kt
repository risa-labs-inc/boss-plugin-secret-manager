package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Secret Manager dynamic plugin - Loaded from external JAR.
 *
 * Manage encrypted credentials and secrets with CRUD and sharing.
 * Uses SecretDataProvider and UserManagementProvider from PluginContext.
 */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.secretmanager"
    override val displayName: String = "Secret Manager (Dynamic)"
    override val version: String = "1.0.4"
    override val description: String = "Manage encrypted credentials and secrets"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    override fun register(context: PluginContext) {
        val secretDataProvider = context.secretDataProvider
        val userManagementProvider = context.userManagementProvider
        val pluginScope = context.pluginScope ?: CoroutineScope(Dispatchers.Main)

        if (secretDataProvider == null) {
            // Provider not available - register stub
            context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
                SecretManagerComponent(ctx, panelInfo, null, null, pluginScope)
            }
            return
        }

        context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
            SecretManagerComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                secretDataProvider = secretDataProvider,
                userManagementProvider = userManagementProvider,
                scope = pluginScope
            )
        }
    }
}
