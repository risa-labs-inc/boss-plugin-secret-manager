# AGENTS.md

## Project Overview

**Secret Manager (Dynamic)** (`ai.rever.boss.plugin.dynamic.secretmanager`) is a dynamic plugin for the BOSS desktop application.

Manage encrypted credentials and secrets, including Plugin Store API keys

- **Plugin ID**: `ai.rever.boss.plugin.dynamic.secretmanager`
- **Main Class**: `ai.rever.boss.plugin.dynamic.secretmanager.SecretManagerDynamicPlugin`
- **API Version**: 1.0.20

## Essential Commands

```bash
./gradlew buildPluginJar    # Build plugin JAR (output: build/libs/)
./gradlew build              # Full build
./gradlew processResources   # Process resources (syncs version)
```

## Workflow Rules

- Do NOT run the BOSS application to test. The user will test manually.
- After building, copy JAR to `~/.boss/plugins/` for local testing.

## Architecture

### Plugin Structure
```
src/main/kotlin/   → Plugin source code (package: ai.rever.boss.plugin.dynamic.*)
src/main/resources/META-INF/boss-plugin/plugin.json → Plugin manifest
build.gradle.kts   → Build config + version (single source of truth)
```

### Key Patterns
- Entry point: `DynamicPlugin` interface with `register(context)` and `dispose()`
- UI: `PanelComponentWithUI` with `@Composable Content()`
- State: ViewModel pattern with `StateFlow`
- Providers from `PluginContext`: `workspaceDataProvider`, `splitViewOperations`, `contextMenuProvider`, `activeTabsProvider`
- Null-safe provider access: providers may be null, UI must handle gracefully

### Dependencies
- **boss-plugin-api**: compileOnly (provided by host app at runtime)
- **Compose Desktop**: UI framework
- **Decompose**: Navigation and component lifecycle
- **Coroutines**: Async operations

## Version Management

