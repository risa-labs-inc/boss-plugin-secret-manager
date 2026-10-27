package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.SupabaseDataProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Authenticate using an AI-only, single-use ticket from the existing session-authenticated
 * RPC API. No host broker registration or access to the BOSS login token is needed.
 */
internal class BossAiCredentialSource(
    private val supabase: SupabaseDataProvider,
    private val legacy: BrokeredKeySource? = null,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val retryDelaysMs: List<Long> = TRANSIENT_RETRY_DELAYS_MS,
) : BrokeredKeySource {
    override val discoversBossAi = true
    override fun bossAiScope(): String = API_SCOPE
    override val supportsSharedProviders: Boolean get() = legacy?.supportsSharedProviders == true
    override fun canDiscoverSharedProviders(): Boolean = legacy?.canDiscoverSharedProviders() == true
    override fun permitsEndpoint(brokerId: String, endpoint: String): Boolean =
        legacy?.permitsEndpoint(brokerId, endpoint) == true
    override fun permitsEndpoints(brokerId: String, endpoints: List<String>): Boolean =
        legacy?.permitsEndpoints(brokerId, endpoints) == true

    override suspend fun fetch(brokerId: String): Result<BrokeredKey> {
        if (brokerId != BossAiDiscovery.BROKER_ID) return legacy?.fetch(brokerId)
            ?: Result.failure(IllegalStateException("This provider is unavailable."))
        return withContext(Dispatchers.IO) {
            try {
                // A fresh ticket per attempt: a ticket is single-use, and a dropped exchange may have spent it.
                retryTransient(retryDelaysMs) { exchange() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // RPC/HTTP/parser errors may contain ticket or token material: only the class name escapes.
                val reason = "Could not authorize BOSS AI (${failureLabel(error)}). Retry using Refresh."
                Result.failure(if (isTransient(error)) TransientBrokerFailure(reason) else IllegalStateException(reason))
            }
        }
    }

    /**
     * One ticket and exchange. Throws a transient failure for [retryTransient]; a refusal is a result.
     *
     * The ticket leg is retried only when the host hands back the transport's own [java.io.IOException].
     * Out of process it arrives as a plain exception carrying a message, which cannot be told apart
     * from a refusal, so there only the exchange POST is retried.
     */
    private suspend fun exchange(): Result<BrokeredKey> {
        val payload = supabase.rpc("boss_ai_create_exchange_ticket").getOrElse {
            if (isTransient(it)) throw it
            return Result.failure(IllegalStateException(
                "Could not authorize BOSS AI. Sign in and check that BOSS AI access is enabled.",
            ))
        }
        val ticket = Json.parseToJsonElement(payload).jsonObject["ticket"]?.jsonPrimitive?.content
        require(ticket != null && ticket.matches(Regex("[a-f0-9]{64}")))
        val request = HttpRequest.newBuilder(URI.create(EXCHANGE_URL))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"ticket\":\"$ticket\"}"))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 500) throw ServerStatusFailure(response.statusCode())
        if (response.statusCode() !in 200..299) return Result.failure(IllegalStateException(
            "BOSS AI authorization failed (HTTP ${response.statusCode()}). Retry using Refresh.",
        ))
        val body = Json.parseToJsonElement(response.body()).jsonObject
        val token = body["access_token"]?.jsonPrimitive?.content
        val expiry = body["expires_at"]?.jsonPrimitive?.content
        val refresh = body["refresh_after_seconds"]?.jsonPrimitive?.longOrNull
        require(!token.isNullOrBlank() && token.length <= 16384 && token.none(Char::isWhitespace))
        require(expiry != null && Instant.parse(expiry).isAfter(Instant.now()))
        require(refresh != null && refresh > 0)
        return Result.success(BrokeredKey(token, refresh, expiry))
    }

    companion object {
        // The plugin owns the provider bootstrap. Metadata cannot redirect this exchange.
        const val API_SCOPE = "https://api.risaboss.com/functions/v1/boss-ai/v1"
        const val EXCHANGE_URL = "https://api.risaboss.com/functions/v1/boss-ai/auth/exchange"
    }
}
