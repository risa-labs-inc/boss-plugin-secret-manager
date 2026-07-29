package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers which provider is the active one — what `LlmProvider.activeConfig()`
 * resolves to for other plugins.
 *
 * This is a preference, not a credential, so it lives in a small plain file rather
 * than the secret store: it must be readable before (and without) a signed-in
 * session, otherwise a restart would lose the choice whenever the store is
 * unreachable. Nothing sensitive is written here — only a provider id.
 */
class ActiveProviderPrefs(
    private val bossRootDir: File = EnvResolver.defaultBossRootDir(),
) {
    private val logger = BossLogger.forComponent("AiProviderPrefs")
    private val json = Json { ignoreUnknownKeys = true }

    private val file: File get() = File(bossRootDir, FILE_NAME)

    /** The stored active provider id, or null when none has been chosen yet. */
    suspend fun read(): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching null
                json
                    .decodeFromString(Prefs.serializer(), file.readText())
                    .activeProviderId
                    ?.takeIf { ProviderRegistry.find(it) != null }
            }.getOrNull()
        }

    /** Persist [providerId] as the active provider. */
    suspend fun write(providerId: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(Prefs.serializer(), Prefs(providerId)))
            }.onFailure {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not persist active AI provider",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }
            Unit
        }

    @Serializable
    private data class Prefs(val activeProviderId: String? = null)

    companion object {
        private const val FILE_NAME = "ai_provider_prefs.json"
    }
}
