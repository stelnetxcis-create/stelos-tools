package dev.ii.tools.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An app with at least one ongoing notification right now. */
data class OngoingApp(
    val pkg: String,
    val label: String,
    val looksLive: Boolean,
)

/**
 * What the UI observes about the relay. The bound [LiveListener] is the only thing
 * that can list notifications, so it publishes here and the screens just collect.
 */
object LiveHub {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _ongoing = MutableStateFlow<List<OngoingApp>>(emptyList())
    /**
     * Apps with an ongoing notification, for the per-app picker. Listing them reads every
     * active notification, so the listener does it only while a screen collects this.
     */
    val ongoing: StateFlow<List<OngoingApp>> = _ongoing.asStateFlow()

    internal fun ongoingWatched(): Boolean = _ongoing.subscriptionCount.value > 0

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            _ongoing.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { seen ->
                if (seen) listener?.listOngoingNow()
            }
        }
    }

    /** Payloads currently on the shell, by source notification key. */
    private val _relayed = MutableStateFlow<Map<String, LivePayload>>(emptyMap())
    val relayed: StateFlow<Map<String, LivePayload>> = _relayed.asStateFlow()

    @Volatile
    internal var listener: LiveListener? = null

    internal fun setConnected(on: Boolean) {
        _connected.value = on
        if (!on) {
            _ongoing.value = emptyList()
            _relayed.value = emptyMap()
        }
    }

    internal fun setOngoing(list: List<OngoingApp>) {
        _ongoing.value = list
    }

    internal fun putRelayed(key: String, payload: LivePayload) {
        _relayed.value = _relayed.value + (key to payload)
    }

    internal fun removeRelayed(key: String) {
        if (key in _relayed.value) _relayed.value = _relayed.value - key
    }

    internal fun retainRelayed(keys: Set<String>) {
        _relayed.value = _relayed.value.filterKeys { it in keys }
    }

    /** Re-evaluates every active notification (after a per-app mode change). */
    fun rescan() {
        listener?.rescan()
    }
}
