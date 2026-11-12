package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Which list in the Secrets section a row belongs to. */
internal enum class VaultKind(val label: String) {
    LOGIN("Logins"),
    CARD("Cards"),
    ADDRESS("Addresses"),
    KEY("Keys & tokens"),
}

/**
 * One SM row as the panel shows it. Built from website/username/notes/tags only: the password
 * column (a card's full number) is never read, so no field here can carry it.
 */
internal sealed interface VaultRow {
    val kind: VaultKind
    val title: String

    data class Login(val site: String, val username: String) : VaultRow {
        override val kind get() = VaultKind.LOGIN
        override val title get() = site
    }

    data class Card(
        val brand: String,
        val last4: String,
        val expMonth: String,
        val expYear: String,
        val holder: String,
    ) : VaultRow {
        override val kind get() = VaultKind.CARD
        override val title get() = "$brand $MASK $last4".trim()

        /** `MM/YY`, or blank when either half is missing. */
        val expiry: String
            get() = if (expMonth.isNotBlank() && expYear.isNotBlank()) "$expMonth/$expYear" else ""
    }

    data class Address(val label: String, val fields: AddressFields) : VaultRow {
        override val kind get() = VaultKind.ADDRESS
        override val title get() = fields.name.ifBlank { label }
    }

    data class Key(override val title: String) : VaultRow {
        override val kind get() = VaultKind.KEY
    }

    /** A stored card security code. Listed so it can be deleted; never revealed or copied. */
    data class CardCode(override val title: String) : VaultRow {
        override val kind get() = VaultKind.KEY
    }

    companion object {
        const val MASK = "••••"
    }
}

/** Address notes JSON, same keys as the host autofill writer. */
internal data class AddressFields(
    val name: String = "",
    val email: String = "",
    val company: String = "",
    val phone: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
    val country: String = "",
) {
    fun oneLine(): String =
        listOf(street, city, listOf(state, zip).filter { it.isNotBlank() }.joinToString(" "), country)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")

    /** Writes the known keys over [base] so fields this panel does not edit survive. */
    fun toJson(base: JsonObject? = null): String =
        JsonObject(
            (base ?: JsonObject(emptyMap())) +
                buildJsonObject {
                    put("name", name)
                    put("email", email)
                    put("company", company)
                    put("phone", phone)
                    put("street", street)
                    put("city", city)
                    put("state", state)
                    put("zip", zip)
                    put("country", country)
                },
        ).toString()

    companion object {
        fun parse(o: JsonObject?): AddressFields =
            AddressFields(
                name = o.str("name"),
                email = o.str("email"),
                company = o.str("company"),
                phone = o.str("phone"),
                street = o.str("street"),
                city = o.str("city"),
                state = o.str("state"),
                zip = o.str("zip"),
                country = o.str("country"),
            )
    }
}

internal object VaultRows {
    const val KIND_PASSWORD = "kind:password"
    const val KIND_CARD = "kind:card"
    const val KIND_ADDRESS = "kind:address"
    const val KIND_CVV = "kind:cvv"
    const val CARD_PREFIX = "card/"
    const val ADDRESS_PREFIX = "address/"

    /** create_secret rejects an empty password; an address has no secret. Same filler as the host. */
    const val ADDRESS_PASSWORD_FILLER = "-"

    private const val MAX_PREFIX = "fluck/"
    private const val CONNECTOR_TAG = "connector"
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
    private val FOUR_DIGITS = Regex("^[0-9]{4}$")
    private val MAX_CARD_ALIAS = Regex("^card-[0-9]{4}$")
    private val MAX_CVV_ALIAS = Regex("^card-[0-9]{4}-cvv$")
    private val JSON = Json { ignoreUnknownKeys = true }

    fun classify(secret: SecretEntryData, isAiProvider: Boolean = false): VaultRow =
        classify(secret.website, secret.username, secret.notes, secret.tags, isAiProvider)

