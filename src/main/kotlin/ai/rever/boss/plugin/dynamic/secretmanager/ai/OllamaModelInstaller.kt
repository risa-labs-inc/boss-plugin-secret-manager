package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pulls a model into a local Ollama daemon by calling its own API — not by printing a
 * terminal command and asking the user to run it themselves. `/api/pull` is Ollama's native
 * endpoint, not the OpenAI-compatible one [WireFormats] speaks: pulling a model is a
 * management operation, not a completion request, and has no OpenAI equivalent.
 *
 * Same shape as [ModelCatalogClient]: the JDK `HttpClient` is a constructor parameter so a
 * test can hand this a fake one instead of reaching an actual daemon.
 */
class OllamaModelInstaller(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUrl: String = NATIVE_API_BASE,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Pull [tag], returning once the daemon reports the pull finished — successfully or not.
     *
     * The response is streamed newline-delimited JSON progress (`{"status":"downloading",
     * "completed":.., "total":..}`, ..., finally `{"status":"success"}`); this drains it
     * rather than surfacing live progress, which is more than this picker currently shows
     * and easy to add later without changing this method's contract. Only the *last* line
     * matters here: an `error` status anywhere in a normally-terminating stream still means
     * the pull did not land.
     */
    suspend fun pull(tag: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(URI("$baseUrl/api/pull"))
                        .timeout(PULL_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(buildJsonObject { put("name", tag) }.toString()))
                        .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    throw IllegalStateException("Ollama rejected the pull request (HTTP ${response.statusCode()}).")
                }

                var lastStatus: String? = null
                response.body().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val status =
                            runCatching { json.parseToJsonElement(line).jsonObject["status"]?.jsonPrimitive?.contentOrNull }
                                .getOrNull()
                        if (!status.isNullOrBlank()) lastStatus = status
                    }
                }

                if (lastStatus?.contains("error", ignoreCase = true) == true) {
                    throw IllegalStateException("Ollama could not pull $tag: $lastStatus")
                }
            }
        }

    companion object {
        /**
         * Ollama's own API, distinct from [ProviderRegistry.ollama]'s OpenAI-compatible
         * `chatEndpoint` — same host and port, different path prefix. Fixed rather than
         * derived from the descriptor for the same reason the descriptor's own endpoint is
         * fixed: this provider is the default local install, not a user-editable one.
         */
        const val NATIVE_API_BASE = "http://localhost:11434"

        /**
         * A model pull can be several gigabytes on a slow connection; the default 20 s used
         * for a models-list GET would abort a real pull in progress.
         */
        private val PULL_TIMEOUT: Duration = Duration.ofMinutes(30)

        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}
