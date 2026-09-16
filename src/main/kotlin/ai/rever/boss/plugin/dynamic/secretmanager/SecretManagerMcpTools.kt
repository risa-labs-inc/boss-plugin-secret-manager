package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.SecretAccessProvider
import ai.rever.boss.plugin.api.UpdateSecretRequestData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent tools whose captured provider is narrowed by the host to each exact MCP tool. */
internal class SecretManagerMcpToolProvider(
    override val providerId: String,
    private val secrets: SecretAccessProvider,
) : McpToolProvider {
    override fun tools(): List<McpToolDefinition> =
        listOf(
            tool("secrets_list", "List metadata for secrets owned by or shared with this tool.", limitSchema) { args ->
                listSecrets(args)
            },
            // Compatibility names from the retired user-secret-list surface. They intentionally
            // retain distinct tool principals, so access must be granted to these exact aliases.
            tool("my_secrets_list", "List metadata for secrets owned by or shared with this tool.", limitSchema) { args ->
                listSecrets(args)
            },
            tool("secret_search", "Search secrets owned by or shared with this tool.", querySchema) { args ->
                val query = args.text("query") ?: return@tool result("Missing required argument: query", true)
                secrets.searchSecrets(query).fold(
                    onSuccess = { page ->
                        result(
                            page.data.joinToString("\n") {
                                "${it.id}\t${it.website}\t${it.username}\t[${it.accessLevel}]"
                            }.ifEmpty { "No matching accessible secrets." },
                        )
                    },
                    onFailure = ::failure,
                )
            },
            tool("secret_get", "Read one secret explicitly shared with or owned by this tool.", idSchema) { args ->
                getSecret(args)
            },
            tool("my_secret_get", "Read one secret explicitly shared with or owned by this tool.", idSchema) { args ->
                getSecret(args)
            },
            tool("secret_create", "Create a secret owned by this exact tool.", createSchema, false) { args ->
                val website = args.text("website") ?: return@tool result("Missing required argument: website", true)
                val username = args.text("username") ?: return@tool result("Missing required argument: username", true)
                val password = args.text("password") ?: return@tool result("Missing required argument: password", true)
                secrets.createSecret(
                    CreateSecretRequestData(website, username, password, notes = args.text("notes")),
                ).fold(
                    onSuccess = { result("Created tool-owned secret $it.") },
                    onFailure = ::failure,
                )
            },
            tool("secret_update", "Update a secret only when this exact tool owns it.", updateSchema, false) { args ->
                val id = args.text("id") ?: return@tool result("Missing required argument: id", true)
                val website = args.text("website") ?: return@tool result("Missing required argument: website", true)
                val username = args.text("username") ?: return@tool result("Missing required argument: username", true)
                val password = args.text("password") ?: return@tool result("Missing required argument: password", true)
                secrets.updateOwnedSecret(
                    UpdateSecretRequestData(id, website, username, password, notes = args.text("notes")),
                ).fold(
                    onSuccess = { result("Updated tool-owned secret $id.") },
                    onFailure = ::failure,
                )
            },
            tool("secret_delete", "Delete a secret only when this exact tool owns it.", idSchema, false) { args ->
                val id = args.text("id") ?: return@tool result("Missing required argument: id", true)
                secrets.deleteOwnedSecret(id).fold(
                    onSuccess = { result("Deleted tool-owned secret $id.") },
                    onFailure = ::failure,
                )
            },
        )

    private suspend fun listSecrets(args: McpToolArgs): McpToolResult {
        val limit = args.text("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        return secrets.listSecrets(limit).fold(
            onSuccess = { page ->
                result(
                    page.data.joinToString("\n") {
                        "${it.id}\t${it.website}\t${it.username}\t[${it.accessLevel}]"
                    }.ifEmpty { "No accessible secrets." },
                )
            },
            onFailure = ::failure,
        )
    }

    private suspend fun getSecret(args: McpToolArgs): McpToolResult {
        val id = args.text("id") ?: return result("Missing required argument: id", true)
        return secrets.getSecret(id).fold(
            onSuccess = { secret ->
                if (secret == null) {
                    result("Secret not found or not authorized.", true)
                } else {
                    result(
                        buildString {
                            appendLine("website: ${secret.website}")
                            appendLine("username: ${secret.username}")
                            appendLine("password: ${secret.password}")
                            secret.notes?.let { appendLine("notes: $it") }
                        }.trimEnd(),
                    )
                }
            },
            onFailure = ::failure,
        )
    }

    private fun tool(
        name: String,
        description: String,
        schema: String,
        readOnly: Boolean = true,
        handler: suspend (McpToolArgs) -> McpToolResult,
    ) = McpToolDefinition(
        name = name,
        description = description,
        inputSchema = schema,
        readOnly = readOnly,
        handler = McpToolHandler(handler),
    )

    private fun McpToolArgs.text(name: String): String? = string(name)?.takeIf { it.isNotBlank() }

    private fun result(text: String, error: Boolean = false) = McpToolResult(text, isError = error)

    private fun failure(error: Throwable) =
        result("Secret operation failed: ${error.message ?: error::class.simpleName}", true)

    private companion object {
        val limitSchema = schema(listOf("limit"), emptyList(), "integer")
        val querySchema = schema(listOf("query"), listOf("query"))
        val idSchema = schema(listOf("id"), listOf("id"))
        val createSchema = schema(listOf("website", "username", "password", "notes"), listOf("website", "username", "password"))
        val updateSchema = schema(listOf("id", "website", "username", "password", "notes"), listOf("id", "website", "username", "password"))

        fun schema(
            fields: List<String>,
            required: List<String>,
            type: String = "string",
        ) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { fields.forEach { put(it, buildJsonObject { put("type", type) }) } })
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }.toString()
    }
}
