package ai.rever.boss.plugin.dynamic.secretmanager.security

import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Generates strong passwords and diceware passphrases for the secret manager - the
 * "fix it" half beside the vault: when a user is adding or replacing a credential,
 * offer a strong value rather than making them invent one.
 *
 * Two properties make this safe and testable:
 *
 * - **The randomness is injected.** Production callers get a
 *   [java.security.SecureRandom]-backed [Random] (the default), the only correct
 *   source for a secret; tests pass a seeded [Random], so every property is pinned
 *   with no mocking and the default is never the predictable [Random.Default].
 * - **The entropy claim matches the sampler.** Generation is uniform over the set
 *   of strings that satisfy the selected policy (rejection sampling), and
 *   [entropyBits] counts exactly that set by inclusion-exclusion - so the reported
 *   bits are the real bits, not `length * log2(pool)`, which overstates a
 *   class-constrained generator.
 *
 * It never logs and never persists: the returned string is the secret, and the
 * caller writes it into the encrypted vault.
 */
object PasswordGenerator {
    const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"

    /** Common punctuation accepted by essentially every site. Not shell-safe: it contains `$`, `&`, `;`. */
    const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?"

    /** Characters that are hard to tell apart in common fonts, dropped when [Options.excludeAmbiguous] is set. */
    const val AMBIGUOUS = "Il1O0o|"

    private const val LOG2 = 0.6931471805599453

    /**
     * A rejection-sampling ceiling. For any length >= the number of selected
     * classes the probability of never drawing a valid string in this many tries
     * is astronomically small; the forced-placement fallback exists only so the
     * function is guaranteed to terminate, and it too satisfies the policy.
     */
    private const val MAX_REJECTION_ATTEMPTS = 1000

    /** What a generated password is built from. */
    data class Options(
        val length: Int = 20,
        val lowercase: Boolean = true,
        val uppercase: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = false,
    ) {
        /** The character classes this selects, each already ambiguity-filtered and non-empty. */
        internal fun pools(): List<String> {
            val raw =
                buildList {
                    if (lowercase) add(LOWERCASE)
                    if (uppercase) add(UPPERCASE)
                    if (digits) add(DIGITS)
                    if (symbols) add(SYMBOLS)
                }
            val filtered =
                if (excludeAmbiguous) raw.map { pool -> pool.filter { it !in AMBIGUOUS } } else raw
            return filtered.filter { it.isNotEmpty() }
        }
    }

    /**
     * A random password matching [options], drawn uniformly from the strings that
     * contain at least one character of every selected class.
     *
     * @throws IllegalArgumentException if no class is selected, or the length is
     *   too short to hold one character from each selected class.
     */
    fun generate(
        options: Options = Options(),
        random: Random = secureRandom(),
    ): String {
        val pools = options.pools()
        require(pools.isNotEmpty()) { "at least one character class must be selected" }
        require(options.length >= pools.size) {
            "length ${options.length} is too short for ${pools.size} required character classes"
        }

        val all = pools.joinToString("")
        repeat(MAX_REJECTION_ATTEMPTS) {
            val candidate = CharArray(options.length) { all[random.nextInt(all.length)] }
            if (pools.all { pool -> candidate.any { it in pool } }) return String(candidate)
        }
        return forcedPlacement(pools, all, options.length, random)
    }

    /** Termination fallback: guarantee one character per class, fill the rest, shuffle so class order does not leak. */
    private fun forcedPlacement(
        pools: List<String>,
        all: String,
        length: Int,
        random: Random,
    ): String {
        val chars = ArrayList<Char>(length)
        pools.forEach { pool -> chars.add(pool[random.nextInt(pool.length)]) }
        repeat(length - pools.size) { chars.add(all[random.nextInt(all.length)]) }
        chars.shuffle(random)
        return chars.joinToString("")
    }

    /** How a diceware passphrase is shaped. */
    data class PassphraseOptions(
        val wordCount: Int = 4,
        val separator: String = "-",
        val capitalize: Boolean = false,
        val includeNumber: Boolean = false,
    )

