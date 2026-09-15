package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretMetadataData

/**
 * The current authenticator code for one stored secret, plus how long it lasts.
 *
 * [code] is grouped for reading only where the panel renders it; the raw digits are what
 * a copy action puts on the clipboard, so [code] here is the ungrouped string.
 */
data class TotpReading(
    val code: String,
    val secondsRemaining: Long,
    val periodSeconds: Long,
)

/**
 * Turns a stored secret's 2FA metadata into a live TOTP reading, or null when the entry
 * carries no usable TOTP seed. This is the seam between the vault (which holds the seed)
 * and [TotpGenerator] (which is the RFC core), and it is the piece worth testing on its
 * own: it decides which entries get a code, and hands the algorithm/digits/period across
 * to the generator.
 *
 * A stored [SecretMetadataData] carries only a raw Base32 [SecretMetadataData.twofaSecret]
 * and a [SecretMetadataData.twofaType] string, with no algorithm/digits/period, so the RFC
 * defaults (SHA-1, 6 digits, 30 seconds) are used - which is what an authenticator app
 * assumes for a bare secret and what every mainstream issuer emits.
 *
 * A null is returned, never an exception, when the entry is not a usable TOTP secret:
 *
 *  - no metadata, or 2FA is not enabled;
 *  - the type is not `totp` (a `hotp` counter-based seed cannot be generated from a clock,
 *    and an unknown type is not assumed to be TOTP);
 *  - the seed is blank; or
 *  - the seed does not decode as Base32. A malformed stored seed must leave the panel with
 *    no code to copy rather than crash it, so the generator's `IllegalArgumentException` is
 *    swallowed here. The seed is never put in the caught message or logged.
 */
object TotpCode {
    private const val TYPE_TOTP = "totp"

    fun reading(
        metadata: SecretMetadataData?,
        unixTimeSeconds: Long = System.currentTimeMillis() / 1000L,
    ): TotpReading? {
        if (metadata == null || !metadata.twofaEnabled) return null
        if (!metadata.twofaType.equals(TYPE_TOTP, ignoreCase = true)) return null
        val seed = metadata.twofaSecret
        if (seed.isNullOrBlank()) return null
        val params = TotpGenerator.Params()
        return try {
            TotpReading(
                code = TotpGenerator.code(seed, unixTimeSeconds, params),
                secondsRemaining = TotpGenerator.secondsRemaining(unixTimeSeconds, params),
                periodSeconds = params.periodSeconds,
            )
        } catch (_: IllegalArgumentException) {
            // A malformed stored seed yields no code rather than a crash. Deliberately does
            // not include the exception or the seed - the message would carry seed fragments.
            null
        }
    }

    /** True when this entry has a usable TOTP seed, so the panel can show its copy action. */
    fun isAvailable(metadata: SecretMetadataData?): Boolean = reading(metadata) != null

    /** Groups a code as `XXX XXX` (6) or two halves (8) for on-screen reading only. */
    fun grouped(code: String): String =
        when (code.length) {
            6 -> "${code.substring(0, 3)} ${code.substring(3)}"
            8 -> "${code.substring(0, 4)} ${code.substring(4)}"
            else -> code
        }
}
