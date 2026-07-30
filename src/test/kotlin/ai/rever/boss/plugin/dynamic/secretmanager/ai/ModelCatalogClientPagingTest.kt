package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins cursor-following and the xAI endpoint fallback.
 *
 * Neither was reachable from the single-response fake the parser tests use, so the paging
 * code — the whole reason a truncated list can't masquerade as a complete one — went
 * unexercised. A parser that stops after page one is indistinguishable from the stale
 * hardcoded lists this feature replaced: a short, wrong dropdown under a "live" label.
 */
class ModelCatalogClientPagingTest {
    private fun descriptor(id: String): ProviderDescriptor =
        ProviderRegistry.find(id) ?: error("unknown provider $id")

    // ==================== Anthropic cursor ====================

    @Test
    fun `follows has_more until the provider stops offering a cursor`() =
        runTest {
            val fake =
                QueuedHttpClient(
                    listOf(
                        200 to """{"data":[{"type":"model","id":"a"}],"has_more":true,"last_id":"a"}""",
                        200 to """{"data":[{"type":"model","id":"b"}],"has_more":true,"last_id":"b"}""",
                        200 to """{"data":[{"type":"model","id":"c"}],"has_more":false}""",
                    ),
                )

            val models =
                ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.ANTHROPIC), "k").getOrThrow()

            assertEquals(listOf("a", "b", "c"), models.map { it.id })
            assertEquals(3, fake.requests.size)
            // The cursor has to travel, or page two repeats page one forever.
            assertTrue(fake.requests[1].contains("after_id=a"), "page 2 did not carry the cursor")
            assertTrue(fake.requests[2].contains("after_id=b"), "page 3 did not carry the cursor")
        }

    @Test
    fun `has_more with no cursor stops rather than looping`() =
        runTest {
            // A missing last_id used to be indistinguishable from "done". It still stops —
            // it cannot page without a cursor — but it now warns instead of quietly
            // presenting a clipped list as complete.
            val fake =
                QueuedHttpClient(
                    listOf(200 to """{"data":[{"type":"model","id":"only"}],"has_more":true}"""),
                )

            val models =
                ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.ANTHROPIC), "k").getOrThrow()

            assertEquals(listOf("only"), models.map { it.id })
            assertEquals(1, fake.requests.size)
        }

    @Test
    fun `a list ending exactly on a page boundary is not treated as truncated`() =
        runTest {
            // Covers that a two-page list is followed to the end and returned whole.
            //
            // It deliberately does NOT claim to cover the related cursor-clearing fix: that
            // one only changes whether a "may be incomplete" warning is logged when a list
            // ends exactly on the last allowed page, and a log line isn't observable here.
            // Verified by reverting the fix and confirming no test failed — so it is
            // reviewed code, not tested code.
            val fake =
                QueuedHttpClient(
                    listOf(
                        200 to """{"data":[{"type":"model","id":"a"}],"has_more":true,"last_id":"a"}""",
                        200 to """{"data":[{"type":"model","id":"b"}],"has_more":false,"last_id":"b"}""",
                    ),
                )

            val models =
                ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.ANTHROPIC), "k").getOrThrow()

            assertEquals(listOf("a", "b"), models.map { it.id })
            assertEquals(2, fake.requests.size)
        }

    @Test
    fun `paging stops at the page cap instead of following a cursor forever`() =
        runTest {
            // A provider that always claims one more page must not hang the panel. The cap
            // is the backstop; without it this is an infinite request loop.
            val fake =
                QueuedHttpClient(
                    responses = emptyList(),
                    always = 200 to """{"data":[{"type":"model","id":"m"}],"has_more":true,"last_id":"m"}""",
                )

            val result = ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.ANTHROPIC), "k")

            assertTrue(result.isSuccess)
            assertEquals(20, fake.requests.size, "did not stop at MAX_PAGES")
        }

    // ==================== Google page token ====================

    @Test
    fun `google follows nextPageToken`() =
        runTest {
            val fake =
                QueuedHttpClient(
                    listOf(
                        200 to
                            """{"models":[{"name":"models/one","supportedGenerationMethods":["generateContent"]}],
                            "nextPageToken":"tok-2"}""",
                        200 to
                            """{"models":[{"name":"models/two","supportedGenerationMethods":["generateContent"]}]}""",
                    ),
                )

            val models =
                ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.GOOGLE), "k").getOrThrow()

            assertEquals(listOf("one", "two"), models.map { it.id })
            assertTrue(fake.requests[1].contains("pageToken=tok-2"), "page 2 did not carry the token")
        }

    // ==================== xAI fallback ====================

    @Test
    fun `xai falls back to the second endpoint when the first fails`() =
        runTest {
            // xAI's model-list endpoint isn't documented; the registry names a primary and a
            // fallback precisely because we're guessing, so the fallback must actually fire.
            val xai = descriptor(ProviderRegistry.XAI)
            val fake =
                QueuedHttpClient(
                    listOf(
                        404 to """{"error":"not found"}""",
                        200 to """{"data":[{"id":"grok-5","owned_by":"xai"}]}""",
                    ),
                )

            val models = ModelCatalogClient(fake).fetch(xai, "k").getOrThrow()

            assertEquals(listOf("grok-5"), models.map { it.id })
            assertEquals(2, fake.requests.size)
            assertTrue(
                fake.requests[1].startsWith(xai.modelsEndpointFallback!!),
                "second attempt did not use the fallback endpoint: ${fake.requests[1]}",
            )
        }

    @Test
    fun `xai falls back when the first endpoint answers with an unrecognised shape`() =
        runTest {
            // A 200 that parses to nothing is the same failure as a 404 — a wrong guess
            // about the endpoint — so it must reach the fallback too, not report success.
            val fake =
                QueuedHttpClient(
                    listOf(
                        200 to """{"something_else":true}""",
                        200 to """{"data":[{"id":"grok-5","owned_by":"xai"}]}""",
                    ),
                )

            val models =
                ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.XAI), "k").getOrThrow()

            assertEquals(listOf("grok-5"), models.map { it.id })
            assertEquals(2, fake.requests.size)
        }

    @Test
    fun `a provider with no fallback reports the original failure`() =
        runTest {
            val fake = QueuedHttpClient(listOf(500 to """{"error":"boom"}"""))

            val result = ModelCatalogClient(fake).fetch(descriptor(ProviderRegistry.OPENAI), "k")

            assertTrue(result.isFailure)
            assertEquals(1, fake.requests.size, "retried an endpoint that has no fallback")
            assertTrue(result.exceptionOrNull()?.message?.contains("500") == true)
        }

    /**
     * Serves [responses] in order, then [always] (or the last response) once exhausted, and
     * records every request URI so cursor propagation and endpoint choice are assertable.
     */
    private class QueuedHttpClient(
        private val responses: List<Pair<Int, String>>,
        private val always: Pair<Int, String>? = null,
    ) : HttpClient() {
        val requests = mutableListOf<String>()

        private fun next(request: HttpRequest): Pair<Int, String> {
            val index = requests.size
            requests += request.uri().toString()
            return responses.getOrNull(index)
                ?: always
                ?: responses.lastOrNull()
                ?: (200 to "{}")
        }

        override fun cookieHandler() = java.util.Optional.empty<java.net.CookieHandler>()

        override fun connectTimeout() = java.util.Optional.empty<java.time.Duration>()

        override fun followRedirects() = Redirect.NEVER

        override fun proxy() = java.util.Optional.empty<java.net.ProxySelector>()

        override fun sslContext(): javax.net.ssl.SSLContext = javax.net.ssl.SSLContext.getDefault()

        override fun sslParameters(): javax.net.ssl.SSLParameters = javax.net.ssl.SSLParameters()

        override fun authenticator() = java.util.Optional.empty<java.net.Authenticator>()

        override fun version() = Version.HTTP_1_1

        override fun executor() = java.util.Optional.empty<java.util.concurrent.Executor>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            val (status, body) = next(request)
            return QueuedResponse(status, body, request) as HttpResponse<T>
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            val (status, body) = next(request)
            return CompletableFuture.completedFuture(QueuedResponse(status, body, request) as HttpResponse<T>)
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class QueuedResponse(
        private val status: Int,
        private val body: String,
        private val request: HttpRequest,
    ) : HttpResponse<String> {
        override fun statusCode() = status

        override fun request() = request

        override fun previousResponse() = java.util.Optional.empty<HttpResponse<String>>()

        override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body() = body

        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()

        override fun uri() = request.uri()

        override fun version() = HttpClient.Version.HTTP_1_1
    }
}
