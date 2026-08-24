package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the AI section tells the user about the AI Gateway, and how.
 *
 * The two facts worth pinning are the ones that were wrong in the version of this that shipped
 * elsewhere: a **disabled** plugin is still in `getLoadedPlugins()`, and an old Toolbox has
 * nothing listening for the install deep link. Both produce a control that does nothing, which is
 * worse than the silence this feature replaces.
 */
class GatewayPresenceTest {
    @Test
    fun `an installed gateway means no notice`() {
        assertEquals(
            GatewayNotice.NONE,
            gatewayNotice(installed = true, canAskToolboxToInstall = true, canOpenToolbox = true),
        )
    }

    @Test
    fun `installed wins even when nothing could be offered anyway`() {
        // Ordering matters: reversed, a host that cannot browse or open panels would be told to
        // install a gateway it already has.
        assertEquals(
            GatewayNotice.NONE,
            gatewayNotice(installed = true, canAskToolboxToInstall = false, canOpenToolbox = false),
        )
    }

    @Test
    fun `absent with a recent Toolbox offers the install prompt`() {
        assertEquals(
            GatewayNotice.OFFER_INSTALL,
            gatewayNotice(installed = false, canAskToolboxToInstall = true, canOpenToolbox = true),
        )
    }

    @Test
    fun `absent with an old Toolbox falls back to opening it`() {
        assertEquals(
            GatewayNotice.OFFER_TOOLBOX,
            gatewayNotice(installed = false, canAskToolboxToInstall = false, canOpenToolbox = true),
        )
    }

    @Test
    fun `absent with no route says so instead of showing a dead button`() {
        assertEquals(
            GatewayNotice.DESCRIBE_ONLY,
            gatewayNotice(installed = false, canAskToolboxToInstall = false, canOpenToolbox = false),
        )
    }

    @Test
    fun `the gateway counts as installed only when it is enabled, healthy and compatible`() {
        assertTrue(presence(gateway = loaded(GatewayPresence.GATEWAY_ID)).installed())
    }

    @Test
    fun `a disabled gateway is not installed`() {
        // `disablePlugin` never unloads, so the row survives in getLoadedPlugins with
        // isEnabled = false - and a disabled gateway answers no getPluginAPI and serves no
        // engines. Treating it as present would put the section back to unexplained silence.
        assertFalse(presence(gateway = loaded(GatewayPresence.GATEWAY_ID, isEnabled = false)).installed())
    }

    @Test
    fun `an unhealthy or incompatible gateway is not installed`() {
        assertFalse(presence(gateway = loaded(GatewayPresence.GATEWAY_ID, healthy = false)).installed())
        assertFalse(presence(gateway = loaded(GatewayPresence.GATEWAY_ID, isIncompatible = true)).installed())
    }

    @Test
    fun `another plugin being present is not the gateway being present`() {
        assertFalse(presence(gateway = loaded("ai.rever.boss.plugin.dynamic.somethingelse")).installed())
    }

    @Test
    fun `no loader delegate reads as absent, and offers what it still can`() {
        // A host that will not hand over the delegate cannot be asked about the Toolbox either,
        // so the honest answer is the one route that needs neither: opening the panel.
        val presence = GatewayPresence(loader = null, panels = FakePanels(), windowId = "w")
        assertFalse(presence.installed())
        assertEquals(GatewayNotice.OFFER_TOOLBOX, presence.notice())
    }

    @Test
    fun `a throwing loader is absent rather than a crash`() {
        val presence = GatewayPresence(loader = ThrowingLoader(), panels = FakePanels(), windowId = "w")
        assertFalse(presence.installed())
        assertFalse(presence.canAskToolboxToInstall())
    }

    @Test
    fun `the Toolbox can be asked only from the release that carried the handler`() {
        assertTrue(presenceWithToolbox("1.9.14").canAskToolboxToInstall())
        assertTrue(presenceWithToolbox("1.9.21").canAskToolboxToInstall())
        assertTrue(presenceWithToolbox("2.0.0").canAskToolboxToInstall())
        assertFalse(presenceWithToolbox("1.9.13").canAskToolboxToInstall())
        assertFalse(presenceWithToolbox("1.8.99").canAskToolboxToInstall())
    }

    @Test
    fun `a prerelease of the release that added the handler has the handler`() {
        assertTrue(presenceWithToolbox("1.9.14-rc1").canAskToolboxToInstall())
    }

    @Test
    fun `an unparseable Toolbox version is not asked`() {
        assertFalse(presenceWithToolbox("nightly").canAskToolboxToInstall())
    }

    @Test
    fun `a disabled Toolbox is not asked`() {
        val loader =
            FakeLoader(listOf(loaded(GatewayPresence.TOOLBOX_ID, version = "1.9.21", isEnabled = false)))
        assertFalse(GatewayPresence(loader, FakePanels(), "w", browseSupported = { true }).canAskToolboxToInstall())
    }

