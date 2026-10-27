package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.dynamic.secretmanager.ai.QueuedHttpClient.Companion.DROPPED
import ai.rever.boss.plugin.dynamic.secretmanager.ai.QueuedHttpClient.Companion.TIMED_OUT
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BOSS AI sign-in rides out a dropped connection instead of failing the model call.
 *
 * 2026-10-27: every Optimist pod failed the exchange POST in bursts on reused connections that
 * the proxy had closed, while fresh connections succeeded; the 15 s mint backoff then failed
 * Max's retries too, so one blip became "couldn't reach my model".
 */
class TransientRetryTest {
    private val noWait = listOf(0L, 0L)
    private val tokenResponse get() = """{"access_token":"ai-only-token","refresh_after_seconds":180,
        "expires_at":"${Instant.now().plusSeconds(300)}"}"""

    private class Rpc(private val failWith: Throwable? = null) : SupabaseDataProvider {
        var calls = 0
        override suspend fun select(table: String, columns: String, filters: List<QueryFilter>, range: QueryRange?) =
            Result.failure<String>(AssertionError("No database SELECT is needed"))
        override suspend fun rpc(function: String, parameters: String): Result<String> {
            calls++
            failWith?.let { return Result.failure(it) }
            // A distinct ticket per call, so a retry is seen asking for a fresh one.
            return Result.success("""{"ticket":"${calls.toString().padStart(64, 'a')}"}""")
        }
    }

    private fun source(rpc: Rpc, vararg responses: Pair<Int, String>) =
        QueuedHttpClient(responses.toList()).let { it to BossAiCredentialSource(rpc, httpClient = it, retryDelaysMs = noWait) }

    @Test fun `a dropped exchange is retried with a fresh ticket`() = runBlocking {
        val rpc = Rpc()
        val (http, source) = source(rpc, DROPPED to "", 200 to tokenResponse)
        val key = source.fetch(BossAiDiscovery.BROKER_ID).getOrThrow()
        assertEquals("ai-only-token", key.token)
        assertEquals(2, rpc.calls, "a ticket is single-use: the retry must not resend the first one")
        assertEquals(2, http.requests.size)
    }

    @Test fun `a 5xx exchange is retried`() = runBlocking {
        val (http, source) = source(Rpc(), 503 to "", 200 to tokenResponse)
        assertTrue(source.fetch(BossAiDiscovery.BROKER_ID).isSuccess)
        assertEquals(2, http.requests.size)
    }

    @Test fun `a drop that persists fails as transient and names its class`() = runBlocking {
        val (http, source) = source(Rpc(), DROPPED to "")
        val error = source.fetch(BossAiDiscovery.BROKER_ID).exceptionOrNull()
        assertIs<TransientBrokerFailure>(error)
        assertEquals("Could not authorize BOSS AI (IOException). Retry using Refresh.", error.message)
        assertEquals(3, http.requests.size)
    }

    @Test fun `a refusal is not retried and is not transient`() = runBlocking {
        val (http, source) = source(Rpc(), 401 to "")
        val error = source.fetch(BossAiDiscovery.BROKER_ID).exceptionOrNull()
        assertNotNull(error)
        assertFalse(error is TransientBrokerFailure)
        assertEquals(1, http.requests.size)
    }

    @Test fun `a timed-out exchange is not retried`() = runBlocking {
        val (http, source) = source(Rpc(), TIMED_OUT to "")
        assertFalse(source.fetch(BossAiDiscovery.BROKER_ID).exceptionOrNull() is TransientBrokerFailure)
        assertEquals(1, http.requests.size, "a timeout already spent its whole wait")
    }

    @Test fun `a ticket request dropped in transit is retried`() = runBlocking {
        val rpc = Rpc(failWith = java.io.IOException("reset"))
        val (http, source) = source(rpc, 200 to tokenResponse)
        assertIs<TransientBrokerFailure>(source.fetch(BossAiDiscovery.BROKER_ID).exceptionOrNull())
        assertEquals(3, rpc.calls)
        assertEquals(0, http.requests.size)
    }

    @Test fun `a ticket refused by the account is not retried`() = runBlocking {
        val rpc = Rpc(failWith = IllegalStateException("BOSS AI access is required"))
        val (_, source) = source(rpc)
        val error = source.fetch(BossAiDiscovery.BROKER_ID).exceptionOrNull()
        assertFalse(error is TransientBrokerFailure)
        assertEquals(1, rpc.calls)
    }

    @Test fun `provider discovery retries a dropped connection`() = runBlocking {
        val metadata = """{"schema":"boss-managed-provider-v1","name":"Included AI",
            "brokerId":"boss-ai","baseUrl":"${BossAiCredentialSource.API_SCOPE}"}"""
        val http = QueuedHttpClient(listOf(DROPPED to "", 502 to "", 200 to metadata))
        val result = BossAiDiscovery(http, retryDelaysMs = noWait).fetch(BossAiCredentialSource.API_SCOPE, "t")
        assertTrue(result.isSuccess)
        assertEquals(3, http.requests.size)
    }

    @Test fun `provider discovery that keeps dropping fails as transient`() = runBlocking {
        val http = QueuedHttpClient(listOf(DROPPED to ""))
        val result = BossAiDiscovery(http, retryDelaysMs = noWait).fetch(BossAiCredentialSource.API_SCOPE, "t")
        assertIs<TransientBrokerFailure>(result.exceptionOrNull())
        Unit
    }

    @Test fun `a transient mint failure is retried after the short backoff, a refusal after the long one`() = runBlocking {
        val dir: File = Files.createTempDirectory("transient-env").toFile().also { File(it, "env_vars").writeText("") }
        val env = EnvResolver(bossRootDir = dir, processEnv = { null }, systemProperty = { null }, useLaunchctl = false)
        var failure: Throwable = TransientBrokerFailure("Could not authorize BOSS AI (IOException).")
        val store = ProviderCredentialStore(
            FakeSecretDataProvider(emptyList()),
            env,
            mintRetryBackoffMs = 600_000,
            transientMintRetryBackoffMs = 20,
        )
        store.brokeredKeys = BrokeredKeySource { Result.failure(failure) }
        val broker = ProviderRegistry.RISA_GLM_BROKER

        store.loadAll()
        Thread.sleep(40)
        assertTrue(store.brokeredCredentialLapsed(broker), "a blip must not hold the provider unconfigured for long")

        failure = IllegalStateException("BOSS AI authorization failed (HTTP 401).")
        store.loadAll()
        Thread.sleep(40)
        assertFalse(store.brokeredCredentialLapsed(broker), "a refusal keeps the long backoff")
    }
}