    fun classify(
        website: String,
        username: String,
        notes: String?,
        tags: List<String>,
        isAiProvider: Boolean = false,
    ): VaultRow {
        val site = website.trim()
        // Payment checks fail closed: case-insensitive, any kind:card/kind:cvv tag, and card shapes
        // count unless the row is explicitly kind:password.
        val lsite = site.lowercase()
        val ltags = tags.map { it.trim().lowercase() }
        val kind = ltags.firstOrNull { it.startsWith("kind:") }
        val maxAlias = maxVaultAlias(site)
        val lalias = maxAlias?.lowercase()
        val shapeAllowed = kind != KIND_PASSWORD

        if (KIND_CVV in ltags || (shapeAllowed && lalias != null && MAX_CVV_ALIAS.matches(lalias))) {
            val card = lalias?.removeSuffix("-cvv") ?: lsite.removeSuffix("-cvv")
            return VaultRow.CardCode("Card security code · $card")
        }
        if (KIND_CARD in ltags ||
            (shapeAllowed && (lsite.startsWith(CARD_PREFIX) || (lalias != null && MAX_CARD_ALIAS.matches(lalias))))
        ) {
            return card(lsite, lalias, username, notes)
        }
        // Untagged `address/` only counts when its notes are JSON, so a plain login there keeps its notes.
        if (kind == KIND_ADDRESS || (kind == null && lsite.startsWith(ADDRESS_PREFIX) && parseObject(notes) != null)) {
            val label = if (lsite.startsWith(ADDRESS_PREFIX)) site.substring(ADDRESS_PREFIX.length) else site
            return VaultRow.Address(label, AddressFields.parse(parseObject(notes)))
        }
        if (isAiProvider || "api_key" in ltags) return VaultRow.Key(site)
        if (isConnectorToken(site, tags)) return VaultRow.Key(site)
        if (maxAlias != null) {
            if (kind == null || kind == KIND_PASSWORD) return VaultRow.Login(maxLoginSite(maxAlias) ?: maxAlias, username)
            return VaultRow.Key(site)
        }
        if (kind != null && kind != KIND_PASSWORD) return VaultRow.Key(site)
        return VaultRow.Login(site, username)
    }

    /**
     * True for a card number or card security code. The one definition both the panel and the
     * MCP refusal use, so a row the panel masks is never handed out raw by a tool.
     */
    fun isPayment(website: String, tags: List<String>): Boolean =
        classify(website, "", null, tags).let { it is VaultRow.Card || it is VaultRow.CardCode }

    /**
     * A connector credential: tagged `connector`, or any `fluck/` key outside a vault
     * (`fluck/<ws>/<connector>/<VAR>` and deeper). Shared by the panel and the MCP refusal.
     */
    fun isConnectorToken(website: String, tags: List<String>): Boolean {
        val site = website.trim()
        return tags.any { it.trim().equals(CONNECTOR_TAG, ignoreCase = true) } ||
            (site.lowercase().startsWith(MAX_PREFIX) && maxVaultAlias(site) == null)
    }

    /** The alias of an old-shape Max vault row `fluck/<ws>/vault/<alias>`, else null. */
    internal fun maxVaultAlias(website: String): String? {
        val parts = website.split('/', limit = 4)
        if (parts.size != 4 || !parts[0].equals("fluck", true) || !parts[2].equals("vault", true)) return null
        return parts[3].takeIf { it.isNotBlank() }
    }

    /** Max login aliases are `<site>` or `<label>@<site>`. */
    internal fun maxLoginSite(alias: String): String? {
        val site = if ('@' in alias) alias.substringAfterLast('@') else alias
        return site.lowercase().takeIf { HOST.matches(it) && '.' in it }
    }

    private fun card(site: String, maxAlias: String?, username: String, notes: String?): VaultRow.Card {
        val user = username.trim()
        val fromUser = user.substringAfterLast(' ', "").takeIf { FOUR_DIGITS.matches(it) }
        val fromSite = site.substringAfterLast('-', "").takeIf { site.startsWith(CARD_PREFIX) && FOUR_DIGITS.matches(it) }
        val fromAlias = maxAlias?.removePrefix("card-")?.takeIf { FOUR_DIGITS.matches(it) }
        val last4 = fromUser ?: fromSite ?: fromAlias.orEmpty()

        val brand = user.substringBeforeLast(' ', "").takeIf { fromUser != null && it.isNotBlank() }
            ?: site.removePrefix(CARD_PREFIX).substringBeforeLast('-', "")
                .takeIf { site.startsWith(CARD_PREFIX) && it.isNotBlank() }
                ?.let(::brandName)
            ?: "Card"

        val o = parseObject(notes)
        return VaultRow.Card(
            brand = if (brand.equals("card", ignoreCase = true)) "Card" else brand,
            last4 = last4,
            expMonth = o.str("exp_month").trim().let { if (it.length == 1) "0$it" else it },
            expYear = o.str("exp_year").trim().let { if (it.length == 4) it.takeLast(2) else it },
            holder = o.str("name").trim(),
        )
    }

