package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderNavigationTest {
    @Test fun `requests survive panel creation and are scoped to the requested window`() {
        val navigation = ProviderNavigation()
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "one")))
        assertEquals(mapOf("one" to 1L), navigation.state.value)
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "two")))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai", mapOf("windowId" to "one")))
        assertEquals(mapOf("one" to 2L, "two" to 1L), navigation.state.value)
    }

    @Test fun `unrelated and untargeted events do not navigate`() {
        val navigation = ProviderNavigation()
        navigation.accept(CustomPluginEvent("fluck", "other", mapOf("windowId" to "one")))
        navigation.accept(CustomPluginEvent("fluck", "secret-manager.open-ai"))
        assertTrue(navigation.state.value.isEmpty())
    }
}
