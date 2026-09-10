package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Retained requests also reach panels constructed after the navigation event. */
internal class ProviderNavigation {
    private val requests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val state: StateFlow<Map<String, Long>> get() = requests

    fun accept(event: CustomPluginEvent) {
        if (event.eventName != "secret-manager.open-ai") return
        val window = event.payload["windowId"] as? String ?: return
        requests.update { it + (window to ((it[window] ?: 0L) + 1L)) }
    }
}
