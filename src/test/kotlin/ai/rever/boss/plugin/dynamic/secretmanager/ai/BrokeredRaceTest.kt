package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SecretEntryData
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two races and the one precedence rule around brokered credentials.
 *
 * Separate from [BrokeredCredentialTest] because these need a broker that can observe or
 * interleave with the store, rather than a fixed answer.
 */
class BrokeredRaceTest {
    private val risa = ProviderRegistry.find(ProviderRegistry.RISA_GLM)!!

    private fun envWith(contents: String = ""): EnvResolver {
        val dir: File = Files.createTempDirectory("brokered-race-env").toFile()
        File(dir, "env_vars").writeText(contents)
        return EnvResolver(
            bossRootDir = dir,
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )
    }

    @Test
    fun `a token minted across an invalidate is returned but not seated`() =
        runTest {
            // The window: fetch starts, invalidate() lands (a sign-out), fetch returns.
            // Seating it would serve a credential minted for the session that just ended,
            // for its whole reuse window. Mirrors the guard loadStoredSecrets already has.
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), envWith())
            var calls = 0
            var invalidateDuringFetch: (() -> Unit)? = null
            store.brokeredKeys =
                BrokeredKeySource {
                    calls += 1
                    invalidateDuringFetch?.invoke()
                    Result.success(BrokeredKey("sk-stale-session", refreshAfterSeconds = 3600))
                }
            invalidateDuringFetch = { store.invalidate() }

            // The caller that asked before the invalidation still gets its token ...
            assertEquals("sk-stale-session", store.loadAll().connections.getValue(risa.id).apiKey)
            // ... and nothing was cached, so the next read has to mint again.
            store.loadAll()

            assertEquals(2, calls, "a token minted across an invalidate must not be seated")
        }

    @Test
    fun `concurrent reads share one mint`() =
        runTest {
            // "Check access" invalidates and reloads, while the invalidations collector also
            // reloads - so two loadAll()s run at once, both miss the cache, and without a
            // mint lock both call the broker. The broker is a real resource at the far end.
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), envWith())
            var calls = 0
            store.brokeredKeys =
                BrokeredKeySource {
                    calls += 1
                    Result.success(BrokeredKey("sk-shared", refreshAfterSeconds = 3600))
                }

            val keys =
                listOf(
                    async { store.loadAll().connections.getValue(risa.id).apiKey },
                    async { store.loadAll().connections.getValue(risa.id).apiKey },
                    async { store.loadAll().connections.getValue(risa.id).apiKey },
                ).awaitAll()

            assertEquals(listOf("sk-shared", "sk-shared", "sk-shared"), keys)
            assertEquals(1, calls, "concurrent reads must share one mint")
        }

    @Test
    fun `a stored model outside the fixed list does not replace it`() =
        runTest {
            // A stale prefs entry, or a value typed into the manual field the panel used to
            // offer for this provider, could otherwise durably replace the one model the
            // gateway serves - with no picker to correct it from.
            val secrets =
                FakeSecretDataProvider(
                    listOf(
                        SecretEntryData(
                            id = "1",
                            website = risa.id,
                            username = "risa",
                            password = "",
                            notes = "{\"selectedModelId\":\"typo-model\"}",
                            tags = listOf(ProviderCredentialStore.TAG_AI_PROVIDER, risa.id),
                            createdAt = "2026-01-01",
                            updatedAt = "2026-01-01",
                        ),
                    ),
                )
            val store = ProviderCredentialStore(secrets, envWith())
            store.brokeredKeys = BrokeredKeySource { Result.success(BrokeredKey("sk-b", 3600)) }

            val connection = store.loadAll().connections.getValue(risa.id)

            assertEquals("coreweave-glm-5-2", connection.selectedModelId)
        }

    @Test
    fun `a stored model inside the fixed list is honoured`() =
        runTest {
            // The constraint must not become "ignore the stored value": if the provider ever
            // serves more than one model, the user's choice among them still wins.
            val store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), envWith())
            store.brokeredKeys = BrokeredKeySource { Result.success(BrokeredKey("sk-b", 3600)) }
            val onlyModel = ProviderRegistry.fixedModels.getValue(risa.id).first().id

            val connection = store.loadAll().connections.getValue(risa.id)

            assertEquals(onlyModel, connection.selectedModelId)
        }
}
