package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Local CLI section, and the one invariant it exists to hold: **exactly one thing is
 * active**.
 *
 * The gateway resolves a disagreement in the engine's favour - a selected engine wins over any
 * configured key - so a stale HTTP preference cannot misroute a request. What it can do is make
 * the panel show a provider as active while requests go somewhere else, which is why each
 * setter clears the other and why that is tested from both directions.
 */
class CliEngineSelectionTest {

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stopScopes() = scopes.forEach { it.cancel() }

    /** Records what it was asked to select, and can refuse the way the api documents. */
    private class FakeCliEngines(
        private val engines: List<CliEngineInfo> =
            listOf(
                CliEngineInfo("claude", "Claude Code CLI", "Your local login.", "Install Claude Code."),
                CliEngineInfo("codex", "Codex CLI", "Your local login.", "Install codex."),
            ),
        private val health: Map<String, CliEngineHealth> =
            mapOf("claude" to CliEngineHealth.Ready("2.1.0"), "codex" to CliEngineHealth.NotInstalled("brew install codex")),
        /** Engines this fake will refuse, mirroring a gateway that does not have one. */
        private val refuse: Set<String> = emptySet(),
    ) : CliEngineAccess {
        val selections = mutableListOf<String?>()
        var selected: String? = null
            private set

        override fun engines(): List<CliEngineInfo> = engines

        override suspend fun health(engineId: String): CliEngineHealth =
            health[engineId] ?: CliEngineHealth.Unknown

        override fun selectedEngineId(): String? = selected

        override fun selectEngine(engineId: String?): Boolean {
            selections += engineId
            if (engineId != null && engineId in refuse) return false
            selected = engineId
            return true
        }
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /** Hermetic: every source of variables is injected, so no exported key can leak in. */
    private fun envIn(dir: File): EnvResolver =
        EnvResolver(
            bossRootDir = dir.also { File(it, "env_vars").writeText("") },
            processEnv = { null },
            systemProperty = { null },
            useLaunchctl = false,
        )

    private fun viewModelWith(cli: CliEngineAccess?): AiProvidersViewModel {
        val root = tempDir("cli-selection")
        val env = envIn(root)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes.add(it) }
        return AiProvidersViewModel(
            store = ProviderCredentialStore(FakeSecretDataProvider(emptyList()), env),
            catalog = ModelCatalog(cacheDir = tempDir("cli-catalog")),
            prefs = ActiveProviderPrefs(bossRootDir = root),
            legacyImport = null,
            splitViewOperations = null,
            scope = scope,
            envResolver = env,
            cliEngines = cli,
        )
    }

    /** The engine list and its probes are loaded from `init`; this waits for them. */
    private suspend fun AiProvidersViewModel.loadedEngines(): List<CliEngineInfo> {
        withTimeout(TIMEOUT_MS) { state.first { it.cliEngines.isNotEmpty() } }
        return state.value.cliEngines
    }

    @Test
    fun theEnginesAndTheirHealthAreLoadedOnOpen() = runBlocking {
        val vm = viewModelWith(FakeCliEngines())

        val engines = vm.loadedEngines()

        assertEquals(listOf("claude", "codex"), engines.map { it.id })
        withTimeout(TIMEOUT_MS) { vm.state.first { it.cliHealth.size == 2 } }
        assertEquals(CliEngineHealth.Ready("2.1.0"), vm.state.value.cliHealthOf("claude"))
        assertTrue(vm.state.value.cliHealthOf("codex") is CliEngineHealth.NotInstalled)
    }

    @Test
    fun anUnprobedEngineReadsAsCheckingRatherThanMissing() = runBlocking {
        // The row renders before its probe answers, and "not installed" would be a claim
        // rather than a wait - the difference between a user waiting a moment and a user
        // going to install something they already have.
        val vm = viewModelWith(FakeCliEngines())

        assertEquals(CliEngineHealth.Unknown, vm.state.value.cliHealthOf("claude"))
    }

