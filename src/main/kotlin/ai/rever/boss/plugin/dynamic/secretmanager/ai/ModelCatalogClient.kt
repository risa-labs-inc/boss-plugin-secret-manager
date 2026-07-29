package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks each provider what models the supplied credential can actually reach.
 *
 * This is the whole point of the feature: model lists are never hardcoded, so they
 * cannot go stale. A provider is only ever described by what it just reported.
 *
 * Uses the JDK HTTP client rather than Ktor deliberately — the host excludes the
 * ktor server stack to keep plugin classloaders clean, and bundling a second Ktor
 * copy into a plugin invites the same loader-constraint trouble. The JDK client
 * needs no dependency at all.
 */
class ModelCatalogClient(
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetch [descriptor]'s model list using [apiKey].
     *
     * Returns a failure with a human-readable message rather than throwing, because
     * every caller renders the message in the settings panel. Providers with no
     * models endpoint (a custom endpoint) fail with an explanatory message.
     */
    suspend fun fetch(
        descriptor: ProviderDescriptor,
        apiKey: String,
    ): Result<List<AiModel>> {
        val primary = descriptor.modelsEndpoint
            ?: return Result.failure(
                IllegalStateException("${descriptor.displayName} has no model list endpoint — enter a model id manually."),
            )

        val first = request(descriptor, primary, apiKey)
        if (first.isSuccess) return first

        // xAI exposes a richer endpoint plus a minimal one; fall back rather than
        // reporting failure when only the richer route is unavailable.
        val fallback = descriptor.modelsEndpointFallback ?: return first
        return request(descriptor, fallback, apiKey)
    }

    private suspend fun request(
        descriptor: ProviderDescriptor,
        endpoint: String,
        apiKey: String,
    ): Result<List<AiModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url =
                    if (descriptor.credentialTransport == CredentialTransport.QUERY_KEY_PARAM) {
                        val separator = if (endpoint.contains('?')) "&" else "?"
                        "$endpoint${separator}key=${apiKey.trim()}"
                    } else {
                        endpoint
                    }

                val builder =
                    HttpRequest
                        .newBuilder(URI.create(url))
                        .GET()
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")

                when (descriptor.credentialTransport) {
                    CredentialTransport.BEARER_HEADER ->
                        builder.header("Authorization", "Bearer ${apiKey.trim()}")

                    CredentialTransport.X_API_KEY_HEADER -> {
                        builder.header("x-api-key", apiKey.trim())
                        builder.header("anthropic-version", ProviderRegistry.ANTHROPIC_VERSION)
                    }

                    CredentialTransport.QUERY_KEY_PARAM -> Unit // already in the URL
                }

                val response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())

                if (response.statusCode() !in 200..299) {
                    error(describeFailure(descriptor, response.statusCode()))
                }

                parse(descriptor, response.body())
            }
        }

    /**
     * Turn a status code into something a user can act on. The body is deliberately
     * not included: provider error bodies can echo the request, and this one carries
     * a credential.
     */
    private fun describeFailure(
        descriptor: ProviderDescriptor,
        status: Int,
    ): String =
        when (status) {
            401, 403 -> "${descriptor.displayName} rejected the API key (HTTP $status). Check the key and try again."
            404 -> "${descriptor.displayName} has no model list at that endpoint (HTTP 404)."
            429 -> "${descriptor.displayName} rate-limited the model list request (HTTP 429). Try again shortly."
            in 500..599 -> "${descriptor.displayName} returned a server error (HTTP $status). Try again shortly."
            else -> "${descriptor.displayName} model list failed (HTTP $status)."
        }

    private fun parse(
        descriptor: ProviderDescriptor,
        body: String,
    ): List<AiModel> {
        val root = json.parseToJsonElement(body)
        val entries = modelArray(root)

        return entries
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                when {
                    descriptor.wireFormat == WireFormat.GOOGLE_GENERATIVE -> googleModel(obj)
                    descriptor.id == ProviderRegistry.ANTHROPIC -> anthropicModel(obj)
                    descriptor.id == ProviderRegistry.MOONSHOT -> moonshotModel(obj)
                    descriptor.id == ProviderRegistry.TOGETHER -> togetherModel(obj)
                    descriptor.id == ProviderRegistry.XAI -> xaiModel(obj)
                    else -> openAiModel(obj)
                }
            }.distinctBy { it.id }
            .sortedBy { it.displayName.lowercase() }
    }

    /**
     * Pull the model array out of whatever envelope the provider used.
     *
     * Anthropic, OpenAI and Moonshot document `data`; Google documents `models`.
     * xAI's and Together's envelope shape is not stated in their published model-list
     * references, so all three forms are accepted rather than betting on one.
     */
    private fun modelArray(root: JsonElement): List<JsonElement> =
        when {
            root is JsonArray -> root
            root is JsonObject && root["data"] is JsonArray -> (root["data"] as JsonArray)
            root is JsonObject && root["models"] is JsonArray -> (root["models"] as JsonArray)
            else -> emptyList()
        }

    private fun anthropicModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        return AiModel(
            id = id,
            displayName = obj.str("display_name") ?: id,
            contextLength = obj.int("max_input_tokens"),
            maxOutputTokens = obj.int("max_tokens"),
            capabilities = anthropicCapabilities(obj["capabilities"] as? JsonObject),
        )
    }

    /**
     * Anthropic reports capabilities as a tree with `{"supported": bool}` leaves.
     * Only the top level is surfaced — enough for a picker badge, without flattening
     * nested sub-trees like `thinking.types.*` into noise.
     */
    private fun anthropicCapabilities(capabilities: JsonObject?): List<String> =
        capabilities
            ?.mapNotNull { (key, value) ->
                key.takeIf { (value as? JsonObject)?.bool("supported") == true }
            }?.sorted()
            .orEmpty()

    private fun openAiModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        return AiModel(id = id, displayName = id, ownedBy = obj.str("owned_by"))
    }

    private fun moonshotModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        val capabilities =
            buildList {
                if (obj.bool("supports_image_in") == true) add("image-in")
                if (obj.bool("supports_video_in") == true) add("video-in")
                if (obj.bool("supports_reasoning") == true) add("reasoning")
            }
        return AiModel(
            id = id,
            displayName = id,
            contextLength = obj.int("context_length"),
            capabilities = capabilities,
            ownedBy = obj.str("owned_by"),
        )
    }

    private fun togetherModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        // Together lists embedding, rerank and moderation models alongside chat
        // models; only conversational ones belong in a chat model picker.
        val type = obj.str("type")
        if (type != null && type !in TOGETHER_CHAT_TYPES) return null
        return AiModel(
            id = id,
            displayName = obj.str("display_name") ?: id,
            contextLength = obj.int("context_length"),
            ownedBy = obj.str("organization"),
        )
    }

    private fun xaiModel(obj: JsonObject): AiModel? {
        val id = obj.str("id") ?: return null
        val inputs = obj.strList("input_modalities")
        val outputs = obj.strList("output_modalities")
        val capabilities =
            buildList {
                if (inputs.any { it.equals("image", ignoreCase = true) }) add("image-in")
                if (outputs.any { it.equals("image", ignoreCase = true) }) add("image-out")
            }
        return AiModel(
            id = id,
            displayName = id,
            capabilities = capabilities,
            ownedBy = obj.str("owned_by"),
        )
    }

    private fun googleModel(obj: JsonObject): AiModel? {
        // Google returns resource names ("models/gemini-x"); requests take the bare id.
        val rawName = obj.str("name") ?: return null
        val id = rawName.removePrefix("models/")

        // Skip embedding and other non-chat models — the picker is for completions.
        val methods = obj.strList("supportedGenerationMethods")
        if (methods.isNotEmpty() && methods.none { it == "generateContent" }) return null

        val capabilities = buildList { if (obj.bool("thinking") == true) add("thinking") }
        return AiModel(
            id = id,
            displayName = obj.str("displayName") ?: id,
            contextLength = obj.int("inputTokenLimit"),
            maxOutputTokens = obj.int("outputTokenLimit"),
            capabilities = capabilities,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    companion object {
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        private val TOGETHER_CHAT_TYPES = setOf("chat", "language", "code")

        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}
