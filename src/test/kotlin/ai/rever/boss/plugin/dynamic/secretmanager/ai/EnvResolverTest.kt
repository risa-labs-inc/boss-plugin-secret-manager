package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the `~/.boss/env_vars` parsing.
 *
 * This file is the documented workaround for packaged builds, which inherit no shell
 * environment — so for some users it is the only way a key arrives, and a parsing slip
 * shows up as "my key isn't picked up" with nothing in the log. `=` inside a value is the
 * case worth locking down: API keys and URLs contain them routinely.
 */
class EnvResolverTest {
    private val tempDir: File = Files.createTempDirectory("env-resolver").toFile()

    private fun resolverWith(contents: String): EnvResolver {
        File(tempDir, "env_vars").writeText(contents)
        // uniqueName() already keeps these off real variables; stubbing the other sources as
        // well means the suite never spawns launchctl and stays fast on macOS.
        return EnvResolver(
            bossRootDir = tempDir,
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )
    }

    /** A name no real environment will define, so only the file can satisfy it. */
    private fun uniqueName(suffix: String) = "BOSS_TEST_ENV_${suffix}_${System.nanoTime()}"

    @Test
    fun `reads a plain assignment`() =
        runTest {
            val name = uniqueName("PLAIN")
            val resolver = resolverWith("$name=sk-value")
            assertEquals("sk-value", resolver.resolve(listOf(name)))
        }

    @Test
    fun `keeps equals signs inside the value`() =
        runTest {
            // split(limit = 2): a key ending in padding, or any URL with a query string,
            // would otherwise be truncated at the first '='.
            val name = uniqueName("EQUALS")
            val resolver = resolverWith("$name=abc==def=ghi")
            assertEquals("abc==def=ghi", resolver.resolve(listOf(name)))
        }

    @Test
    fun `ignores comments and blank lines`() =
        runTest {
            val name = uniqueName("COMMENT")
            val resolver =
                resolverWith(
                    """
                    # a comment
                    $name=real

                    # $name=commented-out
                    """.trimIndent(),
                )
            assertEquals("real", resolver.resolve(listOf(name)))
        }

    @Test
    fun `trims surrounding whitespace`() =
        runTest {
            val name = uniqueName("TRIM")
            val resolver = resolverWith("   $name  =  spaced   ")
            assertEquals("spaced", resolver.resolve(listOf(name)))
        }

    @Test
    fun `a blank value is treated as absent`() =
        runTest {
            val name = uniqueName("BLANK")
            val resolver = resolverWith("$name=   ")
            assertNull(resolver.resolve(listOf(name)))
        }

    @Test
    fun `a malformed line without a separator is skipped`() =
        runTest {
            val name = uniqueName("MALFORMED")
            val resolver = resolverWith("NOT_AN_ASSIGNMENT\n$name=fine")
            assertEquals("fine", resolver.resolve(listOf(name)))
        }

    @Test
    fun `resolves the first name that has a value`() =
        runTest {
            // Google accepts GEMINI_API_KEY or GOOGLE_API_KEY; registry order is priority.
            val first = uniqueName("FIRST")
            val second = uniqueName("SECOND")
            val resolver = resolverWith("$second=from-second")

            assertEquals("from-second", resolver.resolve(listOf(first, second)))
            assertEquals(second, resolver.resolveSourceName(listOf(first, second)))
        }

    @Test
    fun `reports null when no name matches`() =
        runTest {
            val resolver = resolverWith("SOMETHING_ELSE=x")
            assertNull(resolver.resolve(listOf(uniqueName("MISSING"))))
            assertNull(resolver.resolveSourceName(listOf(uniqueName("MISSING"))))
        }

    @Test
    fun `a missing env file resolves to null rather than throwing`() =
        runTest {
            val emptyDir = Files.createTempDirectory("env-resolver-empty").toFile()
            val resolver =
                EnvResolver(
                    bossRootDir = emptyDir,
                    processEnv = { null },
                    systemProperty = { null },
                    useLaunchctl = false,
                )
            assertNull(resolver.resolve(listOf(uniqueName("NOFILE"))))
        }

    @Test
    fun `invalidate re-reads a changed file`() =
        runTest {
            val name = uniqueName("CACHED")
            val resolver = resolverWith("$name=before")
            assertEquals("before", resolver.resolve(listOf(name)))

            File(tempDir, "env_vars").writeText("$name=after")
            // Memoised, including misses — the value must not change until invalidated,
            // and must change afterwards.
            assertEquals("before", resolver.resolve(listOf(name)))

            resolver.invalidate()
            assertEquals("after", resolver.resolve(listOf(name)))
        }
}
