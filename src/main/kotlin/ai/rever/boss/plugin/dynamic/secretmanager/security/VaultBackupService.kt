package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Exports verified personal-owned secrets to an encrypted [VaultBackupCodec] blob and restores it.
 *
 * Export pages `getUserSecretsWithSharingAccess` to exhaustion (verified personal owner entries only; organisation markers override owner labels) and captures the
 * FULL fidelity of each entry - notes, expiration, tags, and the 2FA metadata
 * including the seed and recovery codes - so the file is a complete, portable copy.
 *
 * Restore writes each entry back through `createSecret`, skipping ones already
 * present (matched on website + username), and reports how many were imported,
 * skipped and failed.
 *
 * The create API cannot write a 2FA seed. Entries carrying a seed are counted as
 * unsupported and left untouched, instead of claiming a successful but lossy restore.
 * The complete seed remains in the encrypted backup for a future API write path.
 */
object VaultBackupService {
    private const val PAGE_SIZE = 100
    private const val SCAN_CAP = VaultBackupCodec.MAX_ENTRIES

    /** Imported/skipped/failed tally from a restore. */
    data class RestoreOutcome(
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val unsupportedTwofa: Int = 0,
    )

    /** Completely enumerate verified personal-owned secrets and seal them under [passphrase]. */
    suspend fun exportVault(
        provider: SecretDataProvider,
        passphrase: CharArray,
    ): ByteArray {
        val entries = mutableListOf<BackupEntry>()
        var offset = 0
        while (offset < SCAN_CAP) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecretsWithSharingAccess(limit = minOf(PAGE_SIZE, SCAN_CAP - offset), offset = offset).getOrThrow()
            coroutineContext.ensureActive()
            if (page.data.isEmpty() && page.hasMore) throw VaultBackupException("Vault enumeration returned an empty page before completion")
            if (offset + page.data.size > SCAN_CAP) throw VaultBackupException("Vault exceeds the $SCAN_CAP entry limit")
            page.data.filter(PersonalVaultOwnership::includes).map { it.secret }.forEach { entries.add(it.toBackupEntry()) }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
            if (offset >= SCAN_CAP) throw VaultBackupException("Vault exceeds the $SCAN_CAP entry limit; enumeration is incomplete")
        }
        coroutineContext.ensureActive()
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
        coroutineContext.ensureActive()
        val existing = existingKeys(provider)
        var imported = 0
        var skipped = 0
        var failed = 0
        var unsupportedTwofa = 0
        for (entry in entries) {
            coroutineContext.ensureActive()
            val key = entry.website to entry.username
            if (key in existing) {
                skipped++
            } else if (!entry.twofaSecret.isNullOrEmpty()) {
                unsupportedTwofa++
            } else {
                provider.createSecret(entry.toCreateRequest()).fold(
                    onSuccess = {
                        imported++
                        existing.add(key)
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        coroutineContext.ensureActive()
                        failed++
                    },
                )
            }
        }
        coroutineContext.ensureActive()
        return RestoreOutcome(imported, skipped, failed, unsupportedTwofa)
    }

    private suspend fun existingKeys(provider: SecretDataProvider): MutableSet<Pair<String, String>> {
        val keys = mutableSetOf<Pair<String, String>>()
        var offset = 0
        while (offset < SCAN_CAP) {
            coroutineContext.ensureActive()
            val page = provider.getUserSecretsWithSharingAccess(limit = minOf(PAGE_SIZE, SCAN_CAP - offset), offset = offset).getOrThrow()
            coroutineContext.ensureActive()
            if (page.data.isEmpty() && page.hasMore) throw VaultBackupException("Vault enumeration returned an empty page before completion")
            if (offset + page.data.size > SCAN_CAP) throw VaultBackupException("Vault exceeds the $SCAN_CAP entry limit")
            page.data.filter(PersonalVaultOwnership::includes).map { it.secret }.forEach { keys.add(it.website to it.username) }
            if (!page.hasMore || page.data.isEmpty()) break
            offset += page.data.size
            if (offset >= SCAN_CAP) throw VaultBackupException("Vault exceeds the $SCAN_CAP entry limit; enumeration is incomplete")
        }
        return keys
    }

    private fun SecretEntryWithSharingData.toBackupEntry(): BackupEntry =
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
