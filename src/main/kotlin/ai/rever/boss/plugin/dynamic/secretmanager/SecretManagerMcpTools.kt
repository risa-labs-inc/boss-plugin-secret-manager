package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SecretEntryWithSharingData
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
            description = "List your secrets and secrets shared with you (id, website, username, owner, access). " +
                "Returns the first $DEFAULT_MY_LIST_LIMIT by default; pass a larger `limit` (up to 500) for more.",
            inputSchema = MY_LIST_LIMIT_SCHEMA,
            handler = McpToolHandler { args ->
                // Whether the caller chose the size is what decides if truncation is a
                // surprise. An explicit `limit` is the caller's own cap and returns exactly
                // what it returned before; a defaulted one is this tool's cap, and a cap the
                // caller did not ask for has to announce itself.
                val requested = args.int("limit")
                val limit = (requested ?: DEFAULT_MY_LIST_LIMIT).coerceIn(1, 500)
                secrets.getUserSecretsWithSharingInfo(limit).fold(
                    onSuccess = { page ->
                        if (page.data.isEmpty()) McpToolResult("No secrets.")
                        else {
                            val rows = page.data.joinToString("\n") { s ->
                                "${s.id}\t${s.website}\t${s.username}\t[${accessLabel(s.accessLevel)}]"
                            }
                            // A silently capped list reads as "these are all my secrets",
                            // which is the one wrong answer this tool must never give. The
                            // count of what was omitted is deliberately not stated: there is
                            // no total on the page, and `hasMore` is derived host-side from
                            // `size >= limit`, so the only way to count is to fetch (and
                            // decrypt) every remaining entry - the cost this cap exists to
                            // avoid. That derivation also means the notice can appear when
                            // the vault holds exactly `limit` entries; erring toward "look
                            // again" is the safe direction for a list of secrets.
                            McpToolResult(
                                if (requested == null && page.hasMore) {
                                    rows + "\n\n(Showing the first ${page.data.size}. More secrets exist - " +
                                        "pass a larger `limit`, up to 500, to see them.)"
                                } else {
                                    rows
                                }
                            )
                        }
                    },
                    onFailure = { McpToolResult("Failed: ${it.message}", isError = true) },
                )
            },
        ),
        // Answers by walking pages (see [findSharedById]) rather than reading one 500-row page.
        // The old single call had two defects and `offset`, already on SecretDataProvider and
        // never used, fixes both: an id past row 500 was reported as non-existent - a confident
        // wrong answer rather than an error - and every lookup materialised 500 decrypted
        // passwords to read one. The walk exits at the first hit and refuses to claim absence
        // for a vault it did not finish reading.
        McpToolDefinition(
            name = "my_secret_get",
            description = "Reveal one of your secrets' full value (password, notes) by id. Sensitive.",
            inputSchema = idSchema("Secret id (from my_secrets_list)."),
            handler = McpToolHandler { args ->
                val id = args.string("id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: id", isError = true)
                // Four outcomes, not two. A network or auth failure collapsed into "No secret
                // with id X" tells the agent the secret does not exist, which is a different
                // fact and one it may act on - the reason this was a fold and not a getOrNull.
                // A walk that stopped at the bound makes exactly the same false claim, so it
                // gets its own answer too.
                val entry = when (val found = findSharedById(id)) {
                    is SharedLookup.Found -> found.entry
                    is SharedLookup.Failed ->
                        return@McpToolHandler McpToolResult("Failed: ${found.message}", isError = true)
                    is SharedLookup.Absent ->
                        return@McpToolHandler McpToolResult("No secret with id $id", isError = true)
                    is SharedLookup.Unsearched ->
                        return@McpToolHandler McpToolResult(
                            "Searched the first ${found.searched} secrets without finding id $id. " +
                                "More secrets exist beyond that point, so this is not a statement " +
                                "that the id does not exist.",
                            isError = true,
                        )
                }
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

    /**
     * `secret_get`'s lookup, still a single 500-row page. Left as it is on purpose: it collapses
     * "the call failed" and "no such id" into one null, which is a change to `secret_get`'s
     * contract rather than part of this fix. [findSharedById] is the shape to copy when it moves.
     */
    private suspend fun findById(id: String): SecretEntryData? =
        secrets.getUserSecrets(limit = 500).getOrNull()?.data?.firstOrNull { it.id == id }

    /**
     * What a bounded search can honestly conclude.
     *
     * [Absent] and [Unsearched] are deliberately separate: "it is not in your vault" and "I
     * stopped looking" are different facts, and folding the second into the first is the bug
     * this type exists to make unrepresentable.
     */
    private sealed interface SharedLookup {
        data class Found(val entry: SecretEntryWithSharingData) : SharedLookup

        /** The walk reached the end of the vault without a match: the id really is not there. */
        data object Absent : SharedLookup

        /** The walk stopped after [searched] entries with pages unread. Says nothing about existence. */
        data class Unsearched(val searched: Int) : SharedLookup

        data class Failed(val message: String?) : SharedLookup
    }

    /**
     * Finds one shared-info secret by id, paging with `offset` and stopping at the first hit.
     *
     * The page size is the real cost here: every row carries a *decrypted* password, so a page
     * is the plaintext this materialises per round trip. A hit in the first chunk therefore
     * costs one RPC and [ID_SCAN_CHUNK] plaintexts instead of 500.
     */
    private suspend fun findSharedById(id: String): SharedLookup {
        var offset = 0
        while (offset < ID_SCAN_BOUND) {
            // Clamped so the walk cannot overshoot the bound on a short page.
            val limit = minOf(ID_SCAN_CHUNK, ID_SCAN_BOUND - offset)
            val page = secrets.getUserSecretsWithSharingInfo(limit = limit, offset = offset).fold(
                onSuccess = { it },
                onFailure = { return SharedLookup.Failed(it.message) },
            )
            page.data.firstOrNull { it.id == id }?.let { return SharedLookup.Found(it) }
            // A short page still advances by what it returned; an empty one cannot advance at
            // all, so stop rather than spin - and if the host still claims more, stop without
            // claiming absence, because that is precisely the page never read.
            if (page.data.isEmpty()) {
                return if (page.hasMore) SharedLookup.Unsearched(offset) else SharedLookup.Absent
            }
            offset += page.data.size
            if (!page.hasMore) return SharedLookup.Absent
        }
        return SharedLookup.Unsearched(offset)
    }

    private fun idSchema(desc: String): String =
        """{"type":"object","properties":{"id":{"type":"string","description":"$desc"}},"required":["id"]}"""

    private companion object {
        const val LIMIT_SCHEMA =
            """{"type":"object","properties":{"limit":{"type":"integer","description":"Max secrets (default 100)."}}}"""

        /**
         * `my_secrets_list`'s default page, and the reason it is not [LIMIT_SCHEMA]'s 100.
         *
         * An MCP result is re-read on every subsequent request for the rest of the session,
         * so this tool's default is a *per-request* cost rather than a one-off. Measured
         * against a real vault: the tool returned ~4,080 tokens with no argument and ~244
         * with `limit: 5`, i.e. the size was row count alone - the row is four short columns
         * and there is nothing per-row left to trim. Nothing in the old schema signalled that
         * omitting `limit` was the expensive choice, so nothing ever passed one.
         *
         * 20 lands near the ~600-token target while still showing most vaults in full. An
         * explicit `limit` is untouched and still reaches 500.
         *
         * Deliberately a **separate** constant from [LIMIT_SCHEMA] rather than an edit to it:
         * `secrets_list` shares that schema and still defaults to 100, so changing it in
         * place would misdocument a tool this change does not touch.
         */
        const val DEFAULT_MY_LIST_LIMIT = 20
        const val MY_LIST_LIMIT_SCHEMA =
            """{"type":"object","properties":{"limit":{"type":"integer","description":"Max secrets to return (default 20, max 500). Omit for a short list; pass a larger value to see more."}}}"""

        /**
         * The page size and the total cap of `my_secret_get`'s walk.
         *
         * **50 per page**, because a page is decrypted plaintext, not just bytes: every row
         * carries a password, so the chunk size *is* the number of secrets a single lookup
         * materialises. 50 is [SecretDataProvider]'s own default page and sits well above the
         * vault [DEFAULT_MY_LIST_LIMIT] was measured against, so a typical vault is answered in
         * one round trip for a tenth of the old plaintext. Much smaller turns an ordinary miss
         * into dozens of sequential RPCs; much larger walks back toward decrypting everything to
         * read one thing.
         *
         * **1000 in total**, because the bound is a work cap and not a definition of
         * correctness - past it the tool says what it actually knows instead of guessing. So it
         * wants to be the largest number whose *worst* case is still tolerable: 1000 decrypted
         * rows across 20 sequential RPCs, and only for a genuine miss in a vault that large.
         * That is strictly more reach than the single `limit = 500` call had, so nothing that
         * used to be findable stops being findable, and 500 more ids that used to be reported
         * as non-existent are now returned.
         */
        const val ID_SCAN_CHUNK = 50
        const val ID_SCAN_BOUND = 1000
        const val QUERY_SCHEMA =
            """{"type":"object","properties":{"query":{"type":"string","description":"Search text."}},"required":["query"]}"""
        const val CREATE_SCHEMA =
            """{"type":"object","properties":{"website":{"type":"string"},"username":{"type":"string"},"password":{"type":"string"},"notes":{"type":"string"}},"required":["website","username","password"]}"""
    }
}
