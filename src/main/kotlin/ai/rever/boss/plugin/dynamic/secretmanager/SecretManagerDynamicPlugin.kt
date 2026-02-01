package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Secret Manager dynamic plugin - Loaded from external JAR.
 *
 * Manage encrypted credentials and secrets
 */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.secretmanager"
    override val displayName: String = "Secret Manager (Dynamic)"
    override val version: String = "1.0.0"
    override val description: String = "Manage encrypted credentials and secrets"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
            SecretManagerComponent(ctx, panelInfo)
        }
    }
}
