package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A brokered provider's credential is minted, short-lived and never stored, which
 * makes these invariants the security story rather than a convenience: a token that
 * reaches the secret store, or outlives the session it was minted for, is the failure
 * this shape exists to avoid.
 */
class BrokeredCredentialTest {
    private val risa = ProviderRegistry.find(ProviderRegistry.RISA_GLM)!!

    /** Hermetic: the fixture file is the only source of variables. See ProviderCredentialStoreTest. */
    private fun envWith(contents: String = ""): EnvResolver {
        val dir: File = Files.createTempDirectory("brokered-env").toFile()
        File(dir, "env_vars").writeText(contents)
        return EnvResolver(
            bossRootDir = dir,
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )
    }

    private class CountingSource(
        private val result: Result<BrokeredKey>,
    ) : BrokeredKeySource {
        var calls = 0
        val brokerIds = mutableListOf<String>()

        override suspend fun fetch(brokerId: String): Result<BrokeredKey> {
            calls += 1
            brokerIds.add(brokerId)
            return result
        }
    }

    private fun storeWith(source: BrokeredKeySource?): Pair<ProviderCredentialStore, FakeSecretDataProvider> {
        val secrets = FakeSecretDataProvider(emptyList())
        val store = ProviderCredentialStore(secrets, envWith())
        store.brokeredKeys = source
        return store to secrets
    }

    @Test
    fun `a brokered provider resolves its credential from the broker`() =
        runTest {
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 3600)))
            val (store, _) = storeWith(source)

            val connection = store.loadAll().connections.getValue(risa.id)

            assertEquals("sk-brokered", connection.apiKey)
            assertEquals(CredentialSource.BROKERED, connection.source)
            assertTrue(connection.isConfigured)
            assertEquals(listOf(ProviderRegistry.RISA_GLM_BROKER), source.brokerIds)
        }

    @Test
    fun `a brokered credential is never written to the secret store`() =
        runTest {
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 3600)))
            val (store, secrets) = storeWith(source)

            store.loadAll()

            // The whole reason BROKERED exists as a separate source: resolving one must
            // not take the write path that a user-entered key takes.
            assertEquals(emptyList(), secrets.created)
            assertEquals(emptyList(), secrets.updated)
        }

    @Test
    fun `a live credential is reused rather than re-minted on every read`() =
        runTest {
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 3600)))
            val (store, _) = storeWith(source)

            store.loadAll()
            store.loadAll()
            store.loadAll()

            assertEquals(1, source.calls, "the broker is a real resource; three reads must not mint three keys")
        }

    @Test
    fun `a credential whose reuse window has passed is re-minted`() =
        runTest {
            // refreshAfterSeconds 0 means "do not reuse", which is what a broker reports
            // when it hands back a credential at the end of its window.
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 0)))
            val (store, _) = storeWith(source)

            store.loadAll()
            store.loadAll()

            assertEquals(2, source.calls)
        }

    @Test
    fun `invalidate drops the brokered credential so it cannot outlive a sign-out`() =
        runTest {
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 3600)))
            val (store, _) = storeWith(source)

            store.loadAll()
            store.invalidate()
            store.loadAll()

            assertEquals(2, source.calls, "a credential minted for the previous session must not be served")
        }

    @Test
    fun `a broker that cannot mint leaves the provider unconfigured, not half-configured`() =
        runTest {
            val source = CountingSource(Result.failure(IllegalStateException("not entitled")))
            val (store, _) = storeWith(source)

            val connection = store.loadAll().connections.getValue(risa.id)

            // BROKERED with a blank key would read as usable to isConfigured and fail on
            // the first request instead of at the point the user can act on it.
            assertEquals(CredentialSource.NONE, connection.source)
            assertFalse(connection.isConfigured)
            assertEquals("", connection.apiKey)
        }

    @Test
    fun `a host with no broker leaves the provider unconfigured`() =
        runTest {
            val (store, _) = storeWith(null)

            val connection = store.loadAll().connections.getValue(risa.id)

            assertEquals(CredentialSource.NONE, connection.source)
            assertFalse(connection.isConfigured)
        }

    @Test
    fun `a failed mint is not cached, so retrying after signing in works`() =
        runTest {
            val secrets = FakeSecretDataProvider(emptyList())
            val store = ProviderCredentialStore(secrets, envWith())
            var succeed = false
            store.brokeredKeys =
                BrokeredKeySource {
                    if (succeed) {
                        Result.success(BrokeredKey("sk-later", refreshAfterSeconds = 3600))
                    } else {
                        Result.failure(IllegalStateException("not signed in"))
                    }
                }

            assertFalse(store.loadAll().connections.getValue(risa.id).isConfigured)
            succeed = true

            // No invalidate() in between: a cached failure would strand the user on the
            // error until something else happened to clear the cache.
            val connection = store.loadAll().connections.getValue(risa.id)
            assertEquals("sk-later", connection.apiKey)
        }

    @Test
    fun `a brokered provider comes with a model selected`() =
        runTest {
            val source = CountingSource(Result.success(BrokeredKey("sk-brokered", refreshAfterSeconds = 3600)))
            val (store, _) = storeWith(source)

            val connection = store.loadAll().connections.getValue(risa.id)

            // There is no models endpoint to populate a picker from, so without a
            // default here activeConfig() returns null forever and the provider is
            // configured but unusable.
            assertEquals("coreweave-glm-5-2", connection.selectedModelId)
        }
}
