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
    fun `matches the version Gradle is building`() {
        // Gradle injects this, so the assertion is not circular. Comparing against the
        // bundled plugin.json instead would pass for *any* value in that file — including
        // the unstamped 1.0.9 committed to git, which is valid semver and would sail through
        // a format-only check while build.gradle.kts said something else.
        val expected =
            System.getProperty("boss.plugin.expectedVersion")
                ?: error("boss.plugin.expectedVersion not set — see the Test task in build.gradle.kts")

        assertEquals(expected, plugin.version)
    }

    @Test
    fun `the bundled manifest agrees with the reported version`() {
        // Both must match Gradle, so they must match each other. This is what catches
        // processResources not having stamped the copy that actually reaches the jar.
        val manifest =
            javaClass.classLoader
                .getResourceAsStream("META-INF/boss-plugin/plugin.json")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("plugin.json missing from the classpath")

        val declared =
            Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
                ?: error("plugin.json declares no version")

        assertEquals(plugin.version, declared)
        assertEquals(System.getProperty("boss.plugin.expectedVersion"), declared)
    }

    @Test
    fun `looks like a semantic version`() {
        // Weak on its own — 1.0.9 would pass — but it catches a placeholder surviving
        // resource filtering, which would otherwise reach the store looking like a release.
        assertTrue(
            plugin.version.matches(Regex("""\d+\.\d+\.\d+""")),
            "not a semantic version: ${plugin.version}",
        )
    }
}
