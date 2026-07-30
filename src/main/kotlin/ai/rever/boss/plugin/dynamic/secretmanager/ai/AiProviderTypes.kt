package ai.rever.boss.plugin.dynamic.secretmanager.ai

/**
 * Request/response shape an endpoint speaks.
 *
 * Deliberately a plugin-local enum rather than the api's `LlmApiFormat`, even though
 * the two mirror each other. Referencing the api enum here would put a new-api symbol
 * on a code path that runs whenever this panel loads, so a host shipping an older api
 * jar would fail to link the whole feature. Keeping it local confines every new-api
 * reference to [LlmProviderSettingsApiImpl], which is registered inside a
 * `LinkageError` guard — the same containment editor-tab uses.
 */
enum class WireFormat {
    /** Anthropic Messages API. */
    ANTHROPIC_MESSAGES,

    /** OpenAI-compatible Chat Completions. */
    OPENAI_CHAT,

    /** Google Gemini generative language API. */
    GOOGLE_GENERATIVE,
}

/**
 * How a provider pages its model list.
 *
 * A single unpaged GET silently truncates: Anthropic's `/v1/models` defaults to 20 items
 * and Google's `ListModels` to 50, both with a cursor. A quietly clipped list is the same
 * failure mode as the hardcoded lists this feature replaced, so the cursor is followed.
 */
enum class PagingStyle {
    /** One request. The provider documents no pagination for this endpoint. */
    NONE,

    /** `limit` + `after_id` query params; `has_more` / `last_id` in the body (Anthropic). */
    ANTHROPIC_CURSOR,

    /** `pageSize` + `pageToken` query params; `nextPageToken` in the body (Google). */
    GOOGLE_PAGE_TOKEN,
}

/**
 * How a provider expects its credential to travel on a request. The six supported
 * providers use three distinct shapes, so this cannot be folded into [WireFormat]
 * (Google speaks a different wire format *and* a different credential transport;
 * Anthropic shares neither with OpenAI-compatible providers).
 */
enum class CredentialTransport {
    /** `Authorization: Bearer <key>` — OpenAI, xAI, Moonshot, Together. */
    BEARER_HEADER,

    /** `x-api-key: <key>` plus an `anthropic-version` header — Anthropic. */
    X_API_KEY_HEADER,

    /** `?key=<key>` query parameter — Google generative language API. */
    QUERY_KEY_PARAM,
}

/**
 * Static description of one AI provider: where its endpoints are, how it wants the
 * credential, and where a user goes to obtain one.
 *
 * There is deliberately no OAuth/sign-in field. Of the providers listed here only
 * Google has a documented third-party OAuth flow, and it runs through Vertex AI
 * (different base URL, requires a GCP project and region) — that is tracked as
 * separate work. Anthropic prohibits third-party OAuth outright, and the xAI and
 * Kimi flows belong to their own coding CLIs rather than to a documented
 * third-party program. See AGENTS.md before adding one.
 */
data class ProviderDescriptor(
    /** Stable identifier, persisted in secrets and exposed as `LlmConfig.providerId`. */
    val id: String,
    /** Human-readable name for the UI. */
    val displayName: String,
    /** Wire format of [chatEndpoint], for plugins building requests. */
    val wireFormat: WireFormat,
    /** How the credential is attached to requests. */
    val credentialTransport: CredentialTransport,
    /**
     * Endpoint a caller POSTs completions to. For Google this is a prefix — the
     * selected model and `:generateContent` are appended per request, which
     * [chatEndpointFor] handles.
     */
    val chatEndpoint: String,
    /**
     * Endpoint listing the models this credential can reach, or null when the
     * provider has no such endpoint (a user-supplied custom endpoint).
     */
    val modelsEndpoint: String?,
    /**
     * Secondary models endpoint tried when [modelsEndpoint] fails — xAI exposes a
     * richer `/v1/language-models` alongside the minimal `/v1/models`.
     */
    val modelsEndpointFallback: String? = null,
    /** Environment variables consulted for a key, in priority order. */
    val envVarNames: List<String> = emptyList(),
    /** Where the user creates an API key, opened by the "Get API key" action. */
    val consoleUrl: String? = null,
    /** Shape hint shown in the empty key field, e.g. `sk-ant-...`. */
    val keyPlaceholder: String = "",
    /** How [modelsEndpoint] pages, so a long list isn't silently clipped. */
    val pagingStyle: PagingStyle = PagingStyle.NONE,
) {
    /**
     * Canonical name for this provider's key, used as the stored secret's name so an
     * entry reads as `TOGETHER_API_KEY` rather than a prose label.
     *
     * This is the provider's own environment variable, which means the name in the secret
     * store matches the variable someone would export to supply the same key — the two
     * paths are visibly the same credential.
     */
    val standardKeyName: String
        get() = envVarNames.firstOrNull() ?: "${id}_API_KEY"

    /**
     * Full completions URL for [modelId]. Only Google needs the model in the path;
     * every other provider takes it in the request body and ignores the argument.
     */
    fun chatEndpointFor(modelId: String): String =
        when (wireFormat) {
            WireFormat.GOOGLE_GENERATIVE -> "$chatEndpoint/$modelId:generateContent"
            else -> chatEndpoint
        }
}

