package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One-shot migration of API keys out of the plaintext files that predate this plugin.
 *
 * Two sources, both plaintext on disk:
 *
 * - the host's old `~/.boss/llm_settings.json`, from before provider configuration moved here;
 * - the `llmrpa` plugin's `~/.boss/config/llm-settings.json`, which that plugin rewrote on
 *   every keystroke of its own API-key field before it was migrated onto
 *   `PluginContext.llmProvider`.
 *
 * Leaving either behind would silently orphan real credentials, so this offers to move them
 * into the secret store — on explicit user action, never automatically.
 *
 * Only keys are imported. Model ids are deliberately dropped: both files name models that
 * were hardcoded and are now retired (`claude-3-5-sonnet-v2`, `claude-3-5-sonnet-20240620`),
 * and importing one would put a dead model id back into a picker whose entire purpose is to
 * be live. `llmrpa`'s `customEndpoint` is dropped for a different reason — writing it would
 * mean a `saveSettings` call that also blanks any `selectedModelId` the user has already
 * chosen for `CUSTOM`. A custom provider therefore keeps its imported key but reports "not
 * configured" until its endpoint is re-entered, which the import banner says.
 *
 * After a successful import each file is renamed rather than deleted, so a user who needs to
 * recover something still can.
 */
class LegacySettingsImport(
    private val store: ProviderCredentialStore,
    private val envResolver: EnvResolver = EnvResolver(),
    private val bossRootDir: File = EnvResolver.defaultBossRootDir(),
    private val sources: List<LegacySource> = defaultSources(bossRootDir),
) {
    private val logger = BossLogger.forComponent("AiLegacyImport")

    /**
     * One plaintext file that may hold provider keys, plus how to read it.
     *
     * [parse] returns provider id → key. Ids it does not recognise are filtered out later,
     * so a parser may return whatever the file names things.
     */
    class LegacySource(
        val file: File,
        val parse: (String) -> Map<String, String>,
    )

    /** What an import would do, or null when there is nothing to import. */
    suspend fun inspectAndRetireIfEmpty(): LegacyImportOffer? =
        withContext(Dispatchers.IO) {
            val pending = sources.mapNotNull { pendingFor(it) }
            if (pending.isEmpty()) return@withContext null

            LegacyImportOffer(
                providerIds = pending.flatMap { it.providerIds }.distinct().sorted(),
                sourcePaths = pending.map { it.file.absolutePath },
            )
        }

    /**
     * Move the importable keys into the secret store and retire the files.
     *
     * Returns the provider ids actually imported. A provider whose write fails is omitted and
     * its source file is left in place so the attempt can be repeated. Each file is retired
     * independently: one unwritable provider must not strand another file's keys.
     */
    suspend fun import(): Result<List<String>> {
        val pending = withContext(Dispatchers.IO) { sources.mapNotNull { pendingFor(it) } }
        if (pending.isEmpty()) return Result.success(emptyList())

        val imported = mutableListOf<String>()
        var lastFailure: Throwable? = null

        pending.forEach { source ->
            val done = mutableListOf<String>()
            source.providerIds.forEach { providerId ->
                val key = source.keys[providerId]?.trim().orEmpty()
                if (key.isBlank()) return@forEach
                store
                    .saveKey(providerId, key)
                    .onSuccess { done += providerId }
                    .onFailure { lastFailure = it }
            }
            imported += done
            // Retire only when everything this file offered actually landed; otherwise keep it
            // so the rest can be retried.
            if (done.size == source.providerIds.size) archive(source.file)
        }

        val expected = pending.sumOf { it.providerIds.size }
        return if (imported.size == expected) {
            logger.info(
                LogCategory.SYSTEM,
                "Imported legacy AI provider keys",
                mapOf("providers" to imported.size, "files" to pending.size),
            )
            Result.success(imported.distinct())
        } else {
            Result.failure(
                lastFailure ?: IllegalStateException("Imported ${imported.size} of $expected keys."),
            )
        }
    }

    /** Keys one source can contribute right now, or null when it has nothing to offer. */
    private suspend fun pendingFor(source: LegacySource): PendingSource? {
        val file = source.file
        if (!file.exists()) return null

        val parsed =
            runCatching { source.parse(file.readText()) }
                .onFailure {
                    // No throwable in the log: decode errors quote surrounding JSON and these
                    // files hold API keys.
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Legacy LLM settings file could not be parsed",
                        mapOf(
                            "file" to file.name,
                            "exception" to (it::class.simpleName ?: "Exception"),
                        ),
                    )
                }.getOrNull() ?: return null

        // Keys the file holds for providers this build knows, regardless of the environment.
        // Whether the file is worth keeping depends on this, not on what happens to be
        // exported right now.
        val known =
            parsed
                .filterKeys { ProviderRegistry.find(it) != null }
                .filterValues { it.isNotBlank() }

        if (known.isEmpty()) {
            // Genuinely nothing here for us — safe to retire so the offer stops appearing.
            // This write is why the caller is not named `inspect`: it is the one path where
            // merely looking changes the filesystem.
            archive(file)
            return null
        }

        val importable =
            known
                .filter { (providerId, _) ->
                    val descriptor = ProviderRegistry.findOrDefault(providerId)
                    // A key already supplied by the environment needs no import; storing it
                    // would recreate the leak this replaced.
                    envResolver.resolve(descriptor.envVarNames).isNullOrBlank()
                }.keys
                .sorted()

        // Every key is currently shadowed by an environment variable. Do NOT archive:
        // unsetting that variable later must bring the offer back, and renaming here would
        // strand real keys behind a file the user was never told about.
        if (importable.isEmpty()) return null

        return PendingSource(file = file, keys = known, providerIds = importable)
    }

    /**
     * Rename a legacy file so it is no longer picked up — renamed, not deleted, deliberately.
     *
     * The host kernel's self-healing resolves its repair key before any plugin loads, so it
     * cannot reach this plugin's store; `llm_settings.json.migrated` is its only remaining
     * legacy source (see BossConsole's `SelfHealingSettings.LEGACY_KEY_FILE_NAMES`).
     * Shredding the file here would silently break that. The import banner therefore tells
     * the user the keys are still in it and that deleting it is their call.
     */
    private suspend fun archive(file: File) =
        withContext(Dispatchers.IO) {
            runCatching {
                if (file.exists()) file.renameTo(File(file.parentFile, "${file.name}.migrated"))
            }.onFailure {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not archive legacy LLM settings file",
                    mapOf("file" to file.name, "exception" to (it::class.simpleName ?: "Exception")),
                )
            }
            Unit
        }

    private class PendingSource(
        val file: File,
        /** Every known-provider key in the file, used to look up values during import. */
        val keys: Map<String, String>,
        /** The subset worth importing — not shadowed by an environment variable. */
        val providerIds: List<String>,
    )

    /** Only the fields worth reading; everything else in the host's old file is ignored. */
    @Serializable
    private data class HostLlmSettings(
        val apiKeys: Map<String, String> = emptyMap(),
    )

    /**
     * `llmrpa`'s flat shape — one field per provider rather than a map. `selectedProvider`,
     * `selectedModelId`, `customEndpoint`, `temperature` and `maxTokens` are ignored; see the
     * class doc for why.
     */
    @Serializable
    private data class LlmRpaSettings(
        val anthropicApiKey: String = "",
        val openaiApiKey: String = "",
        val togetherApiKey: String = "",
        val customApiKey: String = "",
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The host's old settings file, written before provider config moved into this plugin. */
        const val HOST_FILE_NAME = "llm_settings.json"

        /** `llmrpa`'s own settings file, relative to the BOSS home directory. */
        const val LLMRPA_RELATIVE_PATH = "config/llm-settings.json"

        fun defaultSources(bossRootDir: File): List<LegacySource> =
            listOf(
                LegacySource(File(bossRootDir, HOST_FILE_NAME)) { text ->
                    json.decodeFromString(HostLlmSettings.serializer(), text).apiKeys
                },
                // Resolved against the real `~/.boss` rather than [bossRootDir]: llmrpa
                // hardcoded `File(user.home, ".boss/config")` and never honoured
                // `boss.dev.mode`, so in a dev host its keys are still under `.boss`, not
                // `.boss_debug`. Following bossRootDir here would silently miss them.
                LegacySource(File(System.getProperty("user.home"), ".boss/$LLMRPA_RELATIVE_PATH")) { text ->
                    val s = json.decodeFromString(LlmRpaSettings.serializer(), text)
                    mapOf(
                        "ANTHROPIC" to s.anthropicApiKey,
                        "OPENAI" to s.openaiApiKey,
                        "TOGETHER" to s.togetherApiKey,
                        "CUSTOM" to s.customApiKey,
                    )
                },
            )
    }
}

/** A pending import, surfaced in the panel so the user can accept or ignore it. */
data class LegacyImportOffer(
    val providerIds: List<String>,
    val sourcePaths: List<String>,
)
