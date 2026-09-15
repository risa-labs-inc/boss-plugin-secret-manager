package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretMetadataData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [TotpCode], the seam between a stored secret's 2FA metadata and [TotpGenerator].
 *
 * This is the parameter handoff the panel relies on: which entries get a code, that the
 * algorithm/digits/period defaults reach the generator so a stored bare seed matches an
 * authenticator app, and that a malformed or non-TOTP entry yields no code rather than a
 * crash. The RFC SHA-1 seed and its published `59s` code (`94287082` at 8 digits,
 * `287082` at the 6-digit default) are the anchor.
 */
class TotpCodeTest {
    private val rfcSeed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun metadata(
        enabled: Boolean = true,
        type: String? = "totp",
        secret: String? = rfcSeed,
    ) = SecretMetadataData(
        twofaEnabled = enabled,
        twofaType = type,
        twofaSecret = secret,
        recoveryCodes = emptyList(),
    )

    @Test
    fun `a totp entry produces the RFC code with default params`() {
        val reading = TotpCode.reading(metadata(), unixTimeSeconds = 59L)
        assertNotNull(reading)
        assertEquals("287082", reading.code)
        assertEquals(1L, reading.secondsRemaining)
        assertEquals(30L, reading.periodSeconds)
        assertTrue(TotpCode.isAvailable(metadata()))
    }

    @Test
    fun `the code expiry boundary follows the period`() {
        // Same code at 30 and 59 (counter 1), a new one at 60 (counter 2).
        val at30 = TotpCode.reading(metadata(), 30L)?.code
        val at59 = TotpCode.reading(metadata(), 59L)?.code
        val at60 = TotpCode.reading(metadata(), 60L)?.code
        assertEquals(at30, at59)
        assertEquals(30L, TotpCode.reading(metadata(), 30L)?.secondsRemaining)
        assertEquals(true, at59 != at60)
    }

    @Test
    fun `no reading when 2FA is not enabled`() {
        assertNull(TotpCode.reading(metadata(enabled = false)))
        assertFalse(TotpCode.isAvailable(metadata(enabled = false)))
    }

    @Test
    fun `no reading for a null or non-totp type`() {
        assertNull(TotpCode.reading(null))
        assertNull(TotpCode.reading(metadata(type = null)))
        // hotp is counter-based and cannot be generated from a clock.
        assertNull(TotpCode.reading(metadata(type = "hotp")))
    }

    @Test
    fun `no reading when the seed is blank`() {
        assertNull(TotpCode.reading(metadata(secret = null)))
        assertNull(TotpCode.reading(metadata(secret = "   ")))
    }

    @Test
    fun `a malformed seed yields no code rather than throwing`() {
        assertNull(TotpCode.reading(metadata(secret = "not-base32!!!")))
        // An impossible Base32 length is malformed too, and must not crash the panel.
        assertNull(TotpCode.reading(metadata(secret = "AAA")))
    }

    @Test
    fun `type match is case-insensitive`() {
        assertNotNull(TotpCode.reading(metadata(type = "TOTP"), 59L))
    }

    @Test
    fun `grouped formats 6 and 8 digit codes and leaves others alone`() {
        assertEquals("287 082", TotpCode.grouped("287082"))
        assertEquals("9428 7082", TotpCode.grouped("94287082"))
        assertEquals("1234567", TotpCode.grouped("1234567"))
    }
}
