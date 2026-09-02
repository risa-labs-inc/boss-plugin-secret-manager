package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [OllamaSystemCheck] against fake machines — every dependency is a constructor
 * parameter precisely so this never has to touch the real filesystem, `PATH` or `Desktop`.
 */
class OllamaSystemCheckTest {
    private fun check(
        binaryPresent: Boolean = false,
        ramBytes: Long? = null,
        browseResult: Boolean = true,
    ) = OllamaSystemCheck(
        path = "/usr/bin",
        home = "/home/test",
        isWindows = false,
        isExecutable = { it.name == "ollama" && binaryPresent },
        physicalMemoryBytes = { ramBytes },
        browse = { browseResult },
    )

    @Test
    fun `reports the binary missing when nothing on the candidate paths is executable`() {
        assertFalse(check(binaryPresent = false).current().binaryFound)
    }

    @Test
    fun `finds the binary once any candidate path is executable`() {
        assertTrue(check(binaryPresent = true).current().binaryFound)
    }

    @Test
    fun `converts bytes to GB and passes through a missing reading as null`() {
        val sixteenGb = 16L * 1024 * 1024 * 1024
        assertEquals(16.0, check(ramBytes = sixteenGb).current().totalRamGb)
        assertEquals(null, check(ramBytes = null).current().totalRamGb)
    }

    @Test
    fun `unknown RAM meets the minimum rather than blocking the provider`() {
        val info = OllamaSystemInfo(binaryFound = false, totalRamGb = null)
        assertTrue(info.meetsMinimum)
    }

    @Test
    fun `RAM below the published floor does not meet the minimum`() {
        assertFalse(OllamaSystemInfo(binaryFound = true, totalRamGb = 4.0).meetsMinimum)
        assertTrue(OllamaSystemInfo(binaryFound = true, totalRamGb = 8.0).meetsMinimum)
    }

    @Test
    fun `suggested models scale with RAM and stay empty below the floor`() {
        assertEquals(emptyList(), OllamaSystemCheck.suggestedModelsFor(4.0))
        assertTrue(OllamaSystemCheck.suggestedModelsFor(8.0).isNotEmpty())
        assertTrue(OllamaSystemCheck.suggestedModelsFor(16.0).any { it.tag.contains("8b") })
        assertTrue(OllamaSystemCheck.suggestedModelsFor(32.0).any { it.tag.contains("32b") })
        assertTrue(OllamaSystemCheck.suggestedModelsFor(64.0).any { it.tag.contains("70b") })
    }

    @Test
    fun `a higher tier does not also repeat the lower tiers' models`() {
        // The shortlist is the single best-fitting tier, not everything at or below it -
        // otherwise a 64 GB machine would be told to consider a 3B model too.
        val topTier = OllamaSystemCheck.suggestedModelsFor(64.0)
        assertFalse(topTier.any { it.tag.contains("3b") })
    }

    @Test
    fun `opening the install page hands the real URL to the injected browser`() {
        var seen: URI? = null
        val opened =
            OllamaSystemCheck(browse = { uri -> seen = uri; true }).openInstallPage()

        assertTrue(opened)
        assertEquals(OllamaSystemCheck.INSTALL_URL, seen.toString())
    }

    @Test
    fun `a browser that cannot open reports failure rather than throwing`() {
        assertFalse(check(browseResult = false).openInstallPage())
    }

    @Test
    fun `home-relative candidate directories are expanded before checking`() {
        val checked = mutableListOf<File>()
        val custom =
            OllamaSystemCheck(
                path = "",
                home = "/home/test",
                isWindows = false,
                isExecutable = { file -> checked += file; false },
            )
        custom.current()

        assertTrue(
            checked.any { it.path == "/home/test/.ollama/bin/ollama" },
            "expected an expanded ~/.ollama/bin candidate, saw: $checked",
        )
    }
}
