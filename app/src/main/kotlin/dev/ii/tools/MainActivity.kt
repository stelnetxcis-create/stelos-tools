package dev.ii.tools

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.ii.tools.deck.DeckStore
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.PairingUri
import dev.ii.tools.live.LiveListener
import dev.ii.tools.media.PcLyrics
import dev.ii.tools.ui.AudioScreen
import dev.ii.tools.ui.CallsScreen
import dev.ii.tools.ui.ClipboardScreen
import dev.ii.tools.ui.RoutinesScreen
import dev.ii.tools.ui.RuleEditorScreen
import dev.ii.tools.ui.DeckScreen
import dev.ii.tools.ui.GalleryScreen
import dev.ii.tools.ui.HomeScreen
import dev.ii.tools.ui.CameraScreen
import dev.ii.tools.ui.Dest
import dev.ii.tools.ui.SecondScreenScreen
import dev.ii.tools.ui.SettingsScreen
import dev.ii.tools.ui.ToolsScreen
import dev.ii.tools.ui.UpdateScreen
import dev.ii.tools.ui.LiveScreen
import dev.ii.tools.ui.MediaModeScreen
import dev.ii.tools.ui.NotifsScreen
import dev.ii.tools.ui.PcLinkScreen
import dev.ii.tools.ui.PerfScreen
import dev.ii.tools.ui.PhoneDataScreen
import dev.ii.tools.ui.CalendarScreen
import dev.ii.tools.ui.RemoteScreen
import dev.ii.tools.ui.ShareScreen
import dev.ii.tools.ui.ShotsScreen
import dev.ii.tools.ui.Shared
import dev.ii.tools.ui.WallpaperScreen
import dev.ii.tools.ui.theme.IiToolsTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Every screen is its own activity, as on the rest of Android. */
abstract class ScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IiToolsTheme { Screen() } }
    }

    @Composable
    abstract fun Screen()

    /** Opens [dest] as its own activity, with the system's transition. */
    fun open(dest: Dest) = startActivity(Intent(this, activityFor(dest)))
}

private fun activityFor(dest: Dest): Class<out ScreenActivity> = when (dest) {
    Dest.SETTINGS -> LinkActivity::class.java
    Dest.HOME_EDIT -> HomeEditActivity::class.java
    Dest.REMOTE -> RemoteActivity::class.java
    Dest.AUDIO -> AudioActivity::class.java
    Dest.DECK -> DeckActivity::class.java
    Dest.WALLPAPER -> WallpaperActivity::class.java
    Dest.TOOLS -> ToolsActivity::class.java
    Dest.TOUCHPAD -> TouchpadActivity::class.java
    Dest.DRAW -> DrawActivity::class.java
    Dest.PRESENTER -> PresenterActivity::class.java
    Dest.PERF -> PerfActivity::class.java
    Dest.CAMERA -> CameraActivity::class.java
    Dest.MIC -> MicActivity::class.java
    Dest.SECOND_SCREEN -> SecondScreenActivity::class.java
    Dest.LIVE -> LiveActivity::class.java
    Dest.NOTIFS -> NotifsActivity::class.java
    Dest.CLIPBOARD -> ClipboardActivity::class.java
    Dest.SHOTS -> ShotsActivity::class.java
    Dest.GALLERY -> GalleryActivity::class.java
    Dest.PHONE_DATA -> PhoneDataActivity::class.java
    Dest.CALENDAR -> CalendarActivity::class.java
    Dest.PC_LINK -> PcLinkActivity::class.java
    Dest.CALLS -> CallsActivity::class.java
    Dest.ROUTINES -> RoutinesActivity::class.java
    Dest.MEDIA_MODE -> MediaModeActivity::class.java
    Dest.UPDATE -> UpdateActivity::class.java
}

/** The home: a dashboard of the PC, or pairing when there is none. */
class MainActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        LiveListener.ensureChannel(this)
        dev.ii.tools.update.AppUpdate.ensureChannel(this)
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun Screen() = HomeScreen(onOpen = ::open)
}

/** Home screen editor, opened from Settings directly into edit mode. */
class HomeEditActivity : ScreenActivity() {
    @Composable
    override fun Screen() = HomeScreen(
        onOpen = ::open,
        startEditing = true,
        onFinishEdit = ::finish,
    )
}

/** Settings: pairing, the link, every permission and switch. Also where `iilink://pair` links land. */
class LinkActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePairingLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    private fun handlePairingLink(intent: Intent?) {
        PairingUri.parse(intent?.data)?.let(LinkHub::pair)
    }

    @Composable
    override fun Screen() = SettingsScreen(onBack = ::finish, onOpen = ::open)
}