    /**
     * A diceware passphrase: [PassphraseOptions.wordCount] words drawn uniformly
     * and independently from [wordlist], joined by [PassphraseOptions.separator].
     *
     * The wordlist and separator must let every word-tuple map to a distinct,
     * unambiguously splittable string, or the claimed entropy would be an
     * overstatement. So this rejects a wordlist that:
     * - contains a blank word or a word containing the separator (joining would be
     *   ambiguous), or
     * - collapses under capitalisation (`["a", "A"]` both become `"A"`, so distinct
     *   selections would produce the same phrase).
     *
     * @throws IllegalArgumentException if the wordlist is empty, has duplicates
     *   (before or after transformation), a word is blank or contains the
     *   separator, or the word count is not positive.
     */
    fun passphrase(
        wordlist: List<String>,
        options: PassphraseOptions = PassphraseOptions(),
        random: Random = secureRandom(),
    ): String {
        require(options.wordCount > 0) { "wordCount must be positive" }
        require(wordlist.isNotEmpty()) { "wordlist must not be empty" }
        require(wordlist.size == wordlist.toHashSet().size) { "wordlist must not contain duplicates" }
        require(options.separator.isNotEmpty()) { "separator must not be empty" }
        require(wordlist.none { it.isBlank() }) { "wordlist must not contain a blank word" }
        require(wordlist.none { it.contains(options.separator) }) {
            "no word may contain the separator, or joining would be ambiguous"
        }
        val transformed = wordlist.map { transform(it, options.capitalize) }
        require(transformed.size == transformed.toHashSet().size) {
            "wordlist must stay distinct after capitalisation"
        }

        val words =
            MutableList(options.wordCount) {
                transform(wordlist[random.nextInt(wordlist.size)], options.capitalize)
            }
        if (options.includeNumber) {
            val at = random.nextInt(words.size)
            words[at] = words[at] + random.nextInt(10)
        }
        return words.joinToString(options.separator)
    }

    private fun transform(
        word: String,
        capitalize: Boolean,
    ): String = if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word

    /**
     * The exact entropy in bits of [generate] for [options]: `log2(N)` where `N`
     * is the number of length-strings over the selected pool that contain at least
     * one character of every selected class, counted by inclusion-exclusion. This
     * is the entropy of the rejection sampler above, so it never overstates.
     */
    fun entropyBits(options: Options): Double {
        val classSizes = options.pools().map { it.length }
        val poolSize = classSizes.sum()
        if (poolSize == 0 || options.length <= 0) return 0.0
        val valid = validStringCount(classSizes, poolSize, options.length)
        return if (valid <= 0.0) 0.0 else ln(valid) / LOG2
    }

    /**
     * Count, as a Double (the value is astronomically large), the length-strings
     * over a [poolSize] alphabet that include at least one character from every
     * class in [classSizes], via inclusion-exclusion over which classes are absent.
     */
    private fun validStringCount(
        classSizes: List<Int>,
        poolSize: Int,
        length: Int,
    ): Double {
        val k = classSizes.size
        var total = 0.0
        for (mask in 0 until (1 shl k)) {
            var removed = 0
            var bits = 0
            for (i in 0 until k) {
                if ((mask shr i) and 1 == 1) {
                    removed += classSizes[i]
                    bits++
                }
            }
            val sign = if (bits % 2 == 0) 1.0 else -1.0
            total += sign * (poolSize - removed).toDouble().pow(length.toDouble())
        }
        return total
    }

    /** Entropy in bits of a [wordCount]-word passphrase over a [wordlistSize]-word list, uniform and independent. */
    fun passphraseEntropyBits(
        wordCount: Int,
        wordlistSize: Int,
    ): Double {
        if (wordCount <= 0 || wordlistSize <= 1) return 0.0
        return wordCount * (ln(wordlistSize.toDouble()) / LOG2)
    }

    /** A [Random] backed by [java.security.SecureRandom] - the only correct source for a secret. */
    private fun secureRandom(): Random = SecureRandom().asKotlinRandom()
}
