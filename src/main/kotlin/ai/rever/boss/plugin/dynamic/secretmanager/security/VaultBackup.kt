package ai.rever.boss.plugin.dynamic.secretmanager.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted, passphrase-protected vault backup: export the vault to a file only the
 * passphrase can open, and restore it. Backup and anti lock-in are the two things a
 * user fears about trusting a password manager.
 *
 * The file is a self-describing envelope so a future build can still read it:
 * `"BOSSVLT"` magic, a version byte, a random salt and IV, then AES-256-GCM
 * ciphertext of a versioned JSON payload. The passphrase is stretched with
 * PBKDF2-HMAC-SHA256 over a fresh salt, and the payload is sealed with a fresh IV
 * per export. GCM's tag makes a wrong passphrase and a tampered file both fail
 * loudly on import rather than returning garbage.
 */
class VaultBackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The AES-GCM + PBKDF2 envelope, independent of what is inside it. */
object VaultCrypto {
    private val MAGIC = "BOSSVLT".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 2
    private const val LEGACY_VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    // OWASP PBKDF2-HMAC-SHA256 guidance: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#pbkdf2
    private const val PBKDF2_ITERATIONS = 600_000
    private const val LEGACY_ITERATIONS = 210_000
    const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val headerLen = MAGIC.size + 1
    private val minSize = headerLen + SALT_LEN + IV_LEN + TAG_BITS / 8
    private val secureRandom = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "A backup passphrase is required" }
        if (plaintext.size > MAX_BLOB_BYTES - minSize) throw VaultBackupException("Backup exceeds the 64 MiB limit")
        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, PBKDF2_ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val header = MAGIC + byteArrayOf(VERSION) + salt + iv
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintext)
        return header + ciphertext
    }

    fun decrypt(
        blob: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        if (blob.size > MAX_BLOB_BYTES) throw VaultBackupException("Backup exceeds the 64 MiB limit")
        if (blob.size < minSize || !hasValidHeader(blob)) {
            throw VaultBackupException("not a BOSS vault backup")
        }
        val salt = blob.copyOfRange(headerLen, headerLen + SALT_LEN)
        val iv = blob.copyOfRange(headerLen + SALT_LEN, headerLen + SALT_LEN + IV_LEN)
        val ciphertext = blob.copyOfRange(headerLen + SALT_LEN + IV_LEN, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            val version = blob[MAGIC.size]
            val iterations = if (version == LEGACY_VERSION) LEGACY_ITERATIONS else PBKDF2_ITERATIONS
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            if (version != LEGACY_VERSION) cipher.updateAAD(blob.copyOfRange(0, headerLen + SALT_LEN + IV_LEN))
            cipher.doFinal(ciphertext)
        }.getOrElse { throw VaultBackupException("wrong passphrase or corrupted backup", it) }
    }

    private fun hasValidHeader(blob: ByteArray): Boolean =
        blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && (blob[MAGIC.size] == VERSION || blob[MAGIC.size] == LEGACY_VERSION)

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val encoded = SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
            return try { SecretKeySpec(encoded, "AES") } finally { encoded.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }
}

/**
 * One credential in a backup, full fidelity.
 *
 * `toString` is redacted: it never prints the password, the 2FA seed or the recovery
 * codes, so a stray log of an entry cannot leak them.
 */
@Serializable
data class BackupEntry(
    val website: String,
    val username: String,
    val password: String,
    val notes: String? = null,
    val expirationDate: String? = null,
    val tags: List<String> = emptyList(),
    val twofaEnabled: Boolean = false,
    val twofaType: String? = null,
    val twofaSecret: String? = null,
    val recoveryCodes: List<String> = emptyList(),
) {
    override fun toString(): String =
        "BackupEntry(website=$website, username=***, password=***, " +
            "twofaSecret=${if (twofaSecret.isNullOrEmpty()) "none" else "***"}, recoveryCodes=${recoveryCodes.size})"
}

/** The decrypted payload: a schema version plus the entries. */
@Serializable
data class BackupFile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val entries: List<BackupEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/** Serialises the credential list into the encrypted envelope and back. */
object VaultBackupCodec {
    const val MAX_ENTRIES = 5000
    private val json = Json { ignoreUnknownKeys = true }

    /** Serialise [entries] to JSON and seal them under [passphrase]. */
    fun export(
        entries: List<BackupEntry>,
        passphrase: CharArray,
    ): ByteArray {
        if (entries.size > MAX_ENTRIES) throw VaultBackupException("Backup exceeds the $MAX_ENTRIES entry limit")
        val plaintext = json.encodeToString(BackupFile(entries = entries)).toByteArray(Charsets.UTF_8)
        return try { VaultCrypto.encrypt(plaintext, passphrase) } finally { plaintext.fill(0) }
    }

    /**
     * Open a backup and parse its credentials.
     *
     * @throws VaultBackupException if decryption fails or the decrypted bytes are
     *   not a valid backup payload.
     */
    fun import(
        blob: ByteArray,
        passphrase: CharArray,
    ): List<BackupEntry> {
        val plaintext = VaultCrypto.decrypt(blob, passphrase)
        val file =
            try {
                json.decodeFromString<BackupFile>(plaintext.toString(Charsets.UTF_8))
            } catch (error: Exception) {
                throw VaultBackupException("Backup contents are not valid", error)
            } finally {
                plaintext.fill(0)
            }
        if (file.schemaVersion != BackupFile.CURRENT_SCHEMA_VERSION) {
            throw VaultBackupException("Unsupported backup schema version")
        }
        if (file.entries.size > MAX_ENTRIES) throw VaultBackupException("Backup exceeds the $MAX_ENTRIES entry limit")
        return file.entries
    }
}
