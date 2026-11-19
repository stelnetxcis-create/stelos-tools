package dev.ii.tools.camera

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The camera's live picture, at the stream's own size (so it shows exactly what the PC
 * gets, framing included). Give it the stream's aspect ratio from [Webcam.status]
 * (`width`/`height`). A tap focuses and meters there. While it is on screen the camera
 * runs; once it leaves, the camera closes unless the webcam is streaming.
 */
@Composable
fun WebcamPreview(modifier: Modifier = Modifier) {
    val settings by Webcam.settings.collectAsStateWithLifecycle()
    val caps by Webcam.caps.collectAsStateWithLifecycle()
    val holder = remember { PreviewHolder() }
    // A new lens, aspect or quality is a new size: the surface is resized, then attached again.
    val size = remember(settings.lens, settings.aspect, settings.quality, caps) { Webcam.previewSize() }
    DisposableEffect(holder) { onDispose { holder.detach() } }
    AndroidView(
        factory = { ctx -> SurfaceView(ctx).also { holder.bind(it) } },
        update = { holder.resize(size) },
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { p ->
                val rotation = (holder.view?.display?.rotation ?: Surface.ROTATION_90) * 90
                val area = this.size
                Webcam.focusAt(p.x / area.width.coerceAtLeast(1), p.y / area.height.coerceAtLeast(1), rotation)
            }
        },
    )
}

private class PreviewHolder : SurfaceHolder.Callback {
    var view: SurfaceView? = null
    private var want: android.util.Size? = null
    private var attached = false

    fun bind(v: SurfaceView) {
        view = v
        v.holder.addCallback(this)
    }

    fun resize(size: android.util.Size?) {
        if (size == want) return
        want = size
        if (attached) detach()
        size?.let { view?.holder?.setFixedSize(it.width, it.height) }
    }

    fun detach() {
        if (!attached) return
        attached = false
        Webcam.detachPreview()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val size = want ?: return
        // Only a surface of the stream's size: the camera gives one size to both outputs.
        if (width != size.width || height != size.height) return
        if (attached) detach()
        attached = true
        Webcam.attachPreview(holder.surface, size)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
}
