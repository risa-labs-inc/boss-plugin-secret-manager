# BOSS Secret Manager

Encrypted credentials, Plugin Store API keys, and every AI provider setting in BOSS.

A right-hand sidebar panel over the host's `SecretDataProvider`, plus the `Settings > AI
Providers` section: this plugin owns **all** AI provider configuration for the application.
The host has none of its own, and `PluginContext.llmProvider` is relayed from the instance
registered here.

## What it does

- **Secret CRUD**: website, username, password, notes, tags, expiry, 2FA type and secret, and
  recovery codes. Paginated at 50 per page with server-side search plus a client filter,
  copy-to-clipboard, and a per-secret reveal toggle.
- **Sharing**: share a secret with individual users (searched through Supabase) or with whole
  RBAC roles, each at an access level, and unshare again.
- **Plugin Store API keys**: create, list and revoke them, with a `publish` scope checkbox. The
  key is shown once at creation and never again.
- **AI providers**: Anthropic, OpenAI, Google Gemini, xAI Grok, Moonshot (Kimi), Together AI,
  and any custom OpenAI-compatible endpoint.
- **Model lists are fetched live** from each provider's own models endpoint and cached for six
  hours. There is deliberately no bundled fallback list: a provider with no credential reports
  "not configured" rather than guessing from a hardcoded list that drifts out of date.

Credential precedence is environment, then stored, then none. Environment values resolve from
the process environment, system properties, macOS `launchctl`, and finally `~/.boss/env_vars`.
**A key supplied by the environment is never written back to disk.**

## MCP tools

| Tool | Purpose |
|---|---|
| `secrets_list` | List secrets as metadata only (id, website, username) |
| `secret_search` | Search secrets by query, metadata only |
| `secret_get` | Reveal password, notes and 2FA for one secret id |
| `secret_create` | Create a secret |
| `secret_delete` | Delete a secret |

**`secret_get` refuses any secret tagged `ai-provider`.** `secrets_list` hands out ids and
`secret_get` hands out the plaintext password, so without that gate a prompt-injected agent is
two tool calls away from every configured provider key. An agent that needs to *use* a provider
goes through `PluginContext.llmProvider` and never needs the raw value.

## Permissions

Manifest `requiredPermissions` is `["secret.read"]`, and every MCP tool carries the same gate.

Writes are intentionally gated on `secret.read` rather than granular `secrets.create` /
`secrets.delete`: those are not seeded in the RBAC catalog, so gating on them would silently
make writes admin-only. Server-side RLS scopes every RPC to `auth.uid()` regardless.

## Requirements

- BOSS >= 9.2.20, boss-plugin-api >= 1.0.20
- `secretDataProvider` is required. Without it only a no-provider stub panel registers and no
  MCP tools are contributed.
- Optional: `supabaseDataProvider` (user and role search for sharing),
  `pluginStoreApiKeyProvider`, `settingsProvider`, `splitViewOperations`, `cacheProvider`.
- Network egress to each provider's models endpoint.
- The AI providers section additionally needs **api 1.0.71**. That dependency is confined to
  one file and registered inside a `LinkageError` guard, so on an older host the AI section is
  simply absent and secret management still works.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-secret-manager-*.jar ~/.boss/plugins/
./gradlew test    # 96 host-independent cases, no live credential needed
```

Do not delete `compose-stability.conf`. It stops the Compose compiler emitting a `$stable` read
against `ai.rever.boss.plugin.logging`, which the host shadows at runtime. Without it the
plugin fails binary-compatibility validation and will not load at all - this shipped broken
twice, in 1.2.6 and 1.2.7.

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
