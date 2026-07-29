package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One-shot migration of API keys out of the host's old `llm_settings.json`.
 *
 * Before provider configuration moved here, the host kept keys in plaintext in that
 * file. Leaving it behind would silently orphan real credentials, so this offers to
 * move them into the secret store — on explicit user action, never automatically.
 *
 * Only keys are imported. The file's `selectedModel`/`selectedModelId` are
 * deliberately dropped: they name models that were hardcoded and are now retired
 * (`claude-3-5-sonnet-v2` and similar), and importing one would put a dead model id
 * back into a picker whose entire purpose is to be live.
 *
 * After a successful import the file is renamed rather than deleted, so a user who
 * needs to recover something still can.
 */
class LegacySettingsImport(
    private val store: ProviderCredentialStore,
    private val envResolver: EnvResolver = EnvResolver(),
    private val bossRootDir: File = EnvResolver.defaultBossRootDir(),
) {
    private val logger = BossLogger.forComponent("AiLegacyImport")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val legacyFile: File get() = File(bossRootDir, LEGACY_FILE_NAME)

    /** What an import would do, or null when there is nothing to import. */
    suspend fun inspect(): LegacyImportOffer? =
        withContext(Dispatchers.IO) {
            val file = legacyFile
            if (!file.exists()) return@withContext null

            val parsed =
                runCatching { json.decodeFromString(LegacySettings.serializer(), file.readText()) }
                    .onFailure {
                        // No throwable in the log: decode errors quote surrounding
                        // JSON and this file holds API keys.
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Legacy LLM settings file could not be parsed",
                            mapOf("exception" to (it::class.simpleName ?: "Exception")),
                        )
                    }.getOrNull() ?: return@withContext null

            val importable =
                parsed.apiKeys
                    .filter { (providerId, key) ->
                        val descriptor = ProviderRegistry.find(providerId)
                        descriptor != null &&
                            key.isNotBlank() &&
                            // A key already supplied by the environment needs no import;
                            // storing it would recreate the leak this replaced.
                            envResolver.resolve(descriptor.envVarNames).isNullOrBlank()
                    }.keys
                    .sorted()

            if (importable.isEmpty()) {
                // Nothing worth moving — retire the file so the offer stops appearing.
                archive()
                return@withContext null
            }

            LegacyImportOffer(
                providerIds = importable,
                sourcePath = file.absolutePath,
            )
        }

    /**
     * Move the importable keys into the secret store and retire the file.
     *
     * Returns the provider ids actually imported. A provider whose write fails is
     * omitted and the file is left in place so the attempt can be repeated.
     */
    suspend fun import(): Result<List<String>> {
        val offer = inspect() ?: return Result.success(emptyList())

        val parsed =
            withContext(Dispatchers.IO) {
                runCatching {
                    json.decodeFromString(LegacySettings.serializer(), legacyFile.readText())
                }.getOrNull()
            } ?: return Result.failure(IllegalStateException("Legacy settings could not be read."))

        val imported = mutableListOf<String>()
        var lastFailure: Throwable? = null

        offer.providerIds.forEach { providerId ->
            val key = parsed.apiKeys[providerId]?.trim().orEmpty()
            if (key.isBlank()) return@forEach

            store
                .saveKey(providerId, key)
                .onSuccess { imported += providerId }
                .onFailure { lastFailure = it }
        }

        return if (imported.size == offer.providerIds.size) {
            archive()
            logger.info(
                LogCategory.SYSTEM,
                "Imported legacy AI provider keys",
                mapOf("providers" to imported.size),
            )
            Result.success(imported)
        } else {
            // Partial import: keep the file so the rest can be retried.
            Result.failure(
                lastFailure
                    ?: IllegalStateException("Imported ${imported.size} of ${offer.providerIds.size} keys."),
            )
        }
    }

    /** Rename the legacy file so it is no longer picked up, keeping it recoverable. */
    private suspend fun archive() =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = legacyFile
                if (file.exists()) file.renameTo(File(bossRootDir, "$LEGACY_FILE_NAME.migrated"))
            }.onFailure {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not archive legacy LLM settings file",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }
            Unit
        }

    /** Only the fields worth reading; everything else in the old file is ignored. */
    @Serializable
    private data class LegacySettings(
        val apiKeys: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val LEGACY_FILE_NAME = "llm_settings.json"
    }
}

/** A pending import, surfaced in the panel so the user can accept or ignore it. */
data class LegacyImportOffer(
    val providerIds: List<String>,
    val sourcePath: String,
)