class LiveActivity : ScreenActivity() {
    @Composable
    override fun Screen() = LiveScreen(onBack = ::finish)
}

class RemoteActivity : ScreenActivity() {
    @Composable
    override fun Screen() = RemoteScreen(onBack = ::finish)
}

class AudioActivity : ScreenActivity() {
    @Composable
    override fun Screen() = AudioScreen(onBack = ::finish)
}

/**
 * The deck. Dock mode is a window state, so it lives here: sideways, screen kept on and
 * dimmed, system bars hidden. Nothing runs in the background for it: the deck is only
 * alive while it is on screen.
 */
class DeckActivity : ScreenActivity() {
    private var dock by mutableStateOf(false)
    private var launchFolder by mutableStateOf<String?>(null)
    private var launchNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Loaded here, not at app start: only the deck needs its layout.
        DeckStore.init(this)
        super.onCreate(savedInstanceState)
        dock = savedInstanceState?.getBoolean(DOCK) ?: false
        if (savedInstanceState == null) {
            launchFolder = intent?.getStringExtra(EXTRA_FOLDER)
        }
        applyDock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val folder = intent.getStringExtra(EXTRA_FOLDER)
        if (folder != null) {
            launchFolder = folder
            launchNonce++
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(DOCK, dock)
    }

    @Composable
    override fun Screen() = DeckScreen(
        onBack = ::finish,
        dock = dock,
        onDock = {
            dock = it
            applyDock()
        },
        initialFolder = launchFolder,
        folderNonce = launchNonce,
    )

    private fun applyDock() {
        requestedOrientation = if (dock) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (dock) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = if (dock) DOCK_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (dock) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
        private const val DOCK = "dock"
        /** Low enough to sit on a desk all day, still readable at a glance. */
        private const val DOCK_BRIGHTNESS = 0.08f
    }
}

/** Wallpaper: from the home, or from the share sheet as "Set on PC". */
class WallpaperActivity : ScreenActivity() {
    @Composable
    override fun Screen() = WallpaperScreen(
        shared = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java),
        onBack = ::finish,
        onDone = ::finish,
    )
}

/** One rule's editor; the rule's id comes in `rule`. */
class RuleEditorActivity : ScreenActivity() {
    @Composable
    override fun Screen() = RuleEditorScreen(intent.getStringExtra("rule").orEmpty(), onBack = ::finish)
}

/**
 * The shortcut builder: any action of the PC, with its value. Started by the system's
 * "create shortcut" (Modes and Routines, a launcher), it hands the shortcut back; started by the
 * app (`pin`), it offers to put it on the home screen.
 */
class CreateShortcutActivity : ScreenActivity() {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    override fun Screen() {
        var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<dev.ii.tools.routines.Step?>(null) }
        val title = chosen?.let { dev.ii.tools.ui.stepTitle(it) }
        androidx.compose.runtime.LaunchedEffect(chosen, title) {
            val step = chosen ?: return@LaunchedEffect
            val label = title ?: return@LaunchedEffect
            val ctx = this@CreateShortcutActivity
            if (intent.getBooleanExtra("pin", false)) {
                dev.ii.tools.routines.ShortcutKit.pin(ctx, "step_" + step.toJson().toString().hashCode(), label,
                    dev.ii.tools.ui.stepIcon(ctx, step), dev.ii.tools.routines.ShortcutKit.runIntent(ctx, step))
                finish()
            } else {
                val launch = Intent(ctx, dev.ii.tools.routines.ExternalActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(dev.ii.tools.routines.ShortcutKit.EXTRA_SPEC, step.toJson().toString())
                    .putExtra(dev.ii.tools.routines.ShortcutKit.EXTRA_TOKEN, dev.ii.tools.routines.ShortcutKit.token(ctx))
                setResult(RESULT_OK, Intent()
                    .putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch)
                    .putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                    .putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(ctx, dev.ii.tools.ui.stepIcon(ctx, step))))
                finish()
            }
        }
        dev.ii.tools.ui.ToolScaffold(title = getString(R.string.shortcut_builder), onBack = { setResult(RESULT_CANCELED); finish() }) {
            item { dev.ii.tools.ui.Hint(getString(R.string.shortcut_builder_body)) }
        }
        dev.ii.tools.ui.StepEditorSheet(onDone = { chosen = it }, onDismiss = { setResult(RESULT_CANCELED); finish() }, allowEnd = false)
    }
}

class RoutinesActivity : ScreenActivity() {
    @Composable
    override fun Screen() = RoutinesScreen(onBack = ::finish)
}

class CallsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = CallsScreen(onBack = ::finish)
}

class NotifsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = NotifsScreen(onBack = ::finish)
}

