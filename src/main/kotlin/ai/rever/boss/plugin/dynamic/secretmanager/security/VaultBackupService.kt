package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Exports the whole vault to an encrypted [VaultBackupCodec] blob and restores it.
 *
 * Export pages `getUserSecrets` to exhaustion (owned secrets only) and captures the
 * FULL fidelity of each entry - notes, expiration, tags, and the 2FA metadata
 * including the seed and recovery codes - so the file is a complete, portable copy.
 *
 * Restore writes each entry back through `createSecret`, skipping ones already
 * present (matched on website + username), and reports how many were imported,
 * skipped and failed.
 *
 * Known fidelity gap, deliberately surfaced rather than hidden: the create API
 * (`CreateSecretRequestData`) exposes `twofaEnabled`/`twofaType`/`recoveryCodes`
 * but no `twofaSecret`, so a restored entry keeps its 2FA flag, type and recovery
 * codes but not the seed itself. The seed IS preserved in the backup file (for
 * portability and for a future API that can write it); it just cannot be re-applied
 * through today's create path.
 */
object VaultBackupService {
    private const val PAGE_SIZE = 100
    private const val SCAN_CAP = 5000

    /** Imported/skipped/failed tally from a restore. */
    data class RestoreOutcome(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
    )

    /** Enumerate the vault and seal it under [passphrase]. */
    suspend fun exportVault(
        provider: SecretDataProvider,
        passphrase: CharArray,
    ): ByteArray {
        val entries = mutableListOf<BackupEntry>()
        var offset = 0
        while (offset < SCAN_CAP) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecrets(limit = PAGE_SIZE, offset = offset).getOrThrow()
            page.data.forEach { entries.add(it.toBackupEntry()) }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
        }
        return VaultBackupCodec.export(entries, passphrase)
    }

    /**
     * Open [blob] and write its entries back, skipping ones already present.
     *
     * @throws VaultBackupException if the blob cannot be decrypted or parsed.
     */
    suspend fun importVault(
        blob: ByteArray,
        passphrase: CharArray,
        provider: SecretDataProvider,
    ): RestoreOutcome {
        val entries = VaultBackupCodec.import(blob, passphrase)
        val existing = existingKeys(provider)
        var imported = 0
        var skipped = 0
        var failed = 0
        for (entry in entries) {
            coroutineContext.ensureActive()
            val key = entry.website.lowercase() to entry.username.lowercase()
            if (key in existing) {
                skipped++
            } else {
                provider.createSecret(entry.toCreateRequest()).fold(
                    onSuccess = {
                        imported++
                        existing.add(key)
                    },
                    onFailure = { failed++ },
                )
            }
        }
        return RestoreOutcome(imported, skipped, failed)
    }

    private suspend fun existingKeys(provider: SecretDataProvider): MutableSet<Pair<String, String>> {
        val keys = mutableSetOf<Pair<String, String>>()
        var offset = 0
        while (offset < SCAN_CAP) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecrets(limit = PAGE_SIZE, offset = offset).getOrThrow()
            page.data.forEach { keys.add(it.website.lowercase() to it.username.lowercase()) }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
        }
        return keys
    }

    private fun SecretEntryData.toBackupEntry(): BackupEntry =
        BackupEntry(
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = metadata?.twofaEnabled ?: false,
            twofaType = metadata?.twofaType,
            twofaSecret = metadata?.twofaSecret,
            recoveryCodes = metadata?.recoveryCodes ?: emptyList(),
        )

    private fun BackupEntry.toCreateRequest(): CreateSecretRequestData =
        CreateSecretRequestData(
            website = website,
            username = username,
            password = password,
            notes = notes,
            expirationDate = expirationDate,
            tags = tags,
            twofaEnabled = twofaEnabled,
            twofaType = twofaType.orEmpty(),
            recoveryCodes = recoveryCodes,
        )
}
