package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every provider's effective credential, plus whether the secret store could be read.
 *
 * Not a `Result`: environment-supplied keys work even when the store is unreachable, so
 * the connections are always usable and the failure is a separate fact rather than a
 * reason to throw the map away.
 */
data class ConnectionsSnapshot(
    val connections: Map<String, ProviderConnection>,
    val storeReadFailed: Boolean,
)

/**
 * Stores AI provider credentials and per-provider settings as ordinary secrets, so
 * they inherit the encryption, row-level security and audit trail the secret store
 * already provides instead of sitting in a plaintext file.
 *
 * Mapping onto the secret schema:
 * - `website`  → the provider id (`ANTHROPIC`, `OPENAI`, …)
 * - `username` → the provider's standard key name (`TOGETHER_API_KEY`, …), which is the
 *                secret list's "Key Name" column — the same name as the environment
 *                variable that would supply this key, so both paths read alike
 * - `password` → the API key
 * - `tags`     → [TAG_AI_PROVIDER] (provider configuration, for this plugin),
 *                [TAG_API_KEY] (renders as an API key card, not a password), and the
 *                provider id
 * - `notes`    → JSON holding the selected model, custom endpoint and generation
 *                defaults. The schema has no free-form JSON column, and adding one
 *                would mean a database migration for settings that are only ever
 *                read back by this plugin.
 *
 * Resolution precedence is environment → stored secret → unconfigured. A key found in
 * the environment is never written here.
 *
 * Mutations are serialised through [mutex]: each one is a read-modify-write over the
 * whole store, and the settings panel and the Secret Manager dialog drive this from
 * separate ViewModels with separate busy flags. Two interleaved saves for one provider
 * would otherwise both observe "no existing secret", both create one, and leave a
 * duplicate whose winner is decided arbitrarily on the next read.
 */
