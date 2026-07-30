package ai.rever.boss.plugin.dynamic.secretmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins that the plugin reports a real version.
 *
 * This regressed once already: reading `javaClass.package?.implementationVersion` looked
 * correct — `buildPluginJar` really does write `Implementation-Version` into the manifest —
 * but `getImplementationVersion()` returns null in practice, verified against a plain
 * `URLClassLoader` (what the host's `PluginClassLoader` extends). The plugin shipped
 * reporting its version to the host and store as `"unknown"`, and nothing caught it because
 * no test asserted the value.
 *
 * The version now comes from the bundled `plugin.json` that `processResources` stamps, which
 * is a plain classloader resource and so loader-independent.
 */
class PluginVersionTest {
    private val plugin = SecretManagerDynamicPlugin()

    @Test
    fun `reports a version rather than unknown`() {
        assertNotEquals("unknown", plugin.version, "version fell back to the unknown sentinel")
        assertTrue(plugin.version.isNotBlank())
    }

    @Test
    fun `matches the version in the bundled plugin manifest`() {
        // Same file the host and store read, so agreeing with it is the whole point — a
        // second source of truth is what drifted before.
        val manifest =
            javaClass.classLoader
                .getResourceAsStream("META-INF/boss-plugin/plugin.json")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("plugin.json missing from the classpath")

        val declared =
            Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
                ?: error("plugin.json declares no version")

        assertEquals(declared, plugin.version)
    }

    @Test
    fun `looks like a semantic version`() {
        // Catches a stray placeholder surviving resource filtering, which would otherwise
        // reach the store as a valid-looking release.
        assertTrue(
            plugin.version.matches(Regex("""\d+\.\d+\.\d+""")),
            "not a semantic version: ${plugin.version}",
        )
    }
}
