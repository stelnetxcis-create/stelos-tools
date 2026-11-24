package dev.ii.tools.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.ii.tools.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shell-level access through Shizuku, for the stats an app cannot read (overall CPU use,
 * per-sensor temperatures, GPU use, the busiest processes, battery health).
 *
 * Optional all the way: without Shizuku (or with it stopped, or not allowed) the phone's
 * load is what any app reads. The user service ([ShellStatsService]) is bound only while
 * a PC watches, and its process ends when unbound.
 */
object ShellAccess {
    enum class State { MISSING, STOPPED, ASK, READY }

    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val PREFS = "shell"
    private const val ENABLED = "enabled"
    private const val REQUEST = 7701

    private lateinit var app: Context
    private val _state = MutableStateFlow(State.MISSING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var connection: ServiceConnection? = null
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellStatsService::class.java.name))
            .daemon(false)
            .processNameSuffix("stats")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** Listeners only: Shizuku hands its binder over through the provider when it runs. */
    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { code, _ -> if (code == REQUEST) refresh() }
        refresh()
    }

    fun refresh() {
        if (!::app.isInitialized) return
        _state.value = when {
            !installed() && !Shizuku.pingBinder() -> State.MISSING
            !Shizuku.pingBinder() -> State.STOPPED
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) -> State.READY
            else -> State.ASK
        }
    }

    private fun installed() = runCatching { app.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, on).apply()
    }

    /** Shizuku's own dialog; the answer comes back through the listener. */
    fun request() {
        if (Shizuku.pingBinder()) runCatching { Shizuku.requestPermission(REQUEST) }
    }

    /** Shizuku's app, to start it (or its store page when it is not installed). */
    fun openIntent(c: Context): Intent =
        c.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))

    fun usable(c: Context): Boolean = isEnabled(c) && state.value == State.READY

    /** Binds the stats service (a few hundred ms the first time), or null when it cannot. */
    suspend fun open(): IShellStats? {
        if (!::app.isInitialized || state.value != State.READY) return null
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val s = binder?.takeIf { it.pingBinder() }?.let { IShellStats.Stub.asInterface(it) }
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                connection = conn
                runCatching { Shizuku.bindUserService(args, conn) }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }.also { if (it == null) close() }
    }

    /** Unbinds and ends the service's process. */
    fun close() {
        val conn = connection ?: return
        connection = null
        runCatching { Shizuku.unbindUserService(args, conn, true) }
    }
}