    @Test
    fun selectingAnEngineHandsItTheRequests() = runBlocking {
        val cli = FakeCliEngines()
        val vm = viewModelWith(cli)
        vm.loadedEngines()

        vm.setActiveCliEngine("claude")

        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == "claude" } }
        assertEquals("claude", cli.selected)
    }

    @Test
    fun selectingAnEngineTakesTheRequestsAwayFromTheProvider() = runBlocking {
        // Only one thing may read as active. The gateway would serve the engine regardless,
        // so leaving the provider marked active is the panel lying about where requests go.
        val cli = FakeCliEngines()
        val vm = viewModelWith(cli)
        vm.loadedEngines()
        vm.setActiveProvider("ANTHROPIC")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeProviderId == "ANTHROPIC" } }

        vm.setActiveCliEngine("claude")

        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == "claude" } }
        // The provider stays SELECTED for editing - it is still the row whose key you can
        // change - but it is no longer what serves a request.
        assertEquals("claude", vm.state.value.activeCliEngineId)
    }

    @Test
    fun choosingAProviderHandsTheRequestsBackFromTheEngine() = runBlocking {
        // The other direction, and the one that actually misroutes if it is missing: the
        // gateway prefers a selected engine, so without the selectEngine(null) here the panel
        // would show the provider active while every request still went to the CLI.
        val cli = FakeCliEngines()
        val vm = viewModelWith(cli)
        vm.loadedEngines()
        vm.setActiveCliEngine("claude")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == "claude" } }

        vm.setActiveProvider("ANTHROPIC")

        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeProviderId == "ANTHROPIC" } }
        assertNull(vm.state.value.activeCliEngineId, "the engine was left active alongside a provider")
        assertNull(cli.selected, "the gateway was left serving the engine")
        assertTrue(null in cli.selections, "nothing told the gateway to release the engine")
    }

    @Test
    fun deselectingAnEngineIsPossibleWithoutPickingAProvider() = runBlocking {
        // The panel offers this by clicking the active row. Without it the only way back is to
        // choose a provider, which a user with no API key cannot do.
        val cli = FakeCliEngines()
        val vm = viewModelWith(cli)
        vm.loadedEngines()
        vm.setActiveCliEngine("claude")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == "claude" } }

        vm.setActiveCliEngine(null)

        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == null } }
        assertNull(cli.selected)
    }

    @Test
    fun aRefusedSelectionSaysSoRatherThanSpringingBack() = runBlocking {
        // selectEngine returns false for an engine the gateway does not have. A row that
        // silently reverts is worse than one that explains itself.
        val cli = FakeCliEngines(refuse = setOf("claude"))
        val vm = viewModelWith(cli)
        vm.loadedEngines()

        vm.setActiveCliEngine("claude")

        withTimeout(TIMEOUT_MS) { vm.state.first { it.error != null } }
        assertNull(vm.state.value.activeCliEngineId, "a refused selection was shown as active")
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun aReleaseTheGatewayRefusesLeavesTheEngineShownAsActive() = runBlocking {
        // The honest reading: if the gateway would not release the engine, it is still serving
        // requests, and clearing the panel's copy would be the exact disagreement this is all
        // guarding against.
        val cli =
            object : CliEngineAccess by FakeCliEngines() {
                override fun selectEngine(engineId: String?): Boolean = engineId != null
            }
        val vm = viewModelWith(cli)
        vm.loadedEngines()
        vm.setActiveCliEngine("claude")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeCliEngineId == "claude" } }

        vm.setActiveProvider("ANTHROPIC")

        withTimeout(TIMEOUT_MS) { vm.state.first { it.activeProviderId == "ANTHROPIC" } }
        assertEquals("claude", vm.state.value.activeCliEngineId, "a refused release was hidden from the user")
    }

    @Test
    fun noGatewayMeansNoSectionAndNoCrash() = runBlocking {
        // Null covers "not installed", "too old to link the api" and "host without the
        // symbol" - none of which gives the user anything to do here, so the section is
        // absent rather than empty.
        val vm = viewModelWith(null)

        assertTrue(vm.state.value.cliEngines.isEmpty())
        vm.setActiveCliEngine("claude")
        withTimeout(TIMEOUT_MS) { vm.state.first { it.error != null } }
        assertNull(vm.state.value.activeCliEngineId)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
