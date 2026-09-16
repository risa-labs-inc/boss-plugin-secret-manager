package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Human vault administration and explicit plugin/tool secret grants. */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = PluginVersionSource.PLUGIN_ID
    override val displayName: String = "Secret Manager (Dynamic)"
    override val version: String = PluginVersionSource.read()
    override val description: String = "Save, share and control access to encrypted credentials"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    override fun register(context: PluginContext) {
        val humanSecrets = context.secretDataProvider
        val scope = context.pluginScope ?: CoroutineScope(Dispatchers.Main)
        context.panelRegistry.registerPanel(SecretManagerInfo) { componentContext, panelInfo ->
            SecretManagerComponent(
                ctx = componentContext,
                panelInfo = panelInfo,
                secretDataProvider = humanSecrets,
                supabaseDataProvider = context.supabaseDataProvider,
                pluginStoreApiKeyProvider = context.pluginStoreApiKeyProvider,
                scope = scope,
                grantManager = context.secretGrantManager,
            )
        }

        context.secretAccessProvider?.let { scoped ->
            context.registerMcpToolProvider(SecretManagerMcpToolProvider(pluginId, scoped))
        }
        logger.info(LogCategory.SYSTEM, "Secret Manager registered")
    }

    override fun dispose() {
        logger.info(LogCategory.SYSTEM, "Secret Manager disposed")
    }

    private companion object {
        private val logger = BossLogger.forComponent("SecretManagerPlugin")
    }
}
