package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.dynamic.secretmanager.PluginVersionSource.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the manifest-selection rules, which have been wrong twice and both times produced a
 * plausible-looking wrong version rather than an obvious failure.
 *
 * These rules were previously untestable because they were tangled with the classloader, so
 * neither the `pluginId` filter nor the own-jar preference had any coverage.
 */
class PluginVersionSourceTest {
    private val ourJar = "jar:file:/plugins/boss-plugin-secret-manager-1.2.6.jar!/"
    private val hostJar = "jar:file:/app/BossConsole.jar!/"
    private val neighbourJar = "jar:file:/plugins/boss-plugin-user-secret-list-1.2.4.jar!/"

    private fun manifest(
        pluginId: String,
        version: String,
    ) = """{"manifestVersion":1,"pluginId":"$pluginId","version":"$version"}"""

    private fun candidate(
        root: String,
        pluginId: String = PluginVersionSource.PLUGIN_ID,
        version: String,
    ) = Candidate("$root${PluginVersionSource.MANIFEST_RESOURCE}", manifest(pluginId, version))

    @Test
    fun `ignores a neighbour plugin's manifest`() {
        // Every BOSS plugin ships this resource at the same path, so the first match on a
        // parent-first enumeration is not necessarily ours.
        val picked =
            PluginVersionSource.pickOwnManifest(
                listOf(
                    candidate(neighbourJar, pluginId = "ai.rever.boss.plugin.dynamic.usersecretlist", version = "9.9.9"),
                    candidate(ourJar, version = "1.2.6"),
                ),
                ownRoot = ourJar,
            )

        assertEquals("1.2.6", picked?.let { PluginVersionSource.versionIn(it) })
    }

    @Test
    fun `prefers our own jar over a stale copy of our own manifest`() {
        // The pluginId filter alone does not save us here: a bundled or stale copy of *our*
        // manifest on the host classpath matches it too, and getResources is parent-first.
        val picked =
            PluginVersionSource.pickOwnManifest(
                listOf(
                    candidate(hostJar, version = "1.0.9"),
                    candidate(ourJar, version = "1.2.6"),
                ),
                ownRoot = ourJar,
            )

        assertEquals("1.2.6", picked?.let { PluginVersionSource.versionIn(it) })
    }

    @Test
    fun `falls back to the pluginId match when the own root is unknown`() {
        // IDE and test runs load classes from a directory, where the root may not line up —
        // the pluginId match is what keeps those working.
        val picked =
            PluginVersionSource.pickOwnManifest(
                listOf(candidate(hostJar, version = "1.2.6")),
                ownRoot = null,
            )

        assertEquals("1.2.6", picked?.let { PluginVersionSource.versionIn(it) })
    }

    @Test
    fun `returns null when no candidate is ours`() {
        assertNull(
            PluginVersionSource.pickOwnManifest(
                listOf(candidate(neighbourJar, pluginId = "some.other.plugin", version = "3.0.0")),
                ownRoot = ourJar,
            ),
        )
    }

    @Test
    fun `returns null for an empty classpath`() {
        assertNull(PluginVersionSource.pickOwnManifest(emptyList(), ownRoot = ourJar))
    }

    @Test
    fun `a manifest with no version yields null rather than a wrong value`() {
        val candidate = Candidate(ourJar, """{"pluginId":"${PluginVersionSource.PLUGIN_ID}"}""")
        assertNull(PluginVersionSource.versionIn(candidate))
    }

    @Test
    fun `a blank version is treated as absent`() {
        val candidate = Candidate(ourJar, manifest(PluginVersionSource.PLUGIN_ID, "  "))
        assertNull(PluginVersionSource.versionIn(candidate))
    }

    @Test
    fun `reads the version key and not apiVersion or minBossVersion`() {
        // The regex is deliberately loose; what keeps it safe is only ever being applied to a
        // document already confirmed ours. Still worth pinning that neighbouring keys with
        // "Version" in the name don't win.
        val text =
            """{"pluginId":"${PluginVersionSource.PLUGIN_ID}","apiVersion":"1.0.20",
            "minBossVersion":"9.2.20","version":"1.2.6"}"""
        assertEquals("1.2.6", PluginVersionSource.versionIn(Candidate(ourJar, text)))
    }

    @Test
    fun `rootOf strips a jar entry back to the archive`() {
        assertEquals(
            "jar:file:/plugins/x.jar!/",
            PluginVersionSource.rootOf("jar:file:/plugins/x.jar!/a/b/C.class", "a/b/C.class"),
        )
    }

    @Test
    fun `rootOf strips a directory entry back to the classes root`() {
        assertEquals(
            "file:/build/classes/kotlin/main/",
            PluginVersionSource.rootOf("file:/build/classes/kotlin/main/a/b/C.class", "a/b/C.class"),
        )
    }

    @Test
    fun `rootOf tolerates a null url`() {
        assertNull(PluginVersionSource.rootOf(null, "a/b/C.class"))
    }
}
