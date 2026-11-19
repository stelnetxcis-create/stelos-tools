package dev.ii.tools.phonedata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import dev.ii.tools.link.LinkHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Launcher icons for the PC's Android Apps page (PROTOCOL.md, `launcherIcons`): drawn
 * by the system, so they match the launcher, instead of dug out of APKs over adb. An
 * adaptive icon goes full bleed (its safe zone filling the square, no mask): the PC
 * cuts it to the shape picked in its settings.
 */
object LauncherIcons {
    private const val SIZE = 192

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued = mutableSetOf<String>()
    private var app: Context? = null

    init {
        scope.launch {
            for (pkg in queue) {
                try {
                    send(pkg)
                } finally {
                    synchronized(queued) { queued.remove(pkg) }
                }
            }
        }
    }

    fun want(ctx: Context, pkgs: List<String>) {
        app = ctx.applicationContext
        for (pkg in pkgs) {
            if (synchronized(queued) { queued.add(pkg) }) queue.trySend(pkg)
        }
    }

    private suspend fun send(pkg: String) {
        val ctx = app ?: return
        val png = runCatching { render(ctx.packageManager.getApplicationIcon(pkg)) }.getOrNull() ?: return
        LinkHub.upload("$pkg.png", png.size.toLong(), { png.inputStream() }, "launcherIcon", "", waitForLink = false) { }
    }

    private fun render(d: Drawable): ByteArray {
        val bmp = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            // Layers are 108 units, of which the middle 72 show: 1.5 times the square.
            val inset = SIZE / 4
            listOfNotNull(d.background, d.foreground).forEach {
                it.setBounds(-inset, -inset, SIZE + inset, SIZE + inset)
                it.draw(canvas)
            }
        } else {
            d.setBounds(0, 0, SIZE, SIZE)
            d.draw(canvas)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