    private fun brandName(lower: String): String = when (lower.lowercase()) {
        "visa" -> "Visa"
        "mastercard" -> "Mastercard"
        "amex" -> "Amex"
        "discover" -> "Discover"
        else -> lower.replaceFirstChar { it.uppercase() }
    }

    // ---- Writes ----

    /** Same rule as Max's brandOf and the host autofill writer, so both spell `card/<brand>-<last4>` alike. */
    fun brandOf(pan: String): String = when {
        pan.startsWith("4") -> "Visa"
        pan.length >= 2 && pan.substring(0, 2).toIntOrNull() in 51..55 -> "Mastercard"
        pan.length >= 4 && pan.substring(0, 4).toIntOrNull() in 2221..2720 -> "Mastercard"
        pan.startsWith("34") || pan.startsWith("37") -> "Amex"
        pan.startsWith("6011") || pan.startsWith("65") -> "Discover"
        else -> "card"
    }

    fun luhn(pan: String): Boolean {
        if (pan.length !in 13..19 || pan.any { !it.isDigit() }) return false
        var sum = 0
        var alternate = false
        for (i in pan.indices.reversed()) {
            var d = pan[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /** `MM/YY` (or `M/YYYY`) to two-digit month and year, or null. */
    fun parseExpiry(raw: String): Pair<String, String>? {
        val m = raw.substringBefore('/', "").trim().toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val y = raw.substringAfter('/', "").trim().toIntOrNull()
            ?.let { if (it in 0..99) it else if (it in 2000..2099) it % 100 else null } ?: return null
        // ASCII digits regardless of locale: Max and host autofill parse these notes.
        return m.toString().padStart(2, '0') to y.toString().padStart(2, '0')
    }

    fun isExpired(month: String, year: String, now: java.time.YearMonth): Boolean {
        val m = month.toIntOrNull()?.takeIf { it in 1..12 } ?: return false
        val y = year.toIntOrNull()?.takeIf { it in 0..99 } ?: return false
        return java.time.YearMonth.of(2000 + y, m).isBefore(now)
    }

    /** Notes for a card, in Max's notes() shape (limit/currency/billing blank on a hand-added card). */
    fun cardNotes(holder: String, expMonth: String, expYear: String): String =
        buildJsonObject {
            put("name", holder)
            put("exp_month", expMonth)
            put("exp_year", expYear)
            put("limit_minor", 0)
            put("currency", "")
            put(
                "billing",
                buildJsonObject {
                    put("line1", "")
                    put("city", "")
                    put("postal", "")
                    put("country", "")
                },
            )
        }.toString()

    data class NewRow(val website: String, val username: String, val password: String, val notes: String, val tags: List<String>)

    /** Null when the number is not a card. */
    fun newCard(number: String, expiry: String, holder: String): NewRow? {
        val digits = number.filter { it.isDigit() }
        if (!luhn(digits)) return null
        val (month, year) = parseExpiry(expiry) ?: return null
        val brand = brandOf(digits)
        val last4 = digits.takeLast(4)
        return NewRow(
            website = "$CARD_PREFIX${brand.lowercase()}-$last4",
            username = "$brand $last4",
            password = digits,
            notes = cardNotes(holder.trim(), month, year),
            tags = listOf(KIND_CARD),
        )
    }

    fun newAddress(fields: AddressFields, takenWebsites: Set<String>): NewRow {
        val stem = slug(fields.name.ifBlank { fields.street }).ifEmpty { "home" }
        var label = stem
        var n = 2
        while ("$ADDRESS_PREFIX$label" in takenWebsites) label = "$stem-${n++}"
        return NewRow(
            website = "$ADDRESS_PREFIX$label",
            username = fields.name.ifBlank { "Address" },
            password = ADDRESS_PASSWORD_FILLER,
            notes = fields.toJson(),
            tags = listOf(KIND_ADDRESS),
        )
    }

    internal fun slug(s: String): String =
        s.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(32)
            .trimEnd('-')

    internal fun parseObject(raw: String?): JsonObject? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() }
}

private fun JsonObject?.str(key: String): String = (this?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Caps prefetches that add no rows to the current kind. Not thread-safe; used from one
 * composition's effect.
 */
internal class PrefetchBudget(private val maxBarren: Int = 2) {
    private var lastCount = -1
    private var barren = 0

    fun allow(count: Int): Boolean {
        barren = if (count == lastCount) barren + 1 else 0
        lastCount = count
        return barren < maxBarren
    }

    fun reset() {
        lastCount = -1
        barren = 0
    }
}
