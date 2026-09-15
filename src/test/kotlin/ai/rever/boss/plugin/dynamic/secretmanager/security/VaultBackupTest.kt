package ai.rever.boss.plugin.dynamic.secretmanager.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the encrypted backup: full-fidelity round trip, wrong-passphrase and tamper rejection, and redaction. */
class VaultBackupTest {
    private val passphrase = "correct horse battery staple".toCharArray()
    private val entries =
        listOf(
            BackupEntry(
                website = "https://example.com",
                username = "john",
                password = "hunter2",
                notes = "primary",
                expirationDate = "2030-01-01",
                tags = listOf("work", "email"),
                twofaEnabled = true,
                twofaType = "totp",
                twofaSecret = "JBSWY3DPEHPK3PXP",
                recoveryCodes = listOf("code-1", "code-2"),
            ),
            BackupEntry(website = "https://bank.example", username = "jane", password = "s3cr3t"),
        )

    @Test
    fun `export then import round-trips full fidelity`() {
        val blob = VaultBackupCodec.export(entries, passphrase)
        assertEquals(entries, VaultBackupCodec.import(blob, passphrase))
    }

    @Test
    fun `a wrong passphrase is rejected`() {
        val blob = VaultBackupCodec.export(entries, passphrase)
        assertFailsWith<VaultBackupException> { VaultBackupCodec.import(blob, "wrong".toCharArray()) }
    }

    @Test
    fun `a tampered file fails the authentication tag`() {
        val blob = VaultBackupCodec.export(entries, passphrase)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<VaultBackupException> { VaultBackupCodec.import(blob, passphrase) }
    }

    @Test
    fun `bytes that are not a backup are refused`() {
        assertFailsWith<VaultBackupException> { VaultBackupCodec.import("nope".toByteArray(), passphrase) }
    }

    @Test
    fun `two exports of the same data differ - fresh salt and IV`() {
        val a = VaultBackupCodec.export(entries, passphrase)
        val b = VaultBackupCodec.export(entries, passphrase)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `the envelope starts with the BOSSVLT magic`() {
        val blob = VaultBackupCodec.export(entries, passphrase)
        assertContentEquals("BOSSVLT".toByteArray(Charsets.US_ASCII), blob.copyOfRange(0, 7))
    }

    @Test
    fun `toString redacts the password, seed and recovery codes`() {
        val text = entries.first().toString()
        assertFalse(text.contains("hunter2"), text)
        assertFalse(text.contains("JBSWY3DPEHPK3PXP"), text)
        assertFalse(text.contains("code-1"), text)
    }

    @Test
    fun `VaultCrypto round-trips arbitrary bytes`() {
        val payload = ByteArray(48) { it.toByte() }
        assertContentEquals(payload, VaultCrypto.decrypt(VaultCrypto.encrypt(payload, passphrase), passphrase))
    }
}
