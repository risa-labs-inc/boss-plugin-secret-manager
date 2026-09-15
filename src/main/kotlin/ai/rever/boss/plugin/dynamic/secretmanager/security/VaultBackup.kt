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
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val headerLen = MAGIC.size + 1
    private val minSize = headerLen + SALT_LEN + IV_LEN + TAG_BITS / 8
    private val secureRandom = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return MAGIC + byteArrayOf(VERSION) + salt + iv + ciphertext
    }

    fun decrypt(
        blob: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        if (blob.size < minSize || !hasValidHeader(blob)) {
            throw VaultBackupException("not a BOSS vault backup")
        }
        val salt = blob.copyOfRange(headerLen, headerLen + SALT_LEN)
        val iv = blob.copyOfRange(headerLen + SALT_LEN, headerLen + SALT_LEN + IV_LEN)
        val ciphertext = blob.copyOfRange(headerLen + SALT_LEN + IV_LEN, blob.size)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrElse { throw VaultBackupException("wrong passphrase or corrupted backup", it) }
    }

    private fun hasValidHeader(blob: ByteArray): Boolean =
        blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) && blob[MAGIC.size] == VERSION

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded, "AES")
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
    private val json = Json { ignoreUnknownKeys = true }

    /** Serialise [entries] to JSON and seal them under [passphrase]. */
    fun export(
        entries: List<BackupEntry>,
        passphrase: CharArray,
    ): ByteArray =
        VaultCrypto.encrypt(
            json.encodeToString(BackupFile(entries = entries)).toByteArray(Charsets.UTF_8),
            passphrase,
        )

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
        val plaintext = VaultCrypto.decrypt(blob, passphrase).toString(Charsets.UTF_8)
        val file =
            runCatching { json.decodeFromString<BackupFile>(plaintext) }
                .getOrElse { throw VaultBackupException("backup contents are not valid", it) }
        return file.entries
    }
}
