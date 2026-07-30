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

The `processResources` task automatically syncs the version into `plugin.json` at build time. Never manually edit the version in `plugin.json` — only change it in `build.gradle.kts`.

## AI Providers (`ai/` package)

This plugin owns **all** AI provider configuration. The host has none: its
`Settings → AI Providers` section renders `LlmProviderSettingsPanel` through
`LlmProviderSettingsAPI`, and `PluginContext.llmProvider` is relayed from the same
registered instance. Provider registry, credentials, environment-variable resolution
and the model catalogue all live here.

**Model lists are always fetched live** from each provider's own models endpoint
(`ModelCatalogClient`), cached with a timestamp and a 6-hour TTL. There is
deliberately no bundled fallback list: the host implementation this replaced shipped
hardcoded models that drifted years out of date, and a provider with no credential
now reports "not configured" instead of guessing.

### The version must come from Gradle, not from a second copy of plugin.json

`version` reads the bundled `plugin.json` that `processResources` stamps. Two traps here, both
hit once already:

- `javaClass.package?.implementationVersion` does **not** work, even though `buildPluginJar`
  writes `Implementation-Version` into the manifest. `getImplementationVersion()` returns null
  under a plain `URLClassLoader`, which is what the host's `PluginClassLoader` extends, so the
  plugin reported `"unknown"` to the host and store for a whole release.
- The jar tasks must **not** also `from("src/main/resources")`. `sourceSets.main.output` already
  carries the stamped copy; adding the raw directory put an *unstamped* `plugin.json` in the jar
  too (the committed one says `1.0.9`), and with `duplicatesStrategy = EXCLUDE` the winner was
  decided by `from` order alone.

`PluginVersionTest` asserts against `boss.plugin.expectedVersion`, injected by the Test task from
the Gradle version. Comparing the reported version to the bundled `plugin.json` would be circular
— both read the same file, so any value in it passes, and `1.0.9` is valid semver.

### Provider keys are readable by agents through `secret_get`

Storing provider keys as ordinary secrets buys encryption, RLS and an audit trail for free,
but it has a second half worth stating: `secret_get` (`SecretManagerMcpTools.kt`) returns any
secret's `password` by id to the calling agent, so **every provider API key is agent-readable
through a tool this same plugin registers**. That is arguably intended — an agent using a
provider needs its key, and `PluginContext.llmProvider` already hands `LlmConfig.apiKey` to
plugins — but the MCP surface is a wider trust boundary than the in-process one, and this
feature is what put provider keys behind it. It is *not* a new mechanism (`secret_get` already
exposed every other secret, including Plugin Store keys), only new data in an existing channel.

If that is not wanted, exclude `TAG_AI_PROVIDER` entries from `secret_get` — the tag is
already on every entry this feature writes.

### Do not add OAuth without re-checking the docs

Sign-in is intentionally absent. As of July 2026:

- **Anthropic** prohibits third-party OAuth outright (policy 2026-02-20, billing
  enforcement 2026-04-04). Subscription tokens are Claude Code / claude.ai only.
  Wiring their OAuth client here would breach their terms.
- **OpenAI**'s "Sign in with ChatGPT" ships only inside Codex tooling; there is no
  third-party program.
- **xAI** and **Moonshot (Kimi)** publish Bearer-API-key auth only in their REST
  references. Their OAuth/device-code flows belong to their own coding CLIs — the
  same category as Anthropic's, and not a documented third-party surface.
- **Google** does have a documented installed-app OAuth flow, but it runs through
  **Vertex AI** — a different base URL needing a GCP project, region and ADC, not the
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
guard — a member newer than the floor would throw `NoSuchMethodError` there and take the
*whole* plugin down, not just the AI section. `cacheProvider` is inside the guard and so is
unconstrained.

The audit also has to cover the UI kit, not just `PluginContext`: this feature added
first-time uses of `BossSection`, `BossCard`, `BossTextField`, `BossPrimaryButton` and
`BossSecondaryButton` (only `BossTheme`/`BossThemeColors` were used before). Containment holds
because `AiProvidersPanel` is reachable only from `LlmProviderSettingsApiImpl`, so those
symbols never load on a pre-1.0.71 host — but that stops being true the moment the panel is
rendered from `SecretManagerContent`, which is exactly why it is written down here.

`LlmProviderSettingsApiImpl` is the **only** file referencing api symbols added in
1.0.71 (`LlmProviderSettingsAPI`, `LlmApiFormat.GOOGLE_GENERATIVE`). Everything else
uses the plugin-local `WireFormat` enum. That is why `registerAiProviderSettings`
can wrap registration in a `LinkageError` guard and why `plugin.json` keeps its lower
`apiVersion`: on an older host the AI panel is simply not served, and secret
management still works. Adding a new-api reference outside that file would take the
whole plugin down on such a host.

### Out-of-process caveat

`plugin.json` declares `isolationMode: out-of-process`, which only engages under
`BOSS_MODE=KERNEL`. In-process (the default) the `@Composable` panel renders
directly. Under KERNEL mode the API crosses a process boundary and the panel is not
expected to render — the host falls back to its "plugin isn't loaded yet" notice
rather than failing.

### Tests

`./gradlew test` — 79 host-independent cases, no live credential needed, run on every
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

A third: reverting the `EnvResolver` stubbing in `ProviderCredentialStoreTest` fails 9 tests
*only if* provider variables are exported. `EnvResolver` consults the process environment and
system properties **before** the `env_vars` file, and these tests must use the registry's real
variable names, so they are hermetic only because all three sources are injected. CI never
caught this because CI exports none of them — run the suite with `OPENAI_API_KEY` set if you
touch it.

The cache-laundering test only got teeth after a correction worth remembering: the first version of that
test seeded the stale entry under the *same* provider it then refreshed, so the merge
replaced it either way and the test passed against the bug. The laundering only shows up on a
*bystander* provider's entry. If you extend these, re-run the mutation.

The api jar path is resolved by picking the newest `boss-plugin-api-*.jar` in the sibling
checkout rather than naming a version, and CI tracks `latest` — the api is additive-only, so
a pin would just mean hand-bumping this repo on every api release.

Two test-only dependencies exist because the api is `compileOnly`: the api jar itself,
and an slf4j backend — `BossLogger` binds slf4j at class-init, so without one every
class holding a logger fails with `NoClassDefFoundError`.

## Code Quality

- Use Compose Multiplatform APIs (not Android-specific)
- All Kotlin files must end with a newline
- Handle null providers gracefully — show fallback UI, never crash

## CI/CD

Pushes to `main` trigger the release workflow which:
1. Builds the plugin JAR
2. Creates a GitHub release
3. Publishes to the BOSS Plugin Store

The workflow is defined in `.github/workflows/build.yml` and delegates to the shared workflow in `risa-labs-inc/BossConsole-Releases`.
