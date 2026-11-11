package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultRowsTest {
    private val testPan = "4242424242424242"
    private val maxCardNotes =
        """{"name":"Jane Doe","exp_month":"08","exp_year":"29","limit_minor":5000,"currency":"USD",""" +
            """"billing":{"line1":"1 Main St","city":"Springfield","postal":"12345","country":"US"}}"""

    private fun row(
        website: String,
        username: String = "",
        password: String = "x",
        notes: String? = null,
        tags: List<String> = emptyList(),
    ) = SecretEntryData(
        id = "id",
        website = website,
        username = username,
        password = password,
        notes = notes,
        tags = tags,
        createdAt = "",
        updatedAt = "",
    )

    private fun classify(r: SecretEntryData, ai: Boolean = false) = VaultRows.classify(r, ai)

    // ---- Logins ----

    @Test
    fun plainRowWithoutKindIsLogin() {
        val r = classify(row("https://github.com", "octo"))
        assertEquals(VaultRow.Login("https://github.com", "octo"), r)
    }

    @Test
    fun kindPasswordRowIsLogin() {
        val r = classify(row("example.com", "me", tags = listOf("kind:password")))
        assertEquals(VaultKind.LOGIN, r.kind)
        assertEquals("example.com", r.title)
    }

    @Test
    fun oldMaxLoginShowsRealSite() {
        val r = classify(row("fluck/ws1/vault/github.com", "octo", notes = "work", tags = listOf("kind:password", "fluck-ws:ws1")))
        assertEquals(VaultRow.Login("github.com", "octo"), r)
    }

    @Test
    fun oldMaxNamedLoginAliasShowsSiteAfterAt() {
        val r = classify(row("fluck/ws1/vault/work@mail.example.com", "me", tags = listOf("kind:password")))
        assertEquals("mail.example.com", r.title)
    }

    @Test
    fun oldMaxLoginWithUnparseableAliasKeepsAlias() {
        val r = classify(row("fluck/ws1/vault/localthing", "me", tags = listOf("kind:password")))
        assertEquals(VaultKind.LOGIN, r.kind)
        assertEquals("localthing", r.title)
    }

    // ---- Cards ----

    @Test
    fun newShapeCard() {
        val r = classify(row("card/visa-4242", "Visa 4242", password = testPan, notes = maxCardNotes, tags = listOf("kind:card")))
        assertEquals(VaultRow.Card("Visa", "4242", "08", "29", "Jane Doe"), r)
        assertEquals("08/29", (r as VaultRow.Card).expiry)
    }

    @Test
    fun oldMaxShapeCard() {
        val r = classify(row("fluck/ws1/vault/card-4242", "Visa 4242", password = testPan, notes = maxCardNotes, tags = listOf("kind:card", "fluck-ws:ws1")))
        assertIs<VaultRow.Card>(r)
        assertEquals("Visa", r.brand)
        assertEquals("4242", r.last4)
        assertEquals("Jane Doe", r.holder)
    }

    @Test
    fun cardFallsBackToWebsiteAndAliasForBrandAndLast4() {
        val fromSite = classify(row("card/mastercard-4444", "", notes = null, tags = listOf("kind:card"))) as VaultRow.Card
        assertEquals("Mastercard", fromSite.brand)
        assertEquals("4444", fromSite.last4)
        val fromAlias = classify(row("fluck/ws/vault/card-1881", "", tags = listOf("kind:card"))) as VaultRow.Card
        assertEquals("1881", fromAlias.last4)
        assertEquals("Card", fromAlias.brand)
    }

    @Test
    fun cardWithMissingOrBrokenNotesIsBlankNotCrash() {
        for (notes in listOf(null, "", "not json", "[1,2]", """{"exp_month":5}""")) {
            val r = classify(row("card/visa-4242", "Visa 4242", notes = notes, tags = listOf("kind:card"))) as VaultRow.Card
            assertEquals("4242", r.last4)
            assertEquals("", r.holder)
            assertEquals("", r.expiry)
        }
    }

    @Test
    fun cardPrefixWithoutKindTagIsStillACard() {
        assertEquals(VaultKind.CARD, classify(row("card/visa-4242", "Visa 4242")).kind)
    }

    @Test
    fun fourDigitYearAndSingleDigitMonthAreNormalised() {
        val r = classify(row("card/visa-4242", "Visa 4242", notes = """{"exp_month":"3","exp_year":"2031"}""", tags = listOf("kind:card"))) as VaultRow.Card
        assertEquals("03/31", r.expiry)
    }

    // ---- Masking ----

    @Test
    fun cardViewNeverCarriesTheFullNumber() {
        val r = classify(row("card/visa-4242", "Visa 4242", password = testPan, notes = maxCardNotes, tags = listOf("kind:card")))
        val shown = listOf(r.title, r.toString(), (r as VaultRow.Card).expiry, r.holder, r.brand, r.last4).joinToString("|")
        assertFalse(testPan in shown)
        assertFalse(testPan.take(12) in shown)
        assertTrue(r.title.contains("•••• 4242"))
    }

    @Test
    fun cardNumberInPasswordIsNeverUsedEvenWhenUsernameIsBlank() {
        // last4 must come from website/username/alias, never the password column.
        val r = classify(row("card/visa", "", password = testPan, tags = listOf("kind:card"))) as VaultRow.Card
        assertEquals("", r.last4)
        assertFalse(testPan in r.toString())
    }

    // ---- Addresses ----

    @Test
    fun addressRow() {
        val notes = """{"name":"Jane Doe","street":"1 Main St","city":"Springfield","state":"IL","zip":"62701","country":"US","email":"j@example.test"}"""
        val r = classify(row("address/jane-doe", "Jane Doe", password = "-", notes = notes, tags = listOf("kind:address")))
        assertIs<VaultRow.Address>(r)
        assertEquals("Jane Doe", r.title)
        assertEquals("1 Main St, Springfield, IL 62701, US", r.fields.oneLine())
    }

    @Test
    fun addressWithoutNameFallsBackToLabelAndBrokenNotesAreBlank() {
        val r = classify(row("address/home", "Address", notes = "{oops", tags = listOf("kind:address"))) as VaultRow.Address
        assertEquals("home", r.title)
        assertEquals("", r.fields.oneLine())
    }

    @Test
    fun addressEditKeepsUnknownNoteKeys() {
        val base = VaultRows.parseObject("""{"name":"A","street":"x","extra":"keep"}""")
        val json = AddressFields(name = "B", street = "y").toJson(base)
        val back = VaultRows.parseObject(json)!!
        assertEquals("keep", back["extra"].toString().trim('"'))
        assertEquals("B", AddressFields.parse(back).name)
    }

    // ---- Keys & tokens ----

    @Test
    fun connectorTokenIsKeyWithItsKey() {
        val r = classify(row("fluck/ws1/github/GH_TOKEN", "x", tags = listOf("fluck", "connector")))
        assertEquals(VaultRow.Key("fluck/ws1/github/GH_TOKEN"), r)
    }

    @Test
    fun kindSecretIsKey() {
        assertEquals(VaultKind.KEY, classify(row("fluck/ws1/vault/openai", "k", tags = listOf("kind:secret"))).kind)
        assertEquals(VaultKind.KEY, classify(row("stripe", "k", tags = listOf("kind:secret"))).kind)
    }

    @Test
    fun maxCvvRowIsSealedCardCode() {
        val r = classify(row("fluck/ws1/vault/card-4242-cvv", "sealed", password = "sealed-blob", tags = listOf("kind:cvv", "fluck-ws:ws1")))
        assertEquals(VaultRow.CardCode("Card security code · card-4242"), r)
        assertEquals(VaultKind.KEY, r.kind)
        assertFalse("sealed-blob" in r.toString())
    }

    @Test
    fun anyKindCvvRowIsCardCodeWhateverItsWebsite() {
        assertIs<VaultRow.CardCode>(classify(row("bank.test-cvv", tags = listOf("fluck-ws:w", "kind:cvv"))))
    }

    @Test
    fun cvvInCardNotesNeverReachesTheCardView() {
        val r = classify(row("card/visa-4242", "Visa 4242", notes = """{"name":"J","cvv":"987","exp_month":"01","exp_year":"30"}""", tags = listOf("kind:card")))
        assertFalse("987" in r.toString())
    }

    // ---- isPayment: shared by the panel and the MCP refusal ----

    @Test
    fun isPaymentCoversEveryCardAndCodeShape() {
        assertTrue(VaultRows.isPayment("card/visa-4242", emptyList()))
        assertTrue(VaultRows.isPayment("card/visa-4242", listOf("kind:card")))
        assertTrue(VaultRows.isPayment("fluck/w/vault/card-4242", listOf("kind:card", "fluck-ws:w")))
        assertTrue(VaultRows.isPayment("bank.test", listOf("fluck-ws:w", "kind:card")))
        assertTrue(VaultRows.isPayment("fluck/w/vault/card-4242-cvv", listOf("kind:cvv")))
        assertTrue(VaultRows.isPayment("card/visa-4242", listOf("api_key")))
    }

    @Test
    fun untaggedOldMaxCardAndCvvAliasesArePayment() {
        assertTrue(VaultRows.isPayment("fluck/w/vault/card-4242", emptyList()))
        assertTrue(VaultRows.isPayment("fluck/w/vault/card-4242-cvv", listOf("fluck-ws:w")))
        assertIs<VaultRow.Card>(classify(row("fluck/w/vault/card-4242", "Visa 4242")))
        assertIs<VaultRow.CardCode>(classify(row("fluck/w/vault/card-4242-cvv", "sealed")))
    }

    @Test
    fun untaggedAddressPrefixWithPlainNotesStaysALogin() {
        assertEquals(VaultKind.LOGIN, classify(row("address/book", "me", notes = "my notes")).kind)
        assertEquals(VaultKind.ADDRESS, classify(row("address/home", "me", notes = """{"street":"1 Main"}""")).kind)
    }

    @Test
    fun isExpiredComparesMonthAndYear() {
        val now = java.time.YearMonth.of(2026, 11)
        assertTrue(VaultRows.isExpired("10", "26", now))
        assertFalse(VaultRows.isExpired("11", "26", now))
        assertFalse(VaultRows.isExpired("01", "27", now))
    }

    @Test
    fun isPaymentIsFalseForLoginsAddressesAndKeys() {
        assertFalse(VaultRows.isPayment("https://github.com", emptyList()))
        assertFalse(VaultRows.isPayment("fluck/w/vault/github.com", listOf("kind:password")))
        assertFalse(VaultRows.isPayment("address/home", listOf("kind:address")))
        assertFalse(VaultRows.isPayment("fluck/w/github/GH_TOKEN", listOf("fluck", "connector")))
    }

    // ---- parseExpiry / slug ----

    @Test
    fun parseExpiryEdges() {
        assertEquals("01" to "31", VaultRows.parseExpiry("1/2031"))
        assertEquals("08" to "29", VaultRows.parseExpiry(" 08 / 29 "))
        assertNull(VaultRows.parseExpiry("00/29"))
        assertNull(VaultRows.parseExpiry("08/"))
        assertNull(VaultRows.parseExpiry("08/1999"))
    }

    @Test
    fun nonAsciiNameSlugsToHome() {
        assertEquals("", VaultRows.slug("山田太郎"))
        assertEquals("address/home", VaultRows.newAddress(AddressFields(name = "山田太郎"), emptySet()).website)
    }

    @Test
    fun apiKeyAndAiProviderRowsAreKeys() {
        assertEquals(VaultKind.KEY, classify(row("openai", "k", tags = listOf("api_key"))).kind)
        assertEquals(VaultKind.KEY, classify(row("anthropic", "k"), ai = true).kind)
    }

    // ---- Writes ----

    @Test
    fun newCardUsesCanonicalShape() {
        val r = assertNotNull(VaultRows.newCard("4242 4242 4242 4242", "8/29", " Jane Doe "))
        assertEquals("card/visa-4242", r.website)
        assertEquals("Visa 4242", r.username)
        assertEquals(listOf("kind:card"), r.tags)
        val back = classify(row(r.website, r.username, notes = r.notes, tags = r.tags)) as VaultRow.Card
        assertEquals(VaultRow.Card("Visa", "4242", "08", "29", "Jane Doe"), back)
        assertFalse(testPan in r.notes)
    }

    @Test
    fun newCardRejectsBadNumberOrExpiry() {
        assertNull(VaultRows.newCard("4242424242424241", "08/29", ""))
        assertNull(VaultRows.newCard("4242424242424242", "13/29", ""))
        assertNull(VaultRows.newCard("4242424242424242", "0829", ""))
    }

    @Test
    fun brandOfMatchesMax() {
        assertEquals("Visa", VaultRows.brandOf("4242424242424242"))
        assertEquals("Mastercard", VaultRows.brandOf("5555555555554444"))
        assertEquals("Mastercard", VaultRows.brandOf("2223003122003222"))
        assertEquals("Amex", VaultRows.brandOf("378282246310005"))
        assertEquals("Discover", VaultRows.brandOf("6011111111111117"))
        assertEquals("card", VaultRows.brandOf("3530111333300000"))
    }

    @Test
    fun newAddressPicksFreeLabel() {
        val f = AddressFields(name = "Jane Doe", street = "1 Main St")
        val r = VaultRows.newAddress(f, setOf("address/jane-doe", "address/jane-doe-2"))
        assertEquals("address/jane-doe-3", r.website)
        assertEquals(listOf("kind:address"), r.tags)
        assertEquals(VaultRows.ADDRESS_PASSWORD_FILLER, r.password)
        val back = classify(row(r.website, r.username, notes = r.notes, tags = r.tags)) as VaultRow.Address
        assertEquals(f, back.fields)
    }
}