**`build.gradle.kts` is the single source of truth for version.**

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` - only change it in `build.gradle.kts`.

## AI Providers (`ai/` package)

This plugin owns **all** AI provider configuration. The host has none: its
`Settings → AI Providers` section renders `LlmProviderSettingsPanel` through
`LlmProviderSettingsAPI`, and `PluginContext.llmProvider` is relayed from the same
registered instance. Provider registry, credentials, environment-variable resolution
and the model catalogue all live here.

### Legacy plaintext key import

`LegacySettingsImport` migrates keys out of the plaintext files that predate this plugin. It
iterates a list of `LegacySource`s (file + parser), so adding another historical location is a
one-entry change rather than a second code path:

- `~/.boss/llm_settings.json` — the host's old file, nested `apiKeys` map.
- `~/.boss/config/llm-settings.json` — `llmrpa`'s own file, one flat field per provider. That
  plugin rewrote it on every keystroke of its own API-key field; it now reads
  `PluginContext.llmProvider` instead, so without this source migrating it would have silently
  stranded whatever the user had typed there.

The llmrpa source resolves against the real `~/.boss`, **not** `bossRootDir`: llmrpa hardcoded
`File(user.home, ".boss/config")` and never honoured `boss.dev.mode`, so under a dev host its
keys are still in `.boss` and following the root would miss them.

Three invariants exist to avoid losing or leaking a key, each retained per-file:

- a key already supplied by an environment variable is **not** imported and its file is **not**
  retired — storing it would recreate the plaintext-persistence leak this feature removed, and
  renaming would strand a real key behind a file the user was never told about;
- a file is retired only when every key it offered was stored, so a failed write can be retried;
- retiring is a rename to `.migrated`, never a delete. The host kernel's self-healing resolves
  its repair key before any plugin loads and cannot reach this store, so
  `llm_settings.json.migrated` is its only remaining legacy source.

Model ids are dropped (both files name retired models, and the picker's whole purpose is to be
live), and so is llmrpa's `customEndpoint` — writing it needs `saveSettings`, which would blank
any `selectedModelId` already chosen for `CUSTOM`. The banner tells the user to re-enter it.

`LegacySettingsImportTest` (8 cases) mutation-verified twice: ignoring env shadowing fails
*a key shadowed by an environment variable is neither imported nor retired*, and retiring
regardless of write failures fails *a file whose writes all fail is kept so the import can be
retried*. `FakeSecretDataProvider` was lifted out of `ProviderCredentialStoreTest` so both
suites assert against one fake rather than two that can drift.

**Model lists are always fetched live** from each provider's own models endpoint
(`ModelCatalogClient`), cached with a timestamp and a 6-hour TTL. There is
deliberately no bundled fallback list: the host implementation this replaced shipped
hardcoded models that drifted years out of date, and a provider with no credential
now reports "not configured" instead of guessing.

### `ai.rever.boss.plugin.logging` must never trigger a runtime `$stable` read

The host bundles `plugin-logging-desktop.jar`, whose `ComponentLogger` has **no** Compose
`$stable` field, and it shadows the boss-plugin-api copy (which *does* have one) parent-first at
runtime. So a `ComponentLogger`-typed **property** makes the Compose compiler emit a `$stable`
field on that class whose initialiser reads `ComponentLogger.$stable` - it resolves against the
api jar at build time and is missing at load time. `BinaryCompatibilityValidator` then rejects
the plugin outright and the host disables it as binary incompatible:

```
SecretManagerDynamicPlugin -> ai.rever.boss.plugin.logging.ComponentLogger.$stable: field not found
```

**This shipped broken in 1.2.6 and 1.2.7** - the plugin could not load on any host, and the
store served it for hours.

The primary defence is **`compose-stability.conf`**, which lists
`ai.rever.boss.plugin.logging.**` and is wired in via `composeCompiler.stabilityConfigurationFiles`.
That resolves the stability at compile time, so no runtime read is emitted *anywhere* in the
module - including from the eight other classes that hold a logger as an ordinary instance
property. Those are safe today only because they infer as unstable outright and the compiler bakes
in a constant; a refactor leaving one all-`val` with otherwise-stable types would have resurrected
this. Do not remove that file to "clean up".

`SecretManagerDynamicPlugin` additionally keeps its logger on the `companion object`. That is
belt-and-braces for the one class whose failure takes the entire plugin down, not a rule to apply
everywhere - and note a companion `val` is still a *property*, just of the companion class, so the
reason it helps is that it is no longer a property of the class whose stability is being computed.
Moving a logger to a nested class or top-level object is **not** equivalent reasoning.

No unit test can catch this: on the test classpath the api jar *is* the ComponentLogger, so
everything links. `buildPluginJar` therefore runs `javap` over the packaged classes and fails
the build if any of them references a `$stable` field on `ai.rever.boss.plugin.logging`.
Mutation-verified - putting the logger back as an instance property fails the build.

### The version must come from Gradle, not from a second copy of plugin.json

`version` reads the bundled `plugin.json` that `processResources` stamps. Two traps here, both
hit once already:

- `javaClass.package?.implementationVersion` does **not** work, even though `buildPluginJar`
  writes `Implementation-Version` into the manifest. `getImplementationVersion()` returns null
  under a plain `URLClassLoader`, which is what the host's `PluginClassLoader` extends, so the
  plugin reported `"unknown"` to the host and store for a whole release.
- The `pluginId` filter alone is not enough either: a bundled or stale copy of *our own*
  manifest on the host classpath matches it, and `getResources` is parent-first. `PluginVersionSource`
  therefore prefers the jar the plugin class itself came from, keeping the `pluginId` match as
  the fallback so IDE and test runs still resolve. The selection rules live in that object,
  away from the classloader, precisely so they can be tested - they have been wrong twice.
- `getResourceAsStream` is the wrong lookup: every BOSS plugin ships `plugin.json` at the same
  path and resource lookup is parent-first, so a neighbour's manifest could win and the plugin
  would report *someone else's* version. Enumerate with `getResources` and pick the document
  whose `pluginId` is ours.
- The jar tasks must **not** also `from("src/main/resources")`. `sourceSets.main.output` already
  carries the stamped copy; adding the raw directory put an *unstamped* `plugin.json` in the jar
  too (the committed one says `1.0.9`), and with `duplicatesStrategy = EXCLUDE` the winner was
  decided by `from` order alone.

`PluginVersionTest` asserts against `boss.plugin.expectedVersion`, injected by the Test task from
the Gradle version. Comparing the reported version to the bundled `plugin.json` would be circular -
both read the same file, so any value in it passes, and `1.0.9` is valid semver.

### The panel is for everyone; two controls inside it are not

`secret.read` reached the baseline `user` role in migration `20260809000000`, so every
authenticated user gets this panel. Read that migration's header before assuming it widened
anything: the vault has always been per-user server-side (grants to `authenticated`,
`auth.uid()` self-scoping, RLS on `auth.uid() = user_id`), and `secret.read` was a
client-side visibility gate translated forward verbatim from the pre-RBAC `requiresAdmin`
flag. The practical consequence of leaving it admin-only was not that secrets were safer, it
was that `DynamicPluginManager` skips `register()` for an inaccessible plugin - so no
non-admin got `Settings > AI Providers`, and `PluginContext.llmProvider` was null in every
other plugin. All AI in BOSS was admin-only by accident.

What did NOT become everyone's is sharing with a **role**. `share_secret` gated role targets
on `can_manage_secret` alone, i.e. any owner could share with any global role - including
`user`, which is a descendant of every role and therefore means "everyone" (see
`20260802010000`'s own header). That was survivable only while non-admins could not open this
panel. It now requires `secret.share.role`, enforced in the RPC and mirrored here by
`SecretManagerState.canShareWithRoles`, which hides the share dialog's Roles tab.

Three things about that flag:

- **It is collected, not read once.** The panel is constructed as soon as the plugin
  registers, which can precede the permission claim landing, so a one-shot read in
  `initialize()` leaves an admin looking at a hidden tab until they reopen the panel.
  `observeRoleSharePermission` combines `userPermissions` and `isAdmin` - both, because
  `hasPermission` answers true for an admin regardless of the permission set, so an admin
  whose claim arrives without a permissions change still needs a recompute.
- **It fails closed on a null `authDataProvider`**, and `selectedTab` is clamped back to
  Users if the permission disappears while the dialog is open (remembered state does not
  re-derive itself).
- **It is not the enforcement.** The RPC is. `RoleShareGateTest` is mutation-verified:
  hardcoding the flag true fails three cases (no permission, late arrival, revocation).

Plugin Store API keys were already gated, on `api_key.create` via
`PluginStoreApiKeyProvider.canManageApiKeys()`. Nothing changed there.

`context.authDataProvider` is read on the always-taken registration path, so it has to
predate the manifest floor: `getAuthDataProvider` is present in the released
`boss-plugin-api-1.0.73.jar`, which is exactly `minApiVersion`. Verified with `javap`, not
assumed.

### Provider keys are withheld from `secret_get`

Storing provider keys as ordinary secrets buys encryption, RLS and an audit trail for free.
The cost would have been agent readability: `secrets_list` hands out ids and `secret_get`
hands out the plaintext `password`, so ungated it is **two model-directed tool calls from a
prompt-injected agent to every configured provider key**.

`secret_get` therefore refuses entries tagged `TAG_AI_PROVIDER`. The asymmetry that decided
it: `PluginContext.llmProvider` also exposes `LlmConfig.apiKey`, but that is plugin code the
operator chose to install, whereas the MCP path is directed by a model. An agent that needs to
*use* a provider goes through `llmProvider`/`activeConfig()` and never needs the raw value.

Deleting the tag check in the `secret_get` handler restores the old behaviour; two tests cover
both halves (provider key withheld, ordinary secret still returned).

### Do not add OAuth without re-checking the docs

Sign-in is intentionally absent. As of July 2026:

- **Anthropic** prohibits third-party OAuth outright (policy 2026-02-20, billing
  enforcement 2026-04-04). Subscription tokens are Claude Code / claude.ai only.
  Wiring their OAuth client here would breach their terms.
- **OpenAI**'s "Sign in with ChatGPT" ships only inside Codex tooling; there is no
  third-party program.
- **xAI** and **Moonshot (Kimi)** publish Bearer-API-key auth only in their REST
  references. Their OAuth/device-code flows belong to their own coding CLIs - the
  same category as Anthropic's, and not a documented third-party surface.
- **Google** does have a documented installed-app OAuth flow, but it runs through
  **Vertex AI** - a different base URL needing a GCP project, region and ADC, not the
  `generativelanguage` key path used here. That is tracked as separate work.
- **Together** has no OAuth.

Providers instead get an assisted flow: a "Get API key" button opening
`ProviderDescriptor.consoleUrl` in a BOSS tab.

### Linkage containment

The guard covers the `Llm*` symbols only, so anything else this plugin touches must
genuinely predate the declared `apiVersion` floor of 1.0.20. Verified against the api tags:
`PluginContext.windowId`, `PluginContext.settingsProvider`, `SettingsProvider` and
`openSettings` all landed in **1.0.16** and are present in the `v1.0.20` tag. That matters
because they are read on the always-taken registration path (`registerPanel`), outside any
guard - a member newer than the floor would throw `NoSuchMethodError` there and take the
*whole* plugin down, not just the AI section. `cacheProvider` is inside the guard and so is
unconstrained.

The audit also has to cover the UI kit, not just `PluginContext`: this feature added
first-time uses of `BossSection`, `BossCard`, `BossTextField`, `BossPrimaryButton` and
`BossSecondaryButton` (only `BossTheme`/`BossThemeColors` were used before). Containment holds
because `AiProvidersPanel` is reachable only from `LlmProviderSettingsApiImpl`, so those
symbols never load on a pre-1.0.71 host - but that stops being true the moment the panel is
rendered from `SecretManagerContent`, which is exactly why it is written down here.

`LlmProviderSettingsApiImpl` and `BrokeredCredentialBridge` are the **only** files
referencing api symbols added after this plugin's declared floor
(`LlmProviderSettingsAPI`, `LlmApiFormat.GOOGLE_GENERATIVE` from 1.0.71;
`BrokeredCredentialProvider` and `PluginContext.brokeredCredentialProvider` from 1.0.74).
Everything else uses the plugin-local `WireFormat` enum and the plugin-local
`BrokeredKeySource` seam. That is why `registerAiProviderSettings` can wrap registration
in a `LinkageError` guard and why `plugin.json` keeps its lower `apiVersion`: on an older
host the AI panel is simply not served, and secret management still works. Adding a
new-api reference outside those two files would take the whole plugin down on such a host.

`ProviderCredentialStore` is constructed **outside** the guard, which is why it cannot
hold an api type and gets `brokeredKeys` assigned after the fact. Left null, brokered
providers report unconfigured - the same answer a host with no broker should give.

### `LlmApiFormat.OPENAI_RESPONSES` is resolved reflectively, and has to be

The GOOGLE_GENERATIVE argument ("it shipped in the same release as the interface, so any
host that can link this class has both") does **not** extend to `OPENAI_RESPONSES`: it
landed in 1.0.74, three releases later. A host on 1.0.71 links
`LlmProviderSettingsApiImpl` fine and then throws `NoSuchFieldError` on the constant,
because the enum is host-compiled and served parent-first. So it goes through
`LlmApiFormat.valueOf` inside a `LinkageError`/`IllegalArgumentException` guard, and
`configFor` returns null when it is missing - the provider reports unconfigured instead of
crashing the section. Only `RISA_GLM` speaks that format and it needs the broker relay
anyway, so on such a host it could never have worked.

### Brokered providers (RISA Codex GLM)

`ProviderDescriptor.brokerId` marks a provider whose credential nobody types in: the user
is signed in to BOSS and an organisation gateway mints a short-lived model-scoped key for
that identity. Such a provider has **no** `envVarNames`, **no** `consoleUrl` and **no**
`keyPlaceholder`, and the panel renders no key field. `ProviderRegistryTest` pins all
three, because each is a path by which a minted credential would end up somewhere durable
(`envVarNames` in particular also names the secret entry).

Two rules the tests hold, both mutation-verified:

- a brokered credential is **never** written to the secret store, and lives only in
  `ProviderCredentialStore`'s in-memory `brokeredCache`. It expires within hours and is
  cheap to re-obtain, so persisting it trades a credential that self-heals for one that
  leaks. Same rule as `CredentialSource.ENVIRONMENT`, different reason;
- `invalidate()` clears that cache. Sign-out invalidates, and a credential minted for the
  previous session must not be served to the next one.

A failed mint is **not** cached, so a user who signs in can retry without anything else
having to clear the cache. And a `BROKERED` source with a blank key collapses to `NONE`:
`isConfigured` reads the key, so a blank one would offer the provider as usable and fail
on the first request rather than where the user can act on it.

`RISA_GLM` is second in `all`, not first, and that is load-bearing - `default` is
`all.first()`, so leading with an organisation-only provider would make it the default
selection for every user outside RISA, for whom it can never resolve a credential.
`ProviderRegistryTest` pins that too.

### One predicate decides "can this provider's models be known"

`ProviderRegistry.hasKnownModels` / `needsManualModel`. Both the ViewModel
(`refreshStale`, `refreshOne`) and the panel (`ModelSection`) branch on it. They used to
test `modelsEndpoint == null` independently, and the result was that a provider serving a
**fixed** set was treated as one nobody can ask: `catalog.refresh` was never called for it,
so its state stayed `NotConfigured`, and the panel offered an endpoint-and-model-id form
for a provider that has neither. Worse, that form's model field went through
`saveSettings`, and a stored `selectedModelId` used to win outright - so a typo durably
replaced the single model the gateway serves. `resolveModelId` now constrains a
fixed-model provider's selection to its own list.

Mutation-verified: narrowing `hasKnownModels` back to `modelsEndpoint != null` fails
*a fixed-model provider reports known models and needs no manual entry*.

### Brokered mints are guarded twice

- **A generation guard**, mirroring `loadStoredSecrets`: a `fetch` that started before
  `invalidate()` and returns after it is handed to its caller but **not** seated, because
  it belongs to the session that just ended.
- **A per-broker mint lock.** "Check access" calls `invalidate()` then
  `reloadConnections()` while the `invalidations` collector reloads too, so two `loadAll()`s
  run at once, both miss the cache, and both would call the broker. The second waits and
  finds the first one's result. The explicit `reloadConnections()` stays because the next
  line reads `_state.value` to report the outcome.

### The reuse window is capped by the credential's own expiry

`ProviderCredentialStore.reuseUntil` caches a brokered credential for
`min(refreshAfterSeconds, expiresAt - 30s)`, not for the window the broker reported.

Trusting the window alone wedges the provider for its whole duration whenever a broker
reports a window that outlives its key, and that is not hypothetical. RISA's gateway
reported an hour-long reuse window on a key that expired in about three minutes; LLM RPA
then failed `401 Authentication Error - Expired Key` on three consecutive runs spanning
eleven minutes, re-sending the same dead token each time, because nothing re-minted until
the window lapsed. Only a plugin reload (which drops the memory-only cache) cleared it.

Three things worth keeping right:

- **The api calls `expiresAt` "informational" and `refreshAfterSeconds` the thing to act
  on.** This acts on both, deliberately: the window is still what bounds reuse, the expiry
  only ever shortens it. A broker that wants renewal well before expiry keeps that.
- **Every branch of the expiry parser has a test.** Four shapes were covered and two branches
  were not: `removeSuffix(" UTC")` and the compact-offset (`+0000`) regex could each be deleted
  with the suite still green. Both are pinned now, along with the already-`T`-separated-with-space
  case that an unconditional `replaceFirst(' ', 'T')` corrupted into a second `T`.
- **The expiry parser is tolerant on purpose.** The api documents RFC 3339, but the value
  originates in LiteLLM and has been seen space-separated instead of `T`-separated, and
  offset-less. A parser accepting only the documented shape returns null for the real value
  and silently disables the cap - which is worse than no cap, because it looks fixed.
  Offset-less is read as UTC, which is what LiteLLM stores.
- **Absent or unparseable falls back to the reported window**, i.e. exactly the old
  behaviour. Failing closed instead would re-mint on every read for any broker that omits
  the field.

`BrokeredCredential.expiresAt` shipped in api **1.0.74**, verified in the released jar - the
same version `BrokeredCredentialBridge` already requires for `BrokeredCredentialProvider`. So it
is read straight, with no `runCatching`: on any host that can load that class the field exists,
and a guard there would be dead code implying a risk that cannot occur. Add it to the
"Linkage containment" list above if that file ever gains a newer symbol.

**The cap alone was not enough, and that is the part worth remembering.**
`ProviderCredentialStore.brokeredCache` is not what hands a token to a consumer.
`LlmProviderSettingsApiImpl.activeConfig` reads `state.connections`, which
`ensureConnectionsLoaded` fills exactly once (`compareAndSet(false, true)`), and nothing else
calls `loadAll` between a panel visit and a secret edit. So the cap could shorten a window that
nobody ever re-read: the eleven-minute wedge would have reproduced with the cap in place, and
the store-level tests would still have passed, because they call `loadAll` directly.

`configFor` therefore calls `refreshLapsedBrokeredCredential`, which asks
`brokeredCredentialLapsed` and kicks an async `reloadConnections`. Four things to keep:

- **The hook is in `configFor`, not `activeConfig`.** Both api methods funnel through it, and
  hooking only `activeConfig` left `configuredProviders` handing out the same dead token - the
  identical wedge one method over.
- **That call still returns the stale token**; the next one is fresh. `activeConfig` cannot
  suspend, so the alternative was blocking a non-suspending api on a network mint. One failed
  request beats a wedge lasting the whole window.
- **An in-flight flag** collapses a burst of reads into one reload, and a **minimum interval**
  (`minBrokeredRefreshIntervalMs`, 5s) bounds the rate. Both are needed: the flag alone leaves a
  collapsed window driving refreshes back-to-back for as long as a consumer polls, each one a
  full `loadAll()` with its paginated secret scan. The first refresh is never blocked, so a
  genuinely lapsed credential does not wait out the interval.
- **A failed mint is retried, not terminal.** A failure is deliberately never cached, and calling
  "nothing cached" *not lapsed* made one network blip permanent on this path: nothing calls
  `loadAll` again, so the provider stayed unconfigured until the panel was opened.
  `lastMintFailureMs` gives bounded retry (`mintRetryBackoffMs`, 15s). It cannot be expressed as
  a blank-token cache entry - `resolveBrokered` would serve that while the deadline was ahead.

**The interval and the backoff are constructor parameters, not constants.** Hard-coded, every
test of them had to outwait them or be written around them, which is how an untested guard ends
up wrong - and both of these *were* wrong first time.

**The duration guards use `nanoTime`, the expiry cap uses wall time.** The interval floor and the
retry backoff measure *elapsed* time, so a wall clock stepping backwards (VM resume, first NTP
sync, a manual change) would make the difference negative and disable them for the length of the
step - the same symptom as the wedge, from a different cause. The cap has to stay on
`currentTimeMillis`, because it compares against an absolute timestamp the broker sent.

**`reloadConnections` has a generation guard, and it is not covered by a test.** It mirrors the
store's own: capture `invalidations.value`, skip the `_state.update` if it changed, so a refresh in
flight when the user signs out cannot seat a pre-invalidate snapshot in front of every consumer.
The obvious test for it passes either way, because the store's guard already refuses to seat a
*minted* pre-invalidate token - `mintBrokered` returns blank and the provider reads as
unconfigured. The remaining window needs a **cache hit** (not a mint) inside a reload slow enough
to straddle the invalidate, which the current harness cannot arrange. Kept because it is cheap and
mirrors a documented pattern; recorded here because it is unproven rather than proven.

**Clock skew is asymmetric.** The cap compares `expiry - 30s` against the *local* clock, so a
machine behind the broker under-caps (harmless) and one ahead over-caps into exactly the
refresh loop the floor now bounds. The log line names the reported and effective windows so that
case is greppable rather than mysterious.

The refresh runs on `Dispatchers.IO` and wraps `reloadConnections` in `runCatching`. Both matter
now that it can fire from any consumer read rather than only panel entry: `pluginScope` falls back
to `Dispatchers.Main`, and a host `exchange`/`listSecrets` that throws instead of returning a
failed `Result` would escape and cancel a scope that is not a supervisor - silently killing every
later launch in the plugin. The in-flight flag is cleared from `invokeOnCompletion`, not a
`finally`, so a body that never runs on an already-cancelled scope cannot latch it true.

`BrokeredReadPathTest` covers this by driving the api rather than `loadAll`, and uses
`runBlocking` with a real scope rather than `runTest`: the load ends up off the test dispatcher
(the store has no `withContext` of its own - it relies on the host's suspend functions
dispatching), so `advanceUntilIdle()` returns without waiting and every assertion reads an empty
snapshot. It waits
on `connectionsLoaded` and on the mint count instead of sleeping.

Two traps that suite has already fallen into, both caught by mutation:

- **Capturing a mint-count baseline while a refresh is in flight.** The helper reads the api
  twice and the second read can itself trigger a refresh, so the in-flight guard then swallowed
  the read under test. It settles the count first.
- **Firing rapid reads to test the interval floor.** The in-flight guard alone collapses those
  into one mint, so the test passed with the floor removed. The reads have to be *spaced* for the
  floor to be the thing under test.

Still open, and not this change's job: **nothing invalidates on a 401.** A credential that dies
earlier than it claimed - revoked, or a gateway that miscomputes - still wedges until the (now
shorter) window lapses. The durable fix is re-minting once on an auth failure, which needs a way
for the gateway plugin to signal "this credential is dead".

### `ProviderRegistry.fixedModels` is not a return to hardcoded catalogues

The gateway serves one model to one scoped key, so there is no models endpoint and nothing
for a live fetch to correct. `fixedModels` covers exactly that case, and two tests fence
it: every endpoint-less provider must be either `CUSTOM` (the user types the id) or have a
fixed list, and nothing with an endpoint may have a fixed list. Without the second, this
becomes the drifting hardcoded list `ModelCatalogClient` replaced.

### Out-of-process caveat

`plugin.json` declares `isolationMode: out-of-process`, which only engages under
`BOSS_MODE=KERNEL`. In-process (the default) the `@Composable` panel renders
directly. Under KERNEL mode the API crosses a process boundary and the panel is not
expected to render - the host falls back to its "plugin isn't loaded yet" notice
rather than failing.

### Tests

`./gradlew test` - 133 host-independent cases, no live credential needed, run on every
pull request by `.github/workflows/test.yml`. The
model-list parsers are the point: each was written from a provider's published
reference, and xAI's and Together's envelopes aren't documented at all, so
`ModelCatalogClientParseTest` pins the captured shapes (Google's `models/` prefix
stripping, Together's type filter, Anthropic's capability tree, and that a rejected key
never reaches an error message). Also covered: `env_vars` parsing (`=` inside values),
`ProviderSettings` round-trip and tolerance, the catalog TTL / cache-seeding rules, and the
preference file's read-modify-write (one file holds the active provider *and* every model
selection, and for env-keyed providers it is the only record of that choice).

`ProviderCredentialStore` is covered through a fake `SecretDataProvider`, because its
invariants *are* the security story: env-then-stored-then-none precedence, refusing to write
an env-supplied key back to disk, updating rather than duplicating a provider entry, paging
past the first page, and the cache honouring `invalidate()`. `ModelCatalogClientPagingTest`
uses a response *queue* rather than one fixed body, which is what makes cursor-following, the
`MAX_PAGES` bound and the xAI primary-then-fallback path reachable at all.

Two suites were validated against deliberate mutations, because a test that passes
unconditionally is indistinguishable from no test:

- dropping the `after_id` parameter fails the paging suite;
- removing the write-path cache-version check fails
  `a rejected cache version is not laundered back in by the write path`.

`buildPluginJar` asserts the packaged `plugin.json` declares the Gradle version. Do not replace
that with a count of `plugin.json` entries: `duplicatesStrategy = EXCLUDE` means the jar always
holds exactly one, so counting can never fail (verified - the count check was tried first and
did nothing). Asserting the content is what catches a `from` reorder.

`seedFromCache` fills an **absence** - `if (seeded.containsKey(providerId)) return@forEach`.
Do not go back to enumerating states to skip: skipping only `Loaded` papered over a rejected
key (a 401 replaced by a within-TTL cached list, which `isStale` then called fresh), and adding
`Failed` still left `NotConfigured` - which `clearKey` sets precisely to drop a stale list.

Relatedly, `refresh` must carry `lastKnown` through a *chain* of failures
(`Failed -> current.lastKnown`), not just the first. `as? Loaded` only ever worked because
seeding used to convert `Failed` back to `Loaded`; once `Failed` survived, a second consecutive
failure emptied the picker for an offline user who merely reopened the section. Mutation-checked.

A third env case: reverting the `EnvResolver` stubbing in `ProviderCredentialStoreTest` fails 9 tests
*only if* provider variables are exported. `EnvResolver` consults the process environment and
system properties **before** the `env_vars` file, and these tests must use the registry's real
variable names, so they are hermetic only because all three sources are injected. CI never
caught this because CI exports none of them - run the suite with `OPENAI_API_KEY` set if you
touch it.

The cache-laundering test only got teeth after a correction worth remembering: the first version of that
test seeded the stale entry under the *same* provider it then refreshed, so the merge
replaced it either way and the test passed against the bug. The laundering only shows up on a
*bystander* provider's entry. If you extend these, re-run the mutation.

The api jar path is resolved by picking the newest `boss-plugin-api-*.jar` in the sibling
checkout rather than naming a version, and CI tracks `latest` - the api is additive-only, so
a pin would just mean hand-bumping this repo on every api release.

Two test-only dependencies exist because the api is `compileOnly`: the api jar itself,
and an slf4j backend - `BossLogger` binds slf4j at class-init, so without one every
class holding a logger fails with `NoClassDefFoundError`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully - show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
