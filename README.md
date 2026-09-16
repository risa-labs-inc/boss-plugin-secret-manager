# BOSS Secret Manager

Encrypted credentials, the secrets other people share with you, and Plugin Store publish keys.

A right-hand sidebar panel over the host's human-only `SecretDataProvider`. AI provider and model
configuration now belongs to the AI Gateway plugin. Secret Manager only stores credentials and
lets a human grant a plugin or MCP tool permission to use a specific secret.

It is also the *only* secrets plugin: the separate **My Secrets** (`user-secret-list`) panel
is retired, and its list is this panel's "Shared with me" section. See
[Two sections, not two panels](#two-sections-not-two-panels).

## What it does

- **Secret CRUD**: website, username, password, notes, tags, expiry, 2FA type and secret, and
  recovery codes. Paginated at 50 per page with server-side search plus a client filter,
  copy-to-clipboard, and a per-secret reveal toggle.
- **Shared with me**: a read-only second section listing what other people have shared with
  you, by user, role or organisation, with the access level and who shared it. No edit, delete
  or re-share control exists anywhere in that section.
- **Sharing**: share a secret with individual users (searched through Supabase) or with whole
  RBAC roles, each at an access level, and unshare again.
- **Execution access**: a secret owner can explicitly grant use to an installed plugin or an
  individual MCP tool, and revoke it immediately. A grant permits retrieval for use; it never
  permits the recipient to edit, delete, or re-share the secret. A plugin/tool creator-owner is
  shown as immutable so it cannot be mistaken for a revocable grant.
- **Plugin Store publish keys**: create, list and revoke them, with a `publish` scope checkbox. The
  key is shown once at creation and never again.
Secrets with no execution owner retain their existing browser and human-vault behavior. They are
not visible to any plugin, agent, or tool until a human explicitly shares them. Secrets created
through a plugin or tool's scoped API are visible only to that exact owner unless shared later.

## Two sections, not two panels

The panel has two segmented sections, and they partition the vault rather than overlap it:

| Section | Reads | Rows | Controls |
|---|---|---|---|
| **Secrets** | `getUserSecrets` | your own plus your organisation's | full CRUD, sharing, API keys, execution grants |
| **Shared with me** | `getUserSecretsWithSharingInfo` | only what was shared with you | none - read-only |

Those were two separate plugins, `secret-manager` and `user-secret-list`, and **both listed
your own secrets** - the confusion this merge removes. `user-secret-list` was also the panel the
first-run wizard installed by default while this one was not, so the typical install had the
read-only half and not the half that can add a key.

**The partition key is `accessLevel`, not `isOwner`.** `get_user_secrets_with_shared` assigns it
per UNION source: `owner` for your own, `org` for an organisation's, and the share's own level
(`read` / `write`) for an actual share. Source 4 returns `is_owner = (s.user_id = auth.uid())`,
so **a colleague's organisation secret arrives with `isOwner = false`** while nobody shared it
with anyone - splitting on `isOwner` files it under "Shared with me" and tells the user someone
shared it with them. An unrecognised level counts as a share, so a new source added server-side
surfaces in the read-only section rather than in the one offering Edit and Delete.

**A copied credential is wiped from the clipboard after 45 seconds**, the same policy the
managed list applies. The panel this section came from had no wipe at all, and one panel holding
two clipboard policies for the same class of data would be an oversight rather than a decision.
The wipe deliberately outlives the panel: cancelling it on close would leave the credential
there indefinitely.

**The section loads lazily and may scan several pages.** A page is 50 entries of everything you
can read, filtered down to the shares, so someone with 60 of their own secrets and two shared
ones gets a first page that filters to nothing. An empty page auto-continues (capped at 5 pages
/ 250 rows, then a "Keep looking" control takes over), because a section reporting "nothing
shared with you" while the server still has some is a lie the user cannot tell from the truth.
The offset advances by the **raw** row count, not the filtered one.

Past that first load, paging is scroll-driven and only on a list that actually scrolls: a short
list of shares in a large vault gets a footer button instead of an invisible scan. Auto-prefetch
without that test turned one tab switch into hundreds of sequential requests.

## MCP tools

| Tool | Purpose |
|---|---|
| `secrets_list` | List secrets as metadata only (id, website, username) |
| `secret_search` | Search secrets by query, metadata only |
| `secret_get` | Reveal password, notes and 2FA for one secret id |
| `secret_create` | Create a secret |
| `secret_update` | Update a secret created by this exact tool |
| `secret_delete` | Delete a secret created by this exact tool |
| `my_secrets_list` | Compatibility alias with its own exact-tool access scope |
| `my_secret_get` | Compatibility alias with its own exact-tool access scope |

Every tool call is bound by the host to its exact provider/tool identity. Listing and search are
metadata-only. `secret_get` is the explicit plaintext operation and succeeds only for secrets
created by that tool or granted to it by a human. Grants are use-only, so update and delete remain
owner-only.

## Permissions

Manifest `requiredPermissions` is `["secret.read"]`, and every MCP tool carries the same gate.

**`secret.read` is part of the baseline `user` role**, so this panel is available to every
authenticated user. It was admin-only until migration `20260809000000`, which is a correction
rather than a widening: the vault was always per-user server-side (every RPC is granted to
`authenticated` and self-scopes with `auth.uid()`, and all four RLS policies on `secrets` are
`auth.uid() = user_id`). The old gate was inherited from the pre-RBAC `requiresAdmin` flag, and
it also meant non-admin users could not manage their own vault. The permission is still
revocable, so a locked-down deployment removes it from `user`.

Writes are intentionally gated on `secret.read` rather than granular `secrets.create` /
`secrets.delete`: those are not seeded in the RBAC catalog, so gating on them would silently
make writes admin-only. Server-side RLS scopes every RPC to `auth.uid()` regardless.

Two surfaces inside the panel carry their own gate, because they reach past the signed-in user:

| Surface | Permission | Held by |
|---|---|---|
| Share with a role (the share dialog's Roles tab) | `secret.share.role` | admin, boss_admin |
| Plugin Store publish keys | `api_key.create` | admin, boss_admin |

The role-share gate exists because a role share reaches every holder of that role, and `user`
is a descendant of every role - so a role target is the one control here that can publish a
credential deployment-wide, with no undo. `share_secret` refuses it server-side; hiding the tab
only keeps a control that cannot work off the screen. Sharing with an individual user is
ungated, and sharing with an organisation already requires membership of it.

## Requirements

- BOSS >= 9.4.2, boss-plugin-api >= 1.0.94
- The panel is visible to every authenticated user only on a host carrying migration
  `20260809000000`, which grants `secret.read` to the baseline `user` role. On an older
  host only admins and `boss_admin` can open it, and nothing else changes.
- `secretDataProvider` is required for the human vault. `secretAccessProvider` scopes MCP tools,
  and `secretGrantManager` supplies the trusted human grant controls.
- Optional: `supabaseDataProvider` (user and role search for sharing),
  `pluginStoreApiKeyProvider`, `settingsProvider`, `splitViewOperations`, `cacheProvider`.

## Build

```bash
./gradlew buildPluginJar
cp build/libs/boss-plugin-secret-manager-*.jar ~/.boss/plugins/
./gradlew test    # 214 host-independent cases, no live credential needed
```

Do not delete `compose-stability.conf`. It stops the Compose compiler emitting a `$stable` read
against `ai.rever.boss.plugin.logging`, which the host shadows at runtime. Without it the
plugin fails binary-compatibility validation and will not load at all - this shipped broken
twice, in 1.2.6 and 1.2.7.

See [AGENTS.md](AGENTS.md) for architecture and conventions.

## License

Proprietary - Risa Labs Inc.
