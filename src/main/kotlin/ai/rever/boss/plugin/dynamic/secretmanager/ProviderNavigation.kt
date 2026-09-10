package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CustomPluginEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Retained requests also reach panels constructed after the navigation event. */
internal class ProviderNavigation {
    companion object {
        const val OPEN_AI_EVENT = "secret-manager.open-ai"
        const val WINDOW_ID_KEY = "windowId"
    }

    private var nextRequest = 0L
    private val requests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val state: StateFlow<Map<String, Long>> get() = requests

    @Synchronized
    fun accept(event: CustomPluginEvent) {
        if (event.eventName != OPEN_AI_EVENT) return
        val window = (event.payload[WINDOW_ID_KEY] as? String)?.takeIf { it.isNotBlank() } ?: return
        val request = ++nextRequest
        requests.update { it + (window to request) }
    }

    /** A retained request is owned by the window, not by each new panel instance. */
    @Synchronized
    fun consume(window: String?, request: Long, aiAvailable: Boolean = true): Boolean {
        if (!aiAvailable || window == null || requests.value[window] != request) return false
        requests.update { it - window }
        return true
    }

    suspend fun collect(events: () -> Flow<CustomPluginEvent>, onFailure: (Exception) -> Unit) {
        try {
            events().collect { accept(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // An optional navigation channel must not cancel the shared plugin scope.
            onFailure(failure)
        }
    }
}
