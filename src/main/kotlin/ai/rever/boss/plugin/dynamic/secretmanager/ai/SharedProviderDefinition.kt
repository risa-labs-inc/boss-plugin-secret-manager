package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/** Validate persisted ids against this discovery, retaining already configured providers first. */
internal fun initialProviderId(
    preferred: String?,
    descriptors: List<ProviderDescriptor>,
    connections: Map<String, ProviderConnection>,
): String? = preferred?.takeIf { id -> descriptors.any { it.id == id } }
    ?: descriptors.firstOrNull {
        !SharedProviderDefinition.isShared(it.id) && connections[it.id]?.let { connection ->
            connection.isConfigured && (it.requiresApiKey || !connection.selectedModelId.isNullOrBlank())
        } == true
    }?.id
    ?: descriptors.firstOrNull { it.sharedDefault && connections[it.id]?.isConfigured == true }?.id

/** The panel and inference API resolve a shared model selection identically. */
internal fun effectiveSharedConnection(connection: ProviderConnection, catalog: CatalogState): ProviderConnection {
    if (!SharedProviderDefinition.isShared(connection.providerId)) return connection
    val models = (catalog as? CatalogState.Loaded)?.models.orEmpty()
    val preferred = connection.selectedModelId?.takeIf { it.isNotBlank() }
    val selected = if (preferred != null) models.firstOrNull { it.id == preferred }
        else models.firstOrNull { it.isDefault } ?: models.firstOrNull()
    return connection.copy(
        selectedModelId = selected?.id,
        maxTokens = selected?.maxOutputTokens?.let { minOf(it, connection.maxTokens) } ?: connection.maxTokens,
    )
}

/** A read-only provider delivered through the vault's existing sharing permissions. */
@Serializable
internal data class SharedProviderDefinition(
    val schema: String,
    val name: String,
    val brokerId: String,
    val baseUrl: String,
    val defaultForNewUsers: Boolean = false,
) {
    fun descriptor(secretId: String): ProviderDescriptor = ProviderDescriptor(
        id = "shared:$secretId",
        displayName = name,
        wireFormat = WireFormat.OPENAI_CHAT,
        credentialTransport = CredentialTransport.BEARER_HEADER,
        chatEndpoint = baseUrl.trimEnd('/') + "/chat/completions",
        modelsEndpoint = baseUrl.trimEnd('/') + "/models",
        envVarNames = emptyList(),
        consoleUrl = null,
        keyPlaceholder = "",
        brokerId = brokerId,
        sharedDefault = defaultForNewUsers,
    )

    companion object {
        const val TAG = "ai-provider-definition"
        private val json = Json { ignoreUnknownKeys = true }
        fun isShared(id: String): Boolean = id.startsWith("shared:")

        fun parse(notes: String?): SharedProviderDefinition? = runCatching {
            if (notes == null || notes.length > 8192) return null
            json.decodeFromString<SharedProviderDefinition>(notes).takeIf {
                it.schema == "boss-managed-provider-v1" && it.name.isNotBlank() && it.name.length <= 100 &&
                    it.brokerId.isNotBlank() && it.baseUrl.length <= 2048 && safeUrl(it.baseUrl)
            }
        }.getOrNull()

        private fun safeUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.normalize() == uri && !value.contains('%') && !value.contains('\\')
        }.getOrDefault(false)

        /** A shared note may name an endpoint, but cannot expand a host broker's trust. */
        fun withinScope(endpoint: String, scope: String?): Boolean {
            if (scope == null || !safeUrl(endpoint) || !safeUrl(scope)) return false
            return endpoint == scope.trimEnd('/') || endpoint.startsWith(scope.trimEnd('/') + "/")
        }
    }
}
