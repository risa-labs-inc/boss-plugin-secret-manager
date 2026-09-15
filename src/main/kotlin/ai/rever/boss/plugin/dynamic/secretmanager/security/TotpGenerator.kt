package ai.rever.boss.plugin.dynamic.secretmanager.security

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (and its RFC 4226 HOTP core), so the Secret Manager panel can turn a
 * stored 2FA seed (`SecretMetadataData.twofaSecret`) into the current code - the piece
 * a "copy my authenticator code" action needs.
 *
 * This lives in the secret-manager plugin, beside the vault UI that owns the seed:
 * `SecretDataProvider` hands the plugin `SecretEntryData.metadata.twofaSecret`, and the
 * panel is the surface that shows and copies it. Keeping the code generation here rather
 * than in the host means the one place that already holds the seed also holds the logic,
 * rather than the host shipping a utility no host surface consumes (see [TotpCode] for
 * the consumer this generator exists for).
 *
 * Kept deliberately pure and injectable-clock: the code is a function of
 * `(secret, time, params)` only, which is what lets it be pinned against the published
 * RFC 6238 Appendix B test vectors for all three hash algorithms with no mocking. The
 * seed never leaves this process, the derived key is zeroed after use, and nothing here
 * logs it.
 *
 * Scope note: this generates the code and [TotpCode] wires it into the panel's copy
 * action. Placing a code into a web page's 2FA field is the integrated browser's job (a
 * separate plugin); this is the correctness-critical core such a surface would also call,
 * and the reason it is unit-first is that getting HOTP truncation or Base32 padding
 * subtly wrong is exactly the kind of bug a test must catch before a user relies on a
 * code.
 */
object TotpGenerator {
    /** Hash algorithms an `otpauth://` URI may name. */
    enum class Algorithm(
        val macName: String,
    ) {
        SHA1("HmacSHA1"),
        SHA256("HmacSHA256"),
        SHA512("HmacSHA512"),
    }

    data class Params(
        val algorithm: Algorithm = Algorithm.SHA1,
        val digits: Int = DEFAULT_DIGITS,
        val periodSeconds: Long = DEFAULT_PERIOD_SECONDS,
    )

    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD_SECONDS = 30L

    /** RFC 4226 requires d >= 6 and its DIGITS_POWER table stops at 8; otpauth uses 6, 7 or 8. */
    private const val MIN_DIGITS = 6
    private const val MAX_DIGITS = 8

    /** Every HMAC this supports is >= 20 bytes; dynamic truncation reads offset+4 with offset up to 15. */
    private const val MIN_HASH_BYTES = 20

    /** Unpadded Base32 lengths RFC 4648 can produce (mod 8): 0, 2, 4, 5, 7 bytes-per-group remainders. */
    private val VALID_TAIL_LENGTHS = setOf(0, 2, 4, 5, 7)

    /**
     * The TOTP code for [base32Secret] at [unixTimeSeconds] (default: now).
     *
     * @throws IllegalArgumentException if the secret is not valid Base32 or the
     *   parameters are out of range - a bad seed must fail loudly here, not
     *   silently emit a code that will never match.
     */
    fun code(
        base32Secret: String,
        unixTimeSeconds: Long = System.currentTimeMillis() / 1000L,
        params: Params = Params(),
    ): String {
        require(params.digits in MIN_DIGITS..MAX_DIGITS) { "digits must be $MIN_DIGITS..$MAX_DIGITS" }
        require(params.periodSeconds > 0) { "period must be positive" }
        val key = base32Decode(base32Secret)
        require(key.isNotEmpty()) { "secret decoded to zero bytes" }
        val counter = Math.floorDiv(unixTimeSeconds, params.periodSeconds)
        try {
            return hotp(key, counter, params.algorithm, params.digits)
        } finally {
            // The SecretKeySpec copied the bytes; drop this copy of the seed.
            key.fill(0)
        }
    }

    /**
     * Seconds until the code at [unixTimeSeconds] rolls over: a value in
     * `1..periodSeconds`, so an affordance does not paste a code about to expire.
     */
    fun secondsRemaining(
        unixTimeSeconds: Long = System.currentTimeMillis() / 1000L,
        params: Params = Params(),
    ): Long {
        require(params.periodSeconds > 0) { "period must be positive" }
        return params.periodSeconds - Math.floorMod(unixTimeSeconds, params.periodSeconds)
    }

    /**
     * RFC 4226 HOTP: HMAC over the 8-byte big-endian [counter], dynamic
     * truncation to a 31-bit integer, then modulo 10^digits, left-padded.
     *
     * @throws IllegalArgumentException if [digits] is outside `6..8` or the MAC
     *   output is too short to truncate (a future sub-20-byte digest).
     */
    fun hotp(
        key: ByteArray,
        counter: Long,
        algorithm: Algorithm,
        digits: Int,
    ): String {
        require(digits in MIN_DIGITS..MAX_DIGITS) { "digits must be $MIN_DIGITS..$MAX_DIGITS" }

        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c ushr 8
        }

        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(key, algorithm.macName))
        val hash = mac.doFinal(msg)
        require(hash.size >= MIN_HASH_BYTES) { "MAC output too short for truncation" }

        // Dynamic truncation (RFC 4226 §5.3): low 4 bits of the last byte select
        // the offset; mask the top bit of the 4 taken bytes to stay positive.
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)

        val otp = binary % POW10[digits]
        return otp.toString().padStart(digits, '0')
    }

    private val POW10 =
        IntArray(MAX_DIGITS + 1) { index ->
            var v = 1
            repeat(index) { v *= 10 }
            v
        }

    /**
     * Decode RFC 4648 Base32 (the authenticator seed encoding), ignoring
     * whitespace, `-` grouping separators and `=` padding, and accepting either
     * case.
     *
     * Validates both the length and the trailing bits, so an encoding RFC 4648
     * cannot produce is rejected rather than silently decoded: `"AAA"` (a
     * bytes-impossible length) and non-canonical trailing bits both throw, so
     * `"AB"` no longer decodes to the same key as `"AA"`.
     *
     * @throws IllegalArgumentException on a character outside the Base32 alphabet,
     *   an impossible length, or non-zero trailing bits.
     */
    fun base32Decode(input: String): ByteArray {
        val cleaned =
            input
                .filterNot { it.isWhitespace() || it == '-' }
                .trimEnd('=')
                .uppercase(Locale.ROOT)
        if (cleaned.isEmpty()) return ByteArray(0)
        require(cleaned.length % 8 in VALID_TAIL_LENGTHS) { "invalid Base32 length" }

        val out = ByteArray(cleaned.length * 5 / 8)
        var outIndex = 0
        var buffer = 0
        var bitsLeft = 0
        for (ch in cleaned) {
            val value = BASE32_ALPHABET.indexOf(ch)
            require(value >= 0) { "invalid Base32 character" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out[outIndex++] = ((buffer ushr bitsLeft) and 0xff).toByte()
            }
        }
        // The leftover partial group must be zero padding, not data (canonical form).
        require(buffer and ((1 shl bitsLeft) - 1) == 0) { "non-canonical Base32 trailing bits" }
        return out
    }

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
}
