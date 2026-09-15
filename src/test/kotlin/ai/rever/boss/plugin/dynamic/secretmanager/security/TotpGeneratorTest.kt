package ai.rever.boss.plugin.dynamic.secretmanager.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins [TotpGenerator] against the published RFC 6238 Appendix B test vectors for
 * ALL THREE hash algorithms, the FULL table including the 20000000000 large timestamp.
 *
 * The classic trap is that the three use DIFFERENT seeds - the SHA-1 seed is the
 * 20-byte ASCII "12345678901234567890", SHA-256 the 32-byte and SHA-512 the 64-byte
 * repetition - so reusing the SHA-1 seed produces plausible-looking wrong codes.
 * Base32 of each: 32, 52 and 103 characters (mod 8 = 0, 4, 7).
 */
class TotpGeneratorTest {
    private val sha1Base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val sha256Base32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
    private val sha512Base32 =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"

    private fun p8(algorithm: TotpGenerator.Algorithm) = TotpGenerator.Params(algorithm, digits = 8, periodSeconds = 30)

    @Test
    fun `base32Decode decodes the full RFC seed to ASCII digits`() {
        val bytes = TotpGenerator.base32Decode(sha1Base32)
        assertEquals("12345678901234567890", String(bytes, Charsets.US_ASCII))
    }

    @Test
    fun `matches RFC 6238 Appendix B SHA-1 vectors`() {
        val p = p8(TotpGenerator.Algorithm.SHA1)
        assertEquals("94287082", TotpGenerator.code(sha1Base32, 59L, p))
        assertEquals("07081804", TotpGenerator.code(sha1Base32, 1111111109L, p))
        assertEquals("14050471", TotpGenerator.code(sha1Base32, 1111111111L, p))
        assertEquals("89005924", TotpGenerator.code(sha1Base32, 1234567890L, p))
        assertEquals("69279037", TotpGenerator.code(sha1Base32, 2000000000L, p))
        assertEquals("65353130", TotpGenerator.code(sha1Base32, 20000000000L, p))
    }

    @Test
    fun `matches RFC 6238 Appendix B SHA-256 vectors`() {
        val p = p8(TotpGenerator.Algorithm.SHA256)
        assertEquals("46119246", TotpGenerator.code(sha256Base32, 59L, p))
        assertEquals("68084774", TotpGenerator.code(sha256Base32, 1111111109L, p))
        assertEquals("67062674", TotpGenerator.code(sha256Base32, 1111111111L, p))
        assertEquals("91819424", TotpGenerator.code(sha256Base32, 1234567890L, p))
        assertEquals("90698825", TotpGenerator.code(sha256Base32, 2000000000L, p))
        assertEquals("77737706", TotpGenerator.code(sha256Base32, 20000000000L, p))
    }

    @Test
    fun `matches RFC 6238 Appendix B SHA-512 vectors`() {
        val p = p8(TotpGenerator.Algorithm.SHA512)
        assertEquals("90693936", TotpGenerator.code(sha512Base32, 59L, p))
        assertEquals("25091201", TotpGenerator.code(sha512Base32, 1111111109L, p))
        assertEquals("99943326", TotpGenerator.code(sha512Base32, 1111111111L, p))
        assertEquals("93441116", TotpGenerator.code(sha512Base32, 1234567890L, p))
        assertEquals("38618901", TotpGenerator.code(sha512Base32, 2000000000L, p))
        assertEquals("47863826", TotpGenerator.code(sha512Base32, 20000000000L, p))
    }

    @Test
    fun `default params produce a 6-digit code`() {
        val code = TotpGenerator.code(sha1Base32, 59L)
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
        assertEquals("287082", code)
    }

    @Test
    fun `code is constant within a period and changes at the boundary`() {
        // counter = floorDiv(t, 30): 30 and 59 share counter 1, 60 starts counter 2.
        val at30 = TotpGenerator.code(sha1Base32, 30L)
        val at59 = TotpGenerator.code(sha1Base32, 59L)
        val at60 = TotpGenerator.code(sha1Base32, 60L)
        assertEquals(at30, at59)
        assertNotEquals(at59, at60)
    }

    @Test
    fun `base32 decoding ignores whitespace, hyphens, padding and case`() {
        assertEquals(
            TotpGenerator.code(sha1Base32, 59L, p8(TotpGenerator.Algorithm.SHA1)),
            TotpGenerator.code("gezd-gnbv gy3t\tqojq\ngezd-gnbv-gy3t-qojq===", 59L, p8(TotpGenerator.Algorithm.SHA1)),
        )
    }

    @Test
    fun `an impossible Base32 length is rejected`() {
        // "AAA" is 3 chars (mod 8 = 3), which no byte count can produce.
        assertFailsWith<IllegalArgumentException> { TotpGenerator.base32Decode("AAA") }
        // A single character likewise cannot encode a byte.
        assertFailsWith<IllegalArgumentException> { TotpGenerator.code("A", 59L) }
    }

    @Test
    fun `non-canonical trailing bits are rejected, so AB no longer equals AA`() {
        assertContentEquals(byteArrayOf(0), TotpGenerator.base32Decode("AA"))
        assertFailsWith<IllegalArgumentException> { TotpGenerator.base32Decode("AB") }
    }

    @Test
    fun `digits outside 6 to 8 are rejected on both entry points`() {
        fun code(digits: Int) = TotpGenerator.code(sha1Base32, 59L, TotpGenerator.Params(digits = digits))
        assertFailsWith<IllegalArgumentException> { code(5) }
        assertFailsWith<IllegalArgumentException> { code(9) }

        // Public hotp validates too: no ArrayIndexOutOfBounds for digits = 10, no "0" for digits = 0.
        val key = TotpGenerator.base32Decode(sha1Base32)

        fun hotp(digits: Int) = TotpGenerator.hotp(key, 1L, TotpGenerator.Algorithm.SHA1, digits)
        assertFailsWith<IllegalArgumentException> { hotp(10) }
        assertFailsWith<IllegalArgumentException> { hotp(0) }
    }

    @Test
    fun `an invalid seed fails loudly rather than emitting a code`() {
        assertFailsWith<IllegalArgumentException> { TotpGenerator.code("not-base32!!!", 59L) }
    }

    @Test
    fun `secondsRemaining counts down within the period`() {
        assertEquals(30L, TotpGenerator.secondsRemaining(0L))
        assertEquals(1L, TotpGenerator.secondsRemaining(29L))
        assertEquals(30L, TotpGenerator.secondsRemaining(30L))
        assertEquals(10L, TotpGenerator.secondsRemaining(50L, TotpGenerator.Params(periodSeconds = 60)))
    }
}
