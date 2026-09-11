package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.BrokeredCredentialProvider
import ai.rever.boss.plugin.api.PluginContext

/**
 * Adapts the host's [BrokeredCredentialProvider] onto the plugin-local
 * [BrokeredKeySource] seam.
 *
 * This adapter keeps host API types out of the credential store even though they predate
 * the manifest's 1.0.89 floor. The boundary still makes the store independently testable
 * and limits an incoherent host installation to AI settings registration. See AGENTS.md.
 *
 * Returns null when the host has no broker relay at all, so the caller can leave
 * [ProviderCredentialStore.brokeredKeys] unset and have brokered providers report
 * unconfigured rather than failing.
 */
internal object BrokeredCredentialBridge {

    fun from(context: PluginContext): BrokeredKeySource? {
        val provider = context.brokeredCredentialProvider ?: return null
        return object : BrokeredKeySource {
            override val supportsSharedProviders: Boolean = true
            override fun permitsEndpoint(brokerId: String, endpoint: String): Boolean =
                SharedProviderDefinition.withinScope(endpoint, provider.availableBrokers().firstOrNull { it.id == brokerId }?.scopedTo)

            override suspend fun fetch(brokerId: String): Result<BrokeredKey> = provider.exchange(brokerId).map { credential ->
                BrokeredKey(
                    token = credential.token,
                    refreshAfterSeconds = credential.refreshAfterSeconds,
                    // Read straight, no guard: `expiresAt` shipped in api 1.0.74 (verified in
                    // the released jar), which is the same version this whole file already
                    // requires - so on any host that can load this class the field exists. A
                    // runCatching here would be dead code implying a risk that cannot occur.
                    expiresAt = credential.expiresAt,
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