/** "Send to PC" in the share sheet. */
class ShareActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ShareScreen(
        shared = Shared.from(intent),
        // From the share sheet's top row: a file goes straight to Downloads, no questions.
        direct = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) == dev.ii.tools.routines.ShortcutKit.SHARE_ID,
        onBack = ::finish,
        onDone = ::finish,
    )
}

class ClipboardActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ClipboardScreen(onBack = ::finish)
}

class ShotsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ShotsScreen(onBack = ::finish)
}

class GalleryActivity : ScreenActivity() {
    @Composable
    override fun Screen() = GalleryScreen(onBack = ::finish)
}

class PhoneDataActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PhoneDataScreen(onBack = ::finish)
}

class CalendarActivity : ScreenActivity() {
    @Composable
    override fun Screen() = CalendarScreen(onBack = ::finish)
}

class PcLinkActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PcLinkScreen(onBack = ::finish)
}

/** The drawing pad: held sideways, like the screen it stands for. */
class DrawActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.DrawScreen(onBack = ::finish)
}

/** The slide clicker, held sideways: the volume keys change slides while it is open and the PC listens. */
class PresenterActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.PresenterScreen(onBack = ::finish)

    private fun slideKey(keyCode: Int): String? {
        val key = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "Right"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "Left"
            else -> return null
        }
        val live = dev.ii.tools.link.LinkHub.status.value is dev.ii.tools.link.LinkStatus.Online &&
            dev.ii.tools.link.LinkHub.shell.value?.caps?.contains("present") == true &&
            dev.ii.tools.present.Presenter.problem.value == null
        return if (live) key else null
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val key = slideKey(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) dev.ii.tools.present.Presenter.key(key)
        return true
    }

    // Swallowed on the way up too, or the system shows its volume panel.
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        slideKey(keyCode) != null || super.onKeyUp(keyCode, event)
}

/** The touchpad; turning sideways resizes the PC's touchpad instead of rebuilding. */
class TouchpadActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.TouchpadScreen(onBack = ::finish)
}

/** The performance panel; turning sideways keeps it, instead of rebuilding it. */
class PerfActivity : ScreenActivity() {
    @Composable
    override fun Screen() = PerfScreen(onBack = ::finish)
}

/** Every tool, grouped. */
class ToolsActivity : ScreenActivity() {
    @Composable
    override fun Screen() = ToolsScreen(onBack = ::finish, onOpen = ::open)
}

/** In-app update notice, release notes and direct APK download. */
class UpdateActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.ii.tools.update.AppUpdate.cancelNotification(this)
    }

    @Composable
    override fun Screen() = UpdateScreen(onBack = ::finish)
}

/** The webcam, held sideways like the camera it drives. The "start" notification opens it to go live. */
class CameraActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeStart(intent)
    }

    private fun takeStart(intent: Intent?) {
        if (intent?.getBooleanExtra(dev.ii.tools.camera.Webcam.EXTRA_START, false) != true) return
        intent.removeExtra(dev.ii.tools.camera.Webcam.EXTRA_START)
        dev.ii.tools.camera.Webcam.requestStart()
    }

    @Composable
    override fun Screen() = CameraScreen(onBack = ::finish)
}

/** The microphone. The "start" notification opens it to go live. */
class MicActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeStart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeStart(intent)
    }

    private fun takeStart(intent: Intent?) {
        if (intent?.getBooleanExtra(dev.ii.tools.mic.Mic.EXTRA_START, false) != true) return
        intent.removeExtra(dev.ii.tools.mic.Mic.EXTRA_START)
        dev.ii.tools.mic.Mic.pendingStart = true
    }

    @Composable
    override fun Screen() = dev.ii.tools.ui.MicScreen(onBack = ::finish)
}

/** The phone as a monitor: extend or mirror, over USB. */
class SecondScreenActivity : ScreenActivity() {
    @Composable
    override fun Screen() = dev.ii.tools.ui.SecondScreenScreen(onBack = ::finish)
}

/** The PC's output itself: full screen, oriented to the picture; leaving it ends the session. */
class SecondScreenViewActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (dev.ii.tools.screen.SecondScreen.state.value as? dev.ii.tools.screen.ScreenState.Ready)?.let { r ->
            requestedOrientation = if (r.height > r.width) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    @Composable
    override fun Screen() = dev.ii.tools.ui.SecondScreenView(onDone = ::finish)

    // Gone from view is gone: the PC would keep an output nobody sees.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}

/** Full-screen landscape Media Mode with synced lyrics from the PC. */
class MediaModeActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()
    }

    override fun onStart() {
        super.onStart()
        PcLyrics.watch(true)
    }

    override fun onStop() {
        super.onStop()
        PcLyrics.watch(false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Composable
    override fun Screen() = MediaModeScreen(onDone = ::finish)
}
