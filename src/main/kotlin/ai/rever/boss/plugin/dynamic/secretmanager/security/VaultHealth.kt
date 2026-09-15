package ai.rever.boss.plugin.dynamic.secretmanager.security

/**
 * Local vault-health analysis: which stored passwords are reused across sites and
 * which are weak. Pure and offline - it looks only at the passwords already in
 * memory and makes no network call, so there is no egress and no consent surface
 * to reason about. (An online breach check is a deliberate follow-up, not part of
 * this.)
 *
 * The report is password-free by construction: it names sites and secret ids, never
 * a password, so it is safe to hold in UI state. It must still not be logged - a
 * reuse group is "which of the user's accounts fall together if one leaks".
 */
object VaultHealth {
    /** The only fields the analysis needs; the caller maps its secret model to this. */
    data class PasswordRecord(
        val id: String,
        val site: String,
        val password: String,
    )

    enum class Strength { WEAK, FAIR, STRONG }

    /** One account in a reuse group. Paired (id, site) so the two can never fall out of alignment. */
    data class ReuseMember(
        val id: String,
        val site: String,
    )

    /** A set of accounts that share one password. */
    data class ReuseGroup(
        val members: List<ReuseMember>,
    ) {
        val count: Int get() = members.size
    }

    /** A password rated below STRONG, with at least one human-readable reason (never empty). */
    data class WeakEntry(
        val id: String,
        val site: String,
        val strength: Strength,
        val reasons: List<String>,
    )

    data class Report(
        val reuseGroups: List<ReuseGroup>,
        val weakEntries: List<WeakEntry>,
        val analyzedCount: Int,
    ) {
        val hasFindings: Boolean get() = reuseGroups.isNotEmpty() || weakEntries.isNotEmpty()
        val reusedPasswordCount: Int get() = reuseGroups.size
        val weakCount: Int get() = weakEntries.size
    }

    private const val MIN_LENGTH = 8
    private const val STRONG_LENGTH = 12
    private const val STRONG_CLASSES = 3

    /** Reuse groups (largest first) and weak entries (weakest first) over [records]. */
    fun analyze(records: List<PasswordRecord>): Report {
        // isNotBlank, not isNotEmpty: a single space is not a password and must not group as reuse.
        val usable = records.filter { it.password.isNotBlank() }

        val reuseGroups =
            usable
                .groupBy { it.password }
                .values
                .filter { it.size > 1 }
                .map { group -> ReuseGroup(group.map { ReuseMember(it.id, it.site) }) }
                .sortedByDescending { it.count }

        val weakEntries =
            usable
                .mapNotNull { record ->
                    val (strength, reasons) = rate(record.password)
                    if (strength == Strength.STRONG) null else WeakEntry(record.id, record.site, strength, reasons)
                }.sortedBy { severity(it.strength) }

        return Report(reuseGroups, weakEntries, usable.size)
    }

    /**
     * Rate a password's strength and say why, in Unicode-aware terms.
     *
     * Character classes use `isLowerCase`/`isUpperCase`/`isDigit`, not ASCII ranges,
     * so a non-ASCII passphrase is not mislabelled "uses only one kind of character".
     * A non-STRONG result always carries at least one reason.
     */
    internal fun rate(password: String): Pair<Strength, List<String>> {
        val length = password.length
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++

        val reasons =
            buildList {
                if (length < MIN_LENGTH) add("Fewer than $MIN_LENGTH characters")
                if (classes < 2) add("Uses only one kind of character")
                if (length in MIN_LENGTH until STRONG_LENGTH && classes >= 2) add("Under $STRONG_LENGTH characters")
                if (classes == 2 && length >= STRONG_LENGTH) add("Only two kinds of character")
            }

        val strength =
            when {
                length < MIN_LENGTH || classes < 2 -> Strength.WEAK
                length >= STRONG_LENGTH && classes >= STRONG_CLASSES -> Strength.STRONG
                else -> Strength.FAIR
            }

        // A weak or fair entry with nothing to show would render an empty reason row.
        val finalReasons = if (strength != Strength.STRONG && reasons.isEmpty()) listOf("Could be stronger") else reasons
        return strength to finalReasons
    }

    private fun severity(strength: Strength): Int =
        when (strength) {
            Strength.WEAK -> 0
            Strength.FAIR -> 1
            Strength.STRONG -> 2
        }
}