class ProviderCredentialStore(
    private val secrets: SecretDataProvider,
    private val envResolver: EnvResolver = EnvResolver(),
) {
    private val logger = BossLogger.forComponent("AiCredentialStore")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    /**
     * Last known provider secrets, so a save doesn't re-page the whole store three
     * times. Cleared by every mutation and by [invalidate].
     */
    @Volatile
    private var cached: Map<String, StoredProvider>? = null

    /**
     * Bumped by every [invalidate]; a load only seats its result if the generation it
     * started in is still current.
     */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    /** Drop the cached provider map, e.g. after an external edit in the secret list. */
    fun invalidate() {
        // Order matters: bump first, so a load that is already paging cannot seat a map it
        // read before the deletion. Clearing alone left a window where an in-flight
        // loadStoredSecrets re-seated the pre-delete map and resurrected a deleted
        // provider for the rest of the session — the exact thing invalidate() prevents.
        generation.incrementAndGet()
        cached = null
    }

    /** Read every provider's effective connection. */
    suspend fun loadAll(): ConnectionsSnapshot {
        val storedResult = loadStoredSecrets()
        val stored = storedResult.getOrElse { emptyMap() }

        val connections =
            ProviderRegistry.all.associate { descriptor ->
                descriptor.id to resolveConnection(descriptor, stored[descriptor.id])
            }

        return ConnectionsSnapshot(
            connections = connections,
            storeReadFailed = storedResult.isFailure,
        )
    }

    /**
     * Environment first, then the stored secret. Settings (selected model, endpoint,
     * generation defaults) always come from the stored entry when there is one, even
     * if the key itself came from the environment — the user still gets to pick a
     * model for a provider they authenticate through an env var.
     */
    private suspend fun resolveConnection(
        descriptor: ProviderDescriptor,
        stored: StoredProvider?,
    ): ProviderConnection {
        val settings = stored?.settings ?: ProviderSettings()
        val envKey = envResolver.resolve(descriptor.envVarNames)

        val (key, source) =
            when {
                !envKey.isNullOrBlank() -> envKey to CredentialSource.ENVIRONMENT
                !stored?.apiKey.isNullOrBlank() -> stored.apiKey to CredentialSource.STORED
                else -> "" to CredentialSource.NONE
            }

        return ProviderConnection(
            providerId = descriptor.id,
            apiKey = key,
            source = source,
            selectedModelId = settings.selectedModelId,
            customEndpoint = settings.customEndpoint,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            label =
                when (source) {
                    CredentialSource.ENVIRONMENT -> envResolver.resolveSourceName(descriptor.envVarNames)
                    CredentialSource.STORED -> stored?.label
                    CredentialSource.NONE -> null
                },
        )
    }

    /**
     * Persist an API key for [providerId], creating or updating its secret.
     *
     * Rejects a blank key (use [clearKey]) and refuses to write when the key came
     * from the environment — that write is the defect this replaced.
     */
    suspend fun saveKey(
        providerId: String,
        apiKey: String,
        label: String? = null,
    ): Result<Unit> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Refusing to store a blank API key."))
        }
        val descriptor =
            ProviderRegistry.find(providerId)
                ?: return Result.failure(IllegalArgumentException("Unknown provider: $providerId"))

        if (!envResolver.resolve(descriptor.envVarNames).isNullOrBlank()) {
            return Result.failure(
                IllegalStateException(
                    "${descriptor.displayName} is configured by environment variable; " +
                        "unset it before storing a key here.",
                ),
            )
        }

        return mutex.withLock {
            val existing = loadStoredSecrets().getOrElse { emptyMap() }[providerId]
            upsert(
                descriptor = descriptor,
                // Default to the provider's standard key name (TOGETHER_API_KEY, …) so the
                // entry is identifiable at a glance in the secret list.
                label = label ?: existing?.label ?: descriptor.standardKeyName,
                apiKey = apiKey.trim(),
                settings = existing?.settings ?: ProviderSettings(),
                existingSecretId = existing?.secretId,
            ).also { invalidate() }
        }
    }

    /**
     * Update the non-secret settings on [providerId]'s existing secret.
     *
     * Returns `false` when there is no secret to attach them to, which is the normal
     * case for a provider whose key comes from the environment. It deliberately does
     * **not** create a key-less entry: the store may reject a blank password, and an
     * AI-provider card carrying no credential reads as broken in the secret list. The
     * caller persists the choice elsewhere in that case — see `ActiveProviderPrefs`.
     */
    suspend fun saveSettings(
        providerId: String,
        settings: ProviderSettings,
    ): Result<Boolean> {
        val descriptor =
            ProviderRegistry.find(providerId)
                ?: return Result.failure(IllegalArgumentException("Unknown provider: $providerId"))

        return mutex.withLock {
            val existing =
                loadStoredSecrets().getOrElse { emptyMap() }[providerId]
                    ?: return@withLock Result.success(false)

            upsert(
                descriptor = descriptor,
                apiKey = existing.apiKey,
                label = existing.label ?: descriptor.standardKeyName,
                settings = settings,
                existingSecretId = existing.secretId,
            ).also { invalidate() }.map { true }
        }
    }

    /** Delete the stored secret for [providerId]. Environment keys are unaffected. */
    suspend fun clearKey(providerId: String): Result<Unit> =
        mutex.withLock {
            val existing =
                loadStoredSecrets().getOrElse { emptyMap() }[providerId]
                    ?: return@withLock Result.success(Unit)
            secrets.deleteSecret(existing.secretId).also { invalidate() }
        }

    private suspend fun upsert(
        descriptor: ProviderDescriptor,
        apiKey: String,
        label: String,
        settings: ProviderSettings,
        existingSecretId: String?,
    ): Result<Unit> {
        val notes = json.encodeToString(ProviderSettings.serializer(), settings)
        // TAG_API_KEY makes the Secret Manager card render as an API key (key icon,
        // "Key Name" label) rather than a website/password pair, which is what these are.
        // TAG_AI_PROVIDER is what marks it as provider configuration for this plugin.
        val tags = listOf(TAG_AI_PROVIDER, TAG_API_KEY, descriptor.id)

        return if (existingSecretId == null) {
            secrets.createSecret(
                CreateSecretRequestData(
                    website = descriptor.id,
                    username = label,
                    password = apiKey,
                    notes = notes,
                    tags = tags,
                ),
            )
        } else {
            secrets.updateSecret(
                UpdateSecretRequestData(
                    secretId = existingSecretId,
                    website = descriptor.id,
                    username = label,
                    password = apiKey,
                    notes = notes,
                    tags = tags,
                ),
            )
        }
    }

    /**
     * Every AI-provider secret, keyed by provider id.
     *
     * Pages through the store rather than taking the first page: a user with many
     * password entries would otherwise appear to have no providers configured.
     */
    private suspend fun loadStoredSecrets(): Result<Map<String, StoredProvider>> {
        cached?.let { return Result.success(it) }

        // Captured before the first page: if invalidate() lands while we are paging, this
        // result is already stale and must not be seated.
        val startedAt = generation.get()

        return runCatching {
            val found = mutableMapOf<String, StoredProvider>()
            var offset = 0

            while (true) {
                val page =
                    secrets
                        .getUserSecrets(limit = PAGE_SIZE, offset = offset)
                        .getOrThrow()

                page.data
                    .filter { it.tags.contains(TAG_AI_PROVIDER) }
                    .forEach { entry ->
                        val providerId = providerIdOf(entry) ?: return@forEach
                        // First entry wins: providers are single-connection here, and a
                        // duplicate would otherwise flip between runs.
                        found.putIfAbsent(providerId, toStored(providerId, entry))
                    }

                if (!page.hasMore || page.data.isEmpty()) break
                offset += PAGE_SIZE
                if (offset >= MAX_SCANNED) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Stopped scanning secrets for AI providers at cap",
                        mapOf("scanned" to offset),
                    )
                    break
                }
            }
            found.toMap()
        }.onSuccess { loaded ->
            if (generation.get() == startedAt) cached = loaded
        }
            .onFailure {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not read AI provider credentials",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }
    }

    /**
     * Provider id from a secret. Prefers `website`, falling back to whichever tag
     * names a known provider, so an entry whose website was edited by hand still
     * resolves.
     */
    private fun providerIdOf(entry: SecretEntryData): String? =
        ProviderRegistry.find(entry.website)?.id
            ?: entry.tags.firstNotNullOfOrNull { tag -> ProviderRegistry.find(tag)?.id }

    private fun toStored(
        providerId: String,
        entry: SecretEntryData,
    ): StoredProvider =
        StoredProvider(
            secretId = entry.id,
            providerId = providerId,
            apiKey = entry.password,
            label = entry.username.takeIf { it.isNotBlank() },
            settings = parseSettings(entry.notes),
        )

    /**
     * Notes are this plugin's own JSON. A user could edit them by hand, so a parse
     * failure falls back to defaults rather than losing the credential. The throwable
     * is deliberately not logged: notes sit on the same record as the key.
     */
    private fun parseSettings(notes: String?): ProviderSettings {
        if (notes.isNullOrBlank()) return ProviderSettings()
        return runCatching { json.decodeFromString(ProviderSettings.serializer(), notes) }
            .onFailure {
                logger.debug(
                    LogCategory.SYSTEM,
                    "Ignoring unreadable AI provider settings note",
                    mapOf("exception" to (it::class.simpleName ?: "Exception")),
                )
            }.getOrDefault(ProviderSettings())
    }

    private data class StoredProvider(
        val secretId: String,
        val providerId: String,
        val apiKey: String,
        val label: String?,
        val settings: ProviderSettings,
    )

    companion object {
        /** Marks a secret as AI provider configuration rather than a password entry. */
        const val TAG_AI_PROVIDER: String = "ai-provider"

        /**
         * The Secret Manager's existing "this is an API key" tag. Reused rather than
         * invented so these entries pick up the same card treatment as any other API key.
         */
        const val TAG_API_KEY: String = "api_key"

        private const val PAGE_SIZE = 100
        private const val MAX_SCANNED = 2000
    }
}

/**
 * Non-secret, per-provider settings, serialised into a secret's notes field.
 *
 * Every field is defaulted so an entry written by an older build still reads back.
 */
@Serializable
data class ProviderSettings(
    val selectedModelId: String? = null,
    val customEndpoint: String? = null,
    val temperature: Float = ProviderConnection.DEFAULT_TEMPERATURE,
    val maxTokens: Int = ProviderConnection.DEFAULT_MAX_TOKENS,
)
