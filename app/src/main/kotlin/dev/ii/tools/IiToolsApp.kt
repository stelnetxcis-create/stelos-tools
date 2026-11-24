package dev.ii.tools

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.ii.tools.link.LinkHub

class IiToolsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LinkHub.init(this)
        dev.ii.tools.audio.VolumeKeys.init(this)
        dev.ii.tools.media.PcLyrics.init(this)
        dev.ii.tools.update.AppUpdate.init(this)
        // Send to PC in the share sheet's top row; one binder call, off the main thread.
        Thread { dev.ii.tools.routines.ShortcutKit.publish(this) }.start()
        // The widget picker's previews, once per version.
        Thread { dev.ii.tools.widgets.WidgetPreviews.publishIfNew(this) }.start()
        dev.ii.tools.camera.Webcam.init(this)
        dev.ii.tools.mic.Mic.init(this)
        dev.ii.tools.shell.ShellAccess.init(this)
        // The link follows the app as a whole, not one screen: moving between activities
        // must not drop it, leaving the app (after LinkHub's grace) does.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                LinkHub.onForeground()
                dev.ii.tools.update.AppUpdate.checkIfDue(this@IiToolsApp)
            }
            override fun onStop(owner: LifecycleOwner) = LinkHub.onBackground()
        })
    }

    /**
     * The app left the screen (or memory runs short): what only a screen shows goes, so
     * the process the link keeps alive holds no decoded images. The PC wallpaper behind
     * the home card lets go by itself (Look) when no screen collects it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            dev.ii.tools.link.AppIcons.trim()
            dev.ii.tools.clip.ClipHistory.trim()
            dev.ii.tools.live.LiveHub.listener?.trim()
        }
    }
}
