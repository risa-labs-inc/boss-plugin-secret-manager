package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.BrokeredCredentialProvider
import ai.rever.boss.plugin.api.PluginContext

/**
 * Adapts the host's [BrokeredCredentialProvider] onto the plugin-local
 * [BrokeredKeySource] seam.
 *
 * **This file references api symbols added in 1.0.74**, which puts it under the same
 * rule as [LlmProviderSettingsApiImpl]: it must only ever be loaded from inside the
 * `LinkageError` guard in `SecretManagerDynamicPlugin.registerAiProviderSettings`.
 * On a host whose api jar predates the interface, resolving this class throws and the
 * guard skips the whole AI section - which is the intended degradation. Referencing it
 * from the store, the registry or the panel would instead take the entire plugin down
 * on such a host, because those load unconditionally. See AGENTS.md.
 *
 * Returns null when the host has no broker relay at all, so the caller can leave
 * [ProviderCredentialStore.brokeredKeys] unset and have brokered providers report
 * unconfigured rather than failing.
 */
internal object BrokeredCredentialBridge {

    fun from(context: PluginContext): BrokeredKeySource? {
        val provider = context.brokeredCredentialProvider ?: return null
        return BrokeredKeySource { brokerId ->
            provider.exchange(brokerId).map { credential ->
                BrokeredKey(
                    token = credential.token,
                    refreshAfterSeconds = credential.refreshAfterSeconds,
                )
            }
        }
    }

    /**
     * Whether [brokerId] is something this host can actually exchange with.
     *
     * Lets the panel say "not available on this host" instead of offering an action
     * that can only fail. Absent from [BrokeredCredentialProvider.availableBrokers]
     * covers both "this build has no such broker" and "no user is signed in".
     */
    fun isAvailable(
        context: PluginContext,
        brokerId: String,
    ): Boolean {
        val provider = context.brokeredCredentialProvider ?: return false
        return provider.availableBrokers().any { it.id == brokerId && it.available }
    }
}
