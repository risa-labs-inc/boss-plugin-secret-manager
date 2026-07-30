package ai.rever.boss.plugin.dynamic.secretmanager.ai

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * An [HttpClient] that serves canned responses in order and records every request URI.
 *
 * Shared by the paging and catalog tests because a *queue* is what makes multi-request
 * behaviour reachable at all — cursor-following, the page cap, the primary-then-fallback
 * path, and any test that needs one genuinely successful fetch to exercise a write path.
 * Once [responses] is exhausted it serves [always], or repeats the last response.
 */
internal class QueuedHttpClient(
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
