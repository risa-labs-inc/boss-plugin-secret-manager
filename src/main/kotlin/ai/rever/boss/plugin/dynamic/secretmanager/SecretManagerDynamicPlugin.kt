package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretDataProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Secret Manager dynamic plugin - Loaded from external JAR.
 *
 * Manage encrypted credentials and secrets with CRUD and sharing.
 */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.secretmanager"
    override val displayName: String = "Secret Manager (Dynamic)"
    override val version: String = "1.0.3"
    override val description: String = "Manage encrypted credentials and secrets"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    private var secretDataProvider: SecretDataProvider? = null
    private var pluginScope: CoroutineScope? = null

    override fun register(context: PluginContext) {
        // Capture providers from context
        secretDataProvider = context.secretDataProvider
        pluginScope = context.pluginScope

        context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
            SecretManagerComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                secretDataProvider = secretDataProvider,
                scope = pluginScope ?: error("Plugin scope not available")
            )
        }
    }
}
