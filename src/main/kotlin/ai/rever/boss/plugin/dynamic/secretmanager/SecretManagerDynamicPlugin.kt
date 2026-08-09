package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ActiveProviderPrefs
import ai.rever.boss.plugin.dynamic.secretmanager.ai.AiProvidersViewModel
import ai.rever.boss.plugin.dynamic.secretmanager.ai.BrokeredCredentialBridge
import ai.rever.boss.plugin.dynamic.secretmanager.ai.EnvResolver
import ai.rever.boss.plugin.dynamic.secretmanager.ai.LegacySettingsImport
import ai.rever.boss.plugin.dynamic.secretmanager.ai.LlmProviderSettingsApiImpl
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ModelCatalog
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Secret Manager dynamic plugin - Loaded from external JAR.
 *
 * Manage encrypted credentials and secrets with CRUD and sharing.
 * Uses SecretDataProvider, SupabaseDataProvider, and PluginStoreApiKeyProvider from PluginContext.
 */
class SecretManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = PluginVersionSource.PLUGIN_ID
    override val displayName: String = "Secret Manager (Dynamic)"
    /** Resolved from the bundled `plugin.json` — see [PluginVersionSource] for why not the manifest. */
    override val version: String = PluginVersionSource.read()
    override val description: String = "Manage encrypted credentials and secrets, including Plugin Store API keys"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-secret-manager"

    private companion object {
        /** api release that introduced LlmProviderSettingsAPI. */
        const val REQUIRED_API_VERSION = "1.0.71"

        /**
         * Deliberately on the companion, not an instance property.
         *
         * A `ComponentLogger`-typed property on this class makes the Compose compiler emit a
         * `$stable` field for the class whose initialiser *reads*
         * `ai.rever.boss.plugin.logging.ComponentLogger.$stable`. That field exists in the
         * boss-plugin-api jar we compile against but NOT in the host's bundled
         * `plugin-logging-desktop` jar, which shadows it parent-first at runtime — so
         * BinaryCompatibilityValidator rejected the whole plugin with
         * "ComponentLogger.$stable: field not found" and the host disabled it as binary
         * incompatible. Shipped broken in 1.2.6 and 1.2.7. Now prevented module-wide by
         * compose-stability.conf and proved by the bytecode guard in buildPluginJar — there is
         * deliberately no unit test, because on the test classpath the api jar IS ComponentLogger
         * and everything links. This companion placement is belt-and-braces for the one class the
         * validator takes the whole plugin down over.
         */
        private val logger = BossLogger.forComponent("SecretManagerPlugin")
    }

    override fun register(context: PluginContext) {
        val secretDataProvider = context.secretDataProvider
        val supabaseDataProvider = context.supabaseDataProvider
        val pluginStoreApiKeyProvider = context.pluginStoreApiKeyProvider
        val pluginScope = context.pluginScope ?: CoroutineScope(Dispatchers.Main)

        if (secretDataProvider == null) {
            context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
                SecretManagerComponent(ctx, panelInfo, null, null, null, pluginScope)
            }
            return
        }

        // Built once and shared: the panel's "Add AI Provider Key" action and the
        // settings panel must write through the same store, or an entry added from one
        // wouldn't be recognised as provider configuration by the other.
        //
        // Safe to construct outside the LinkageError guard below — ProviderCredentialStore
        // and ProviderRegistry reference only api symbols that predate 1.0.71.
        val envResolver = EnvResolver()
        val credentialStore = ProviderCredentialStore(secretDataProvider, envResolver)

        context.panelRegistry.registerPanel(SecretManagerInfo) { ctx, panelInfo ->
            SecretManagerComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                secretDataProvider = secretDataProvider,
                supabaseDataProvider = supabaseDataProvider,
                pluginStoreApiKeyProvider = pluginStoreApiKeyProvider,
                scope = pluginScope,
                aiProviderStore = credentialStore,
                settingsProvider = context.settingsProvider,
                windowId = context.windowId,
                splitViewOperations = context.splitViewOperations
            )
        }

        // Contribute secret_* MCP tools (expose secret values to agents; auto-removed on disable/unload).
        context.registerMcpToolProvider(
            SecretManagerMcpToolProvider(pluginId, secretDataProvider, credentialStore),
        )

        registerAiProviderSettings(context, credentialStore, envResolver, pluginScope)
    }

    /**
     * Serve AI provider configuration (credentials, env resolution, live model lists)
     * to the host's Settings → AI Providers section and to other plugins via
     * PluginContext.llmProvider.
     *
     * Guarded: LlmProviderSettingsAPI is a shared-package (parent-first) class added in
     * api 1.0.71, so on hosts that predate it LlmProviderSettingsApiImpl fails to link.
     * Skipping registration there costs only the AI panel — secret management, MCP
     * tools and everything else still work. This is why the plugin's declared
     * apiVersion stays at its true floor instead of being raised to 1.0.71.
     */
    private fun registerAiProviderSettings(
        context: PluginContext,
        credentialStore: ProviderCredentialStore,
        envResolver: EnvResolver,
        pluginScope: CoroutineScope,
    ) {
        try {
            // Constructed inside the guard: the ViewModel starts a catalog.states
            // collector, and on a host that can't link the impl below that coroutine
            // would be started and then orphaned.
            val cacheDir =
                context.cacheProvider
                    ?.getPluginCacheDirectory(pluginId)
                    ?.let { File(it) }

            // Inside the guard on purpose: the bridge names an api type added in
            // 1.0.74, so resolving it is exactly what must not happen on an older
            // host. Left unset when the host has no broker relay, which makes
            // brokered providers report unconfigured instead of failing.
            credentialStore.brokeredKeys = BrokeredCredentialBridge.from(context)

            val viewModel =
                AiProvidersViewModel(
                    store = credentialStore,
                    catalog = ModelCatalog(cacheDir = cacheDir),
                    prefs = ActiveProviderPrefs(),
                    legacyImport = LegacySettingsImport(credentialStore, envResolver),
                    envResolver = envResolver,
                    splitViewOperations = context.splitViewOperations,
                    scope = pluginScope,
                )

            context.registerPluginAPI(LlmProviderSettingsApiImpl(viewModel))

            // Warm the credentials so the first AI action after a restart doesn't race
            // the load. Network-free — model lists are still fetched lazily, when the
            // panel is opened or refreshed, so this costs nothing at startup.
            viewModel.ensureConnectionsLoaded()
        } catch (_: LinkageError) {
            // Host predates LlmProviderSettingsAPI — skip; everything else works.
            // Logged rather than swallowed: without this, "the AI Providers section is
            // missing" has no explanation anywhere.
            logger.info(
                LogCategory.SYSTEM,
                "AI provider settings not served — host api predates LlmProviderSettingsAPI",
                mapOf("requiredApiVersion" to REQUIRED_API_VERSION),
            )
        }
    }
}