/**
 * One model offered by a provider, as reported by that provider's models endpoint.
 * Every field beyond [id] is optional because coverage varies — Together reports
 * context length and pricing, OpenAI reports almost nothing.
 */
data class AiModel(
    /** Model id to send in a request, e.g. `claude-opus-5`. */
    val id: String,
    /** Display name from the provider, falling back to [id]. */
    val displayName: String,
    /** Maximum input tokens, when the provider reports it. */
    val contextLength: Int? = null,
    /** Maximum output tokens, when the provider reports it. */
    val maxOutputTokens: Int? = null,
    /** Free-form capability labels, e.g. `vision`, `reasoning`. */
    val capabilities: List<String> = emptyList(),
    /** Owning organisation, when reported. */
    val ownedBy: String? = null,
)

/** Where a provider's credential came from, which decides whether it is editable. */
enum class CredentialSource {
    /** Resolved from the environment. Read-only: never written back to storage. */
    ENVIRONMENT,

    /** Stored as a secret by this plugin. Editable. */
    STORED,

    /** No credential available. */
    NONE,
}

/**
 * A provider's resolved credential plus the generation settings chosen for it.
 * [apiKey] is blank exactly when [source] is [CredentialSource.NONE].
 */
data class ProviderConnection(
    val providerId: String,
    val apiKey: String,
    val source: CredentialSource,
    val selectedModelId: String? = null,
    val customEndpoint: String? = null,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** Label shown alongside the credential, e.g. an account or key name. */
    val label: String? = null,
) {
    /**
     * A local runtime (Ollama, vLLM, llama.cpp) needs no credential, so for [CUSTOM] an
     * endpoint alone counts. Requiring a key there forced users to invent a dummy one to
     * make `activeConfig()` resolve at all.
     */
    val isConfigured: Boolean
        get() =
            apiKey.isNotBlank() ||
                (providerId == ProviderRegistry.CUSTOM && !customEndpoint.isNullOrBlank())

    companion object {
        const val DEFAULT_TEMPERATURE: Float = 0.7f
        const val DEFAULT_MAX_TOKENS: Int = 2000
    }
}

/**
 * State of one provider's model list.
 *
 * There is no "bundled defaults" case on purpose: model lists are only ever what a
 * provider just told us. A provider with no credential reports
 * [NotConfigured] rather than a hardcoded guess, because a stale hardcoded list is
 * what this replaced.
 */
sealed interface CatalogState {
    /** No credential, so the provider cannot be asked. */
    data object NotConfigured : CatalogState

    /** A fetch is in flight and nothing usable is cached. */
    data object Loading : CatalogState

    /** Models as reported by the provider at [fetchedAtEpochMs]. */
    data class Loaded(
        val models: List<AiModel>,
        val fetchedAtEpochMs: Long,
        /** True when served from disk cache rather than a fetch in this session. */
        val fromCache: Boolean = false,
    ) : CatalogState

    /**
     * The fetch failed. [lastKnown] carries the previous good result when there is
     * one, so the picker keeps working while surfacing that it is not current.
     */
    data class Failed(
        val message: String,
        val lastKnown: Loaded? = null,
    ) : CatalogState
}
