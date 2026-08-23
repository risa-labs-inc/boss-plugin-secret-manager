package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.ProviderCredentialStore

/**
 * MCP tools contributed by the Secret Manager plugin.
 *
 * SECURITY: these tools expose secret values (passwords, 2FA secrets) to the
 * calling agent. They exist only while this plugin is active for a permitted
 * user. `secrets_list` returns metadata only (no passwords); `secret_get`
 * reveals a single secret's value on explicit request. Registered in
 * [SecretManagerDynamicPlugin.register]; removed automatically on disable/unload.
 */
internal class SecretManagerMcpToolProvider(
    override val providerId: String,
    private val secrets: SecretDataProvider,
    /**
     * Invalidated after any write, for the same reason the panel's CRUD paths do it: the
     * AI provider cache is keyed off these same secrets, so an agent deleting an
     * `ai-provider` entry would otherwise leave `activeConfig()` handing the revoked
     * credential to other plugins until restart.
     *
     * Deliberately **not** defaulted: a `= null` default is what let the sole call site go
     * unwired while still compiling, so both invalidate calls were dead code in the shipped
     * jar. One call site, no default.
     */
    private val aiProviderStore: ProviderCredentialStore,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "secrets_list",
            description = "List saved secrets as metadata only (id, website, username) — no passwords.",
            inputSchema = LIMIT_SCHEMA,
            handler = McpToolHandler { args ->
                val limit = (args.int("limit") ?: 100).coerceIn(1, 500)
                secrets.getUserSecrets(limit).fold(
                    onSuccess = { page ->
                        if (page.data.isEmpty()) McpToolResult("No secrets.")
                        else McpToolResult(page.data.joinToString("\n") { "${it.id}\t${it.website}\t${it.username}" })
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "secret_search",
            description = "Search secrets by query; returns metadata only (id, website, username).",
            inputSchema = QUERY_SCHEMA,
            handler = McpToolHandler { args ->
                val query = args.string("query")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: query", isError = true)
                secrets.searchSecrets(query).fold(
                    onSuccess = { page ->
                        if (page.data.isEmpty()) McpToolResult("No matching secrets.")
                        else McpToolResult(page.data.joinToString("\n") { "${it.id}\t${it.website}\t${it.username}" })
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "secret_get",
            description = "Reveal a single secret's full value (password, notes, 2FA) by id. Sensitive.",
            inputSchema = idSchema("Secret id (from secrets_list)."),
            handler = McpToolHandler { args ->
                val id = args.string("id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: id", isError = true)
                findById(id)?.let { s ->
                    aiProviderRefusal(id, s.tags)?.let { return@McpToolHandler it }
                    McpToolResult(
                        buildString {
                            appendLine("website: ${s.website}")
                            appendLine("username: ${s.username}")
                            appendLine("password: ${s.password}")
                            s.notes?.let { appendLine("notes: $it") }
                            if (s.tags.isNotEmpty()) appendLine("tags: ${s.tags.joinToString(", ")}")
                            s.metadata?.let { m ->
                                if (m.twofaEnabled) appendLine("2fa: ${m.twofaType ?: "enabled"}${m.twofaSecret?.let { " secret=$it" } ?: ""}")
                            }
                        }.trimEnd()
                    )
                } ?: McpToolResult("No secret with id $id", isError = true)
            },
        ),
        // my_secrets_list / my_secret_get were the retired `user-secret-list` plugin's two
        // tools, adopted here when its panel became this panel's "Shared with me" section.
        // Kept under their original names rather than folded into secrets_list: agents,
        // prompts and skills already call them, and they answer a question secrets_list
        // cannot - `getUserSecretsWithSharingInfo` is the only call that reports how a secret
        // reached the caller.
        McpToolDefinition(
            name = "my_secrets_list",
            description = "List your secrets and secrets shared with you (id, website, username, owner, access).",
            inputSchema = LIMIT_SCHEMA,
            handler = McpToolHandler { args ->
                val limit = (args.int("limit") ?: 100).coerceIn(1, 500)
                secrets.getUserSecretsWithSharingInfo(limit).fold(
                    onSuccess = { page ->
                        if (page.data.isEmpty()) McpToolResult("No secrets.")
                        else McpToolResult(page.data.joinToString("\n") { s ->
                            "${s.id}\t${s.website}\t${s.username}\t[${accessLabel(s.accessLevel)}]"
                        })
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
        // Known limit, shared with findById: this reads the first 500 accessible entries and
        // answers "no secret with id X" past that - a wrong answer rather than an error. The
        // with-sharing set is a strict superset of the managed one, so it is the likelier of the
        // two to overflow, and each call materialises 500 decrypted passwords to find one. A
        // by-id RPC is the fix; SecretDataProvider does not have one.
        McpToolDefinition(
            name = "my_secret_get",
            description = "Reveal one of your secrets' full value (password, notes) by id. Sensitive.",
            inputSchema = idSchema("Secret id (from my_secrets_list)."),
            handler = McpToolHandler { args ->
                val id = args.string("id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: id", isError = true)
                // fold, not getOrNull: a network or auth failure collapsed into "No secret with
                // id X" tells the agent the secret does not exist, which is a different fact and
                // one it may act on.
                val page = secrets.getUserSecretsWithSharingInfo(limit = 500).fold(
                    onSuccess = { it },
                    onFailure = { return@McpToolHandler McpToolResult("Failed: ${it.message}", isError = true) },
                )
                val entry = page.data.firstOrNull { it.id == id }
                    ?: return@McpToolHandler McpToolResult("No secret with id $id", isError = true)
                // The same refusal secret_get carries. This tool shipped without it for three
                // days and read exactly the keys the other one withholds: same vault, same
                // secret.read gate, so a gate on one tool and not its sibling is no gate.
                aiProviderRefusal(id, entry.tags)?.let { return@McpToolHandler it }
                McpToolResult(
                    buildString {
                        appendLine("website: ${entry.website}")
                        appendLine("username: ${entry.username}")
                        appendLine("password: ${entry.password}")
                        entry.notes?.let { appendLine("notes: $it") }
                        append("access: ${accessLabel(entry.accessLevel)}")
                    }
                )
            },
        ),
        McpToolDefinition(
            name = "secret_create",
            description = "Create a new secret (website, username, password, optional notes).",
            inputSchema = CREATE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val website = args.string("website")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: website", isError = true)
                val username = args.string("username")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: username", isError = true)
                val password = args.string("password")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: password", isError = true)
                secrets.createSecret(
                    CreateSecretRequestData(
                        website = website,
                        username = username,
                        password = password,
                        notes = args.string("notes"),
                        expirationDate = null,
                        tags = emptyList(),
                        twofaEnabled = false,
                        twofaType = null,
                        recoveryCodes = emptyList(),
                    )
                ).fold(
                    onSuccess = {
                        aiProviderStore.invalidate()
                        McpToolResult("Created secret for $website.")
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
        McpToolDefinition(
            name = "secret_delete",
            description = "Delete a secret by id.",
            inputSchema = idSchema("Secret id to delete."),
            readOnly = false,
            handler = McpToolHandler { args ->
                val id = args.string("id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: id", isError = true)
                secrets.deleteSecret(id).fold(
                    onSuccess = {
                        aiProviderStore.invalidate()
                        McpToolResult("Deleted secret $id.")
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
    ).onEach { it.requiredPermissions = listOf("secret.read") }

    // RBAC gate: secret.read across the board — the same gate as the panel UI
    // (the plugin's manifest permission; the RPCs themselves are auth.uid()-
    // scoped so users only ever touch their own secrets). Granular
    // secrets.create/secrets.delete strings are NOT seeded in the RBAC catalog,
    // so gating on them would silently make the write tools admin-only and
    // diverge from what the panel allows.

    /**
     * How a secret reached the caller, for an agent.
     *
     * On `accessLevel`, **not** `isOwner`, for exactly the reason the panel's sections split on
     * it: source 4 of `get_user_secrets_with_shared` returns
     * `is_owner = (s.user_id = auth.uid())`, so a colleague's organisation secret arrives with
     * `isOwner = false`. Labelling off that field told an agent `shared(org)` - that somebody
     * shared it - about a secret nobody shared with anyone. The UI stopped making that claim
     * when the sections landed; the tool is the surface a model actually reads, so it mattered
     * more here.
     */
    private fun accessLabel(accessLevel: String): String =
        if (SecretAccess.isShare(accessLevel)) "shared($accessLevel)" else accessLevel

    /**
     * The refusal every value-revealing tool here shares, or null when the secret is
     * ordinary. Non-null means "return this instead".
     *
     * AI provider keys are withheld deliberately: a `*_list` tool returns ids and a `*_get`
     * tool returns the plaintext password, so without the gate it is two model-directed tool
     * calls from a prompt-injected agent to every configured provider key. An agent that
     * needs to *use* a provider goes through `PluginContext.llmProvider` / `activeConfig()`
     * and never needs the raw value - unlike plugin code, which the operator chose to
     * install.
     *
     * One function rather than the check inlined per tool, because inlining it is how the
     * sibling plugin's `my_secret_get` came to read exactly what `secret_get` refused. Any
     * new tool that returns a password calls this.
     */
    private fun aiProviderRefusal(id: String, tags: List<String>): McpToolResult? =
        if (tags.contains(ProviderCredentialStore.TAG_AI_PROVIDER)) {
            McpToolResult(
                "Secret $id is an AI provider key and is not readable through this tool. " +
                    "Use the provider via the host's AI provider settings instead.",
                isError = true,
            )
        } else {
            null
        }

    private suspend fun findById(id: String): SecretEntryData? =
        secrets.getUserSecrets(limit = 500).getOrNull()?.data?.firstOrNull { it.id == id }

    private fun idSchema(desc: String): String =
        """{"type":"object","properties":{"id":{"type":"string","description":"$desc"}},"required":["id"]}"""

    private companion object {
        const val LIMIT_SCHEMA =
            """{"type":"object","properties":{"limit":{"type":"integer","description":"Max secrets (default 100)."}}}"""
        const val QUERY_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Search text."}},"required":["query"]}"""
        const val CREATE_SCHEMA =
            """{"type":"object","properties":{"website":{"type":"string"},"username":{"type":"string"},"password":{"type":"string"},"notes":{"type":"string"}},"required":["website","username","password"]}"""
    }
}
