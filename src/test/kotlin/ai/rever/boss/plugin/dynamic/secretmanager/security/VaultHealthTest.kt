package ai.rever.boss.plugin.dynamic.secretmanager.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the local [VaultHealth] analysis, including the review fixes: paired reuse members, Unicode classing, non-empty reasons. */
class VaultHealthTest {
    private fun rec(
        id: String,
        site: String,
        password: String,
    ) = VaultHealth.PasswordRecord(id, site, password)

    @Test
    fun `reuse groups shared passwords with paired id and site`() {
        val report =
            VaultHealth.analyze(
                listOf(
                    rec("1", "github.com", "reused-pw"),
                    rec("2", "gitlab.com", "reused-pw"),
                    rec("3", "unique.com", "Str0ng!Passphrase"),
                ),
            )
        assertEquals(1, report.reuseGroups.size)
        val group = report.reuseGroups.single()
        assertEquals(2, group.count)
        assertEquals(setOf("1" to "github.com", "2" to "gitlab.com"), group.members.map { it.id to it.site }.toSet())
    }

    @Test
    fun `weak passwords are flagged and strong ones are not`() {
        val report = VaultHealth.analyze(listOf(rec("1", "a.com", "abc"), rec("2", "b.com", "Xy9!abcdefgh")))
        assertEquals(listOf("1"), report.weakEntries.map { it.id }, "only the short one is weak")
        assertEquals(VaultHealth.Strength.WEAK, report.weakEntries.single().strength)
    }

    @Test
    fun `a non-ASCII password is not mislabelled as one kind of character`() {
        // Cyrillic upper + lower, a digit and a symbol.
        val (strength, reasons) = VaultHealth.rate("Пароли1!")
        assertFalse(reasons.any { it.contains("one kind", ignoreCase = true) }, "reasons: $reasons")
        assertTrue(strength != VaultHealth.Strength.WEAK)
    }

    @Test
    fun `a non-strong entry always has at least one reason`() {
        val (strength, reasons) = VaultHealth.rate("Abcdef1!") // 8 chars, 4 classes -> FAIR
        assertEquals(VaultHealth.Strength.FAIR, strength)
        assertTrue(reasons.isNotEmpty(), "a FAIR entry must say why")
    }

    @Test
    fun `a blank password is neither grouped nor counted`() {
        val report = VaultHealth.analyze(listOf(rec("1", "a.com", "   "), rec("2", "b.com", "Str0ng!Passphrase")))
        assertEquals(1, report.analyzedCount)
        assertTrue(report.reuseGroups.isEmpty())
    }

    @Test
    fun `weak entries are ordered weakest first`() {
        val report = VaultHealth.analyze(listOf(rec("1", "a.com", "Abcdef1!"), rec("2", "b.com", "abc")))
        assertEquals(listOf("2", "1"), report.weakEntries.map { it.id }, "WEAK before FAIR")
    }
}
