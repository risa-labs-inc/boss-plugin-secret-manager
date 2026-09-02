package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [OllamaModelInstaller] against captured `/api/pull` response shapes — the streamed
 * NDJSON envelope Ollama documents, not the OpenAI-compatible one [ModelCatalogClient] speaks.
 */
class OllamaModelInstallerTest {
    private fun installerReturning(
        status: Int,
        body: String,
    ) = OllamaModelInstaller(httpClient = FakeHttpClient(status, body))

    @Test
    fun `a stream ending in success is a success`() =
        runTest {
            val body =
                """
                {"status":"pulling manifest"}
                {"status":"downloading","completed":100,"total":1000}
                {"status":"success"}
                """.trimIndent()

            val result = installerReturning(200, body).pull("llama3.2:3b")

            assertTrue(result.isSuccess)
        }

    @Test
    fun `an error status anywhere in the stream fails the pull`() =
        runTest {
            val body =
                """
                {"status":"pulling manifest"}
                {"status":"error: model not found"}
                """.trimIndent()

            val result = installerReturning(200, body).pull("nonexistent:1b")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("nonexistent:1b") == true)
        }

    @Test
    fun `a non-2xx response fails without needing a parseable body`() =
        runTest {
            val result = installerReturning(404, "not found").pull("llama3.2:3b")

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("404") == true)
        }

    @Test
    fun `the request posts the tag as name to the native pull endpoint`() =
        runTest {
            var seenRequest: HttpRequest? = null
            val client =
                FakeHttpClient(200, """{"status":"success"}""") { request -> seenRequest = request }

            OllamaModelInstaller(httpClient = client).pull("qwen2.5:32b")

            assertEquals("http://localhost:11434/api/pull", seenRequest?.uri().toString())
        }

    /** Minimal HttpClient stub returning an InputStream body, as `/api/pull` streams one. */
    private class FakeHttpClient(
        private val status: Int,
        private val body: String,
        private val onRequest: (HttpRequest) -> Unit = {},
    ) : HttpClient() {
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
            onRequest(request)
            return FakeResponse(status, body, request) as HttpResponse<T>
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> {
            onRequest(request)
            return CompletableFuture.completedFuture(FakeResponse(status, body, request) as HttpResponse<T>)
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class FakeResponse(
        private val status: Int,
        private val body: String,
        private val request: HttpRequest,
    ) : HttpResponse<InputStream> {
        override fun statusCode() = status

        override fun request() = request

        override fun previousResponse() = java.util.Optional.empty<HttpResponse<InputStream>>()

        override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): InputStream = ByteArrayInputStream(body.toByteArray())

        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()

        override fun uri() = request.uri()

        override fun version() = HttpClient.Version.HTTP_1_1
    }
}
