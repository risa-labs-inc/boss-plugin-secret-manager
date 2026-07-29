package ai.rever.boss.plugin.dynamic.secretmanager.ai

/**
 * The AI providers BOSS knows about.
 *
 * Every endpoint here was taken from the provider's own API reference. Where a
 * secondary source disagreed with first-party docs (xAI and Kimi OAuth, in
 * particular) the first-party docs won and the feature was left out — see the note
 * on [ProviderDescriptor] and AGENTS.md.
 *
 * Ids are persisted (in secrets, and as `LlmConfig.providerId`), so renaming one is
 * a breaking change. They match the identifiers the previous host-side
 * implementation used, so credentials imported from it line up.
 */
object ProviderRegistry {
    const val ANTHROPIC: String = "ANTHROPIC"
    const val OPENAI: String = "OPENAI"
    const val GOOGLE: String = "GOOGLE"
    const val XAI: String = "XAI"
    const val MOONSHOT: String = "MOONSHOT"
    const val TOGETHER: String = "TOGETHER"
    const val CUSTOM: String = "CUSTOM"

    /** Header value required on every Anthropic API request. */
    const val ANTHROPIC_VERSION: String = "2023-06-01"

    private val anthropic =
        ProviderDescriptor(
            id = ANTHROPIC,
            displayName = "Anthropic",
            wireFormat = WireFormat.ANTHROPIC_MESSAGES,
            credentialTransport = CredentialTransport.X_API_KEY_HEADER,
            chatEndpoint = "https://api.anthropic.com/v1/messages",
            modelsEndpoint = "https://api.anthropic.com/v1/models",
            envVarNames = listOf("ANTHROPIC_API_KEY"),
            consoleUrl = "https://console.anthropic.com/settings/keys",
            keyPlaceholder = "sk-ant-...",
            pagingStyle = PagingStyle.ANTHROPIC_CURSOR,
        )

    private val openai =
        ProviderDescriptor(
            id = OPENAI,
            displayName = "OpenAI",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "https://api.openai.com/v1/chat/completions",
            modelsEndpoint = "https://api.openai.com/v1/models",
            envVarNames = listOf("OPENAI_API_KEY"),
            consoleUrl = "https://platform.openai.com/api-keys",
            keyPlaceholder = "sk-...",
        )

    private val google =
        ProviderDescriptor(
            id = GOOGLE,
            displayName = "Google Gemini",
            wireFormat = WireFormat.GOOGLE_GENERATIVE,
            credentialTransport = CredentialTransport.QUERY_KEY_PARAM,
            chatEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
            modelsEndpoint = "https://generativelanguage.googleapis.com/v1beta/models",
            envVarNames = listOf("GEMINI_API_KEY", "GOOGLE_API_KEY"),
            consoleUrl = "https://aistudio.google.com/apikey",
            keyPlaceholder = "AIza...",
            pagingStyle = PagingStyle.GOOGLE_PAGE_TOKEN,
        )

    private val xai =
        ProviderDescriptor(
            id = XAI,
            displayName = "xAI Grok",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "https://api.x.ai/v1/chat/completions",
            // /v1/language-models carries modalities, aliases and pricing;
            // /v1/models is the minimal list and is the fallback.
            modelsEndpoint = "https://api.x.ai/v1/language-models",
            modelsEndpointFallback = "https://api.x.ai/v1/models",
            envVarNames = listOf("XAI_API_KEY"),
            consoleUrl = "https://console.x.ai/",
            keyPlaceholder = "xai-...",
        )

    private val moonshot =
        ProviderDescriptor(
            id = MOONSHOT,
            displayName = "Moonshot (Kimi)",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "https://api.moonshot.ai/v1/chat/completions",
            modelsEndpoint = "https://api.moonshot.ai/v1/models",
            envVarNames = listOf("MOONSHOT_API_KEY", "KIMI_API_KEY"),
            consoleUrl = "https://platform.moonshot.ai/console/api-keys",
            keyPlaceholder = "sk-...",
        )

    private val together =
        ProviderDescriptor(
            id = TOGETHER,
            displayName = "Together AI",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "https://api.together.ai/v1/chat/completions",
            modelsEndpoint = "https://api.together.ai/v1/models",
            envVarNames = listOf("TOGETHER_API_KEY"),
            consoleUrl = "https://api.together.ai/settings/api-keys",
            keyPlaceholder = "tgp_v1_...",
        )

    /**
     * A user-supplied OpenAI-compatible endpoint (local llama.cpp, vLLM, a gateway).
     * It has no models endpoint of its own: the model id is typed in by hand, since
     * there is nothing authoritative to ask.
     */
    private val custom =
        ProviderDescriptor(
            id = CUSTOM,
            displayName = "Custom (OpenAI-compatible)",
            wireFormat = WireFormat.OPENAI_CHAT,
            credentialTransport = CredentialTransport.BEARER_HEADER,
            chatEndpoint = "",
            modelsEndpoint = null,
            envVarNames = listOf("CUSTOM_LLM_API_KEY"),
            consoleUrl = null,
            keyPlaceholder = "Your API key",
        )

    /**
     * All providers in display order: the ones serving open-weight models first, then
     * the closed-weight hosts.
     *
     * `custom` sits with the open group on purpose — it is how you point BOSS at a local
     * or self-hosted OpenAI-compatible runtime (Ollama, vLLM, llama.cpp), which is the
     * most open option available here.
     */
    val all: List<ProviderDescriptor> =
        listOf(together, moonshot, custom, xai, anthropic, openai, google)

    private val byId: Map<String, ProviderDescriptor> = all.associateBy { it.id }

    /** The provider selected when nothing has been chosen yet — first in display order. */
    val default: ProviderDescriptor = all.first()

    /** Look up a provider by its stable id, or null when unknown. */
    fun find(id: String?): ProviderDescriptor? = id?.let { byId[it] }

    /**
     * Look up a provider by id, falling back to [default]. Used when reading a persisted
     * selection that names a provider this build doesn't have.
     *
     * Derived from list order rather than naming a provider, so re-ordering [all] moves
     * the default with it instead of leaving a stale hardcoded one behind.
     */
    fun findOrDefault(id: String?): ProviderDescriptor = find(id) ?: default
}