    @Test
    fun `a platform that cannot browse is not asked`() {
        val loader = FakeLoader(listOf(loaded(GatewayPresence.TOOLBOX_ID, version = "1.9.21")))
        val presence = GatewayPresence(loader, FakePanels(), "w", browseSupported = { false })
        assertFalse(presence.canAskToolboxToInstall())
    }

    @Test
    fun `the install link names the gateway and asks the Toolbox to install it`() =
        runBlocking {
            val visited = mutableListOf<String>()
            val loader = FakeLoader(listOf(loaded(GatewayPresence.TOOLBOX_ID, version = "1.9.21")))
            val presence =
                GatewayPresence(
                    loader = loader,
                    panels = FakePanels(),
                    windowId = "w",
                    browseSupported = { true },
                    browse = { visited += it; true },
                )

            assertTrue(presence.askToolboxToInstall())
            assertEquals(
                listOf(
                    "boss://plugin?id=ai.rever.boss.plugin.dynamic.pluginmanager" +
                        "&action=install&plugin=ai.rever.boss.plugin.dynamic.aigateway",
                ),
                visited,
            )
        }

    @Test
    fun `nothing is handed to the platform when the Toolbox is too old`() =
        runBlocking {
            // Not merely a false return: a link nothing is listening for looks to the user like a
            // button that silently failed, so it must not be sent at all.
            val visited = mutableListOf<String>()
            val loader = FakeLoader(listOf(loaded(GatewayPresence.TOOLBOX_ID, version = "1.9.13")))
            val presence =
                GatewayPresence(loader, FakePanels(), "w", browseSupported = { true }, browse = { visited += it; true })

            assertFalse(presence.askToolboxToInstall())
            assertTrue(visited.isEmpty())
        }

    @Test
    fun `opening the Toolbox needs both a provider and a window`() =
        runBlocking {
            val panels = FakePanels()
            assertTrue(GatewayPresence(FakeLoader(), panels, "w").openToolbox())
            assertEquals(listOf(GatewayPresence.TOOLBOX_PANEL to "w"), panels.opened)

            assertFalse(GatewayPresence(FakeLoader(), panels, windowId = null).openToolbox())
            assertFalse(GatewayPresence(FakeLoader(), panels = null, windowId = "w").openToolbox())
        }

    private fun presenceWithToolbox(version: String) =
        GatewayPresence(
            loader = FakeLoader(listOf(loaded(GatewayPresence.TOOLBOX_ID, version = version))),
            panels = FakePanels(),
            windowId = "w",
            browseSupported = { true },
        )

    private fun presence(gateway: LoadedPluginInfo) =
        GatewayPresence(FakeLoader(listOf(gateway)), FakePanels(), "w")

    private fun loaded(
        pluginId: String,
        version: String = "1.1.3",
        isEnabled: Boolean = true,
        healthy: Boolean = true,
        isIncompatible: Boolean = false,
    ) = LoadedPluginInfo(
        pluginId = pluginId,
        displayName = pluginId,
        version = version,
        isEnabled = isEnabled,
        healthy = healthy,
        isIncompatible = isIncompatible,
    )

    private class FakeLoader(private val plugins: List<LoadedPluginInfo> = emptyList()) : StubLoader() {
        override fun getLoadedPlugins(): List<LoadedPluginInfo> = plugins
    }

    private class ThrowingLoader : StubLoader() {
        override fun getLoadedPlugins(): List<LoadedPluginInfo> = throw NoSuchMethodError("getLoadedPlugins")
    }
}

/**
 * The abstract members of `PluginLoaderDelegate` these fakes do not care about.
 *
 * Separate so each fake says only what it is for.
 */
internal abstract class StubLoader : PluginLoaderDelegate {
    override fun isPluginLoaded(pluginId: String): Boolean = getLoadedPlugins().any { it.pluginId == pluginId }

    override suspend fun loadPlugin(jarPath: String) = null

    override suspend fun unloadPlugin(pluginId: String) = false

    override suspend fun reloadPlugin(pluginId: String) = null

    override fun getLoadedPlugins(): List<LoadedPluginInfo> = emptyList()

    override fun getPluginsDirectory() = ""

    override fun getBundledPluginsDirectory() = ""

    override fun isCurrentUserAdmin() = false

    override suspend fun enablePlugin(pluginId: String) = false

    override suspend fun disablePlugin(pluginId: String) = false

    override fun getAccessToken(): String? = null
}

/** A `PanelEventProvider` that records what it was asked to reveal. */
internal class FakePanels : ai.rever.boss.plugin.api.PanelEventProvider {
    val opened = mutableListOf<Pair<ai.rever.boss.plugin.api.PanelId, String>>()

    override suspend fun openPanel(
        panelId: ai.rever.boss.plugin.api.PanelId,
        windowId: String,
    ) {
        opened += panelId to windowId
    }

    override suspend fun closePanel(
        panelId: ai.rever.boss.plugin.api.PanelId,
        windowId: String,
    ) = Unit
}
