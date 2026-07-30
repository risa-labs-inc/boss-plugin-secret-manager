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
