package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.AiCliHealth
import ai.rever.boss.plugin.api.AiCliSessionAPI
import ai.rever.boss.plugin.api.PluginContext

/**
 * [CliEngineAccess] backed by the AI Gateway plugin.
 *
 * **The only file in this plugin that names `AiCliSessionAPI`**, which is the point: every
 * new-api reference is confined here so the rest of the panel links on a host whose api jar
 * predates it. Construct it through [orNull], which answers null rather than throwing when
 * that is the case.
 *
 * The gateway is resolved **per call, never cached**. Plugin load order is not guaranteed, so
 * a value read once would usually be null forever - and the gateway can be installed, updated
 * or hot-reloaded while this panel is open, which is exactly when a settings page needs to
 * notice.
 */
internal class GatewayCliEngineAccess private constructor(
    private val context: PluginContext,
) : CliEngineAccess {

    private fun api(): AiCliSessionAPI? =
        runCatching { context.getPluginAPI(AiCliSessionAPI::class.java) }.getOrNull()

    override fun engines(): List<CliEngineInfo> =
        runCatching {
            api()?.engines()?.map {
                CliEngineInfo(
                    id = it.id,
                    displayName = it.displayName,
                    description = it.description,
                    installHint = it.installHint,
                )
            }
        }.getOrNull().orEmpty()

    override suspend fun health(engineId: String): CliEngineHealth {
        val health = runCatching { api()?.health(engineId) }.getOrNull() ?: return CliEngineHealth.Unknown
        return when (health) {
            is AiCliHealth.Ready -> CliEngineHealth.Ready(health.version)
            is AiCliHealth.NotInstalled -> CliEngineHealth.NotInstalled(health.hint)
            is AiCliHealth.Failed -> CliEngineHealth.Failed(health.message)
            // The hierarchy is open by design, so a case this build has never heard of is
            // expected. Unknown rather than a guess: a newer gateway reporting something new
            // must not be rendered as "not installed".
            else -> CliEngineHealth.Unknown
        }
    }

    override fun selectedEngineId(): String? = runCatching { api()?.selectedEngineId() }.getOrNull()

    override fun selectEngine(engineId: String?): Boolean =
        runCatching { api()?.selectEngine(engineId) }.getOrNull() ?: false

    companion object {
        /**
         * An adapter, or null when this host cannot serve one.
         *
         * Null covers both cases a caller cannot act on differently: the gateway is not
         * installed, or the host's api jar predates `AiCliSessionAPI` so the symbol does not
         * link. `LinkageError` is caught rather than `Exception` because that is the shape the
         * second case takes, and it is an `Error`.
         */
        fun orNull(context: PluginContext): CliEngineAccess? =
            try {
                GatewayCliEngineAccess(context).takeIf { it.linksOnThisHost() }
            } catch (_: LinkageError) {
                null
            }

        /**
         * Touch the api type once, here, so a host that cannot link it fails inside the guard
         * above rather than later from a composable.
         */
        private fun GatewayCliEngineAccess.linksOnThisHost(): Boolean =
            runCatching { AiCliSessionAPI::class.java.name }.isSuccess
    }
}
