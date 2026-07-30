package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins the preference file's read-modify-write behaviour.
 *
 * One file holds the active provider *and* every per-provider model selection, so a write
 * that clobbers the other field, or a truncated write, loses configuration the user set by
 * hand. There is no second copy to recover from — for providers keyed by an environment
 * variable this file is the only record of the model choice.
 */
class ActiveProviderPrefsTest {
    private fun prefsIn(dir: File = Files.createTempDirectory("ai-prefs").toFile()): Pair<ActiveProviderPrefs, File> =
        ActiveProviderPrefs(bossRootDir = dir) to dir

    @Test
    fun `writing a model preserves the active provider and vice versa`() =
        runTest {
            // The two fields are written through the same transform, so a copy() that
            // dropped one would silently reset it on the next unrelated write.
            val (prefs, _) = prefsIn()
            prefs.write(ProviderRegistry.OPENAI)
            prefs.writeModel(ProviderRegistry.ANTHROPIC, "claude-opus-5")

            assertEquals(ProviderRegistry.OPENAI, prefs.read())
            assertEquals(mapOf(ProviderRegistry.ANTHROPIC to "claude-opus-5"), prefs.readModels())

            prefs.write(ProviderRegistry.TOGETHER)
            assertEquals(mapOf(ProviderRegistry.ANTHROPIC to "claude-opus-5"), prefs.readModels())
        }

    @Test
    fun `concurrent writers do not drop each other's entry`() =
        runTest {
            // Mutex + read-modify-write: without the lock, interleaved writers each read the
            // same base and the last one wins, losing the others' model selections.
            val (prefs, _) = prefsIn()
            val ids = ProviderRegistry.all.map { it.id }

            coroutineScope {
                ids.map { id -> async { prefs.writeModel(id, "model-for-$id") } }.awaitAll()
            }

            assertEquals(ids.size, prefs.readModels().size)
            ids.forEach { assertEquals("model-for-$it", prefs.readModels()[it]) }
        }

    @Test
    fun `the temp file is not left behind after a write`() =
        runTest {
            // Temp-then-rename is only safe if the temp is consumed; a stale .tmp beside the
            // real file is how a half-written state survives to the next read.
            val (prefs, dir) = prefsIn()
            prefs.write(ProviderRegistry.XAI)

            assertFalse(
                File(dir, "ai_provider_prefs.json.tmp").exists(),
                "temp file survived the write",
            )
            assertEquals(ProviderRegistry.XAI, prefs.read())
        }

    @Test
    fun `a corrupt file degrades to defaults instead of throwing`() =
        runTest {
            // Hand-editable plain file: a parse failure must not take out provider
            // resolution, which awaits this read during registration.
            val (prefs, dir) = prefsIn()
            File(dir, "ai_provider_prefs.json").writeText("{not json")

            assertNull(prefs.read())
            assertEquals(emptyMap(), prefs.readModels())

            // ...and a later write repairs it rather than failing forever.
            prefs.write(ProviderRegistry.GOOGLE)
            assertEquals(ProviderRegistry.GOOGLE, prefs.read())
        }

    @Test
    fun `a provider id that no longer exists is ignored`() =
        runTest {
            // Ids are persisted, so a removed or renamed provider must not resurface as an
            // active selection that nothing can resolve.
            val (prefs, dir) = prefsIn()
            File(dir, "ai_provider_prefs.json").writeText(
                """{"activeProviderId":"RETIRED","modelByProvider":{"RETIRED":"m"}}""",
            )

            assertNull(prefs.read())
            assertEquals(emptyMap(), prefs.readModels())
        }
}
