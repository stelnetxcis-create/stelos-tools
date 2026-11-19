package dev.ii.tools.deck

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import androidx.core.graphics.drawable.toBitmap
import dev.ii.tools.DeckActivity
import dev.ii.tools.R
import dev.ii.tools.link.AppIcons
import dev.ii.tools.link.LinkHub
import dev.ii.tools.link.Symbols
import dev.ii.tools.ui.bundledSymbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The first eight top-level deck keys on the home screen. Action taps send a broadcast
 * back here, which brings the link up just for it (LinkHub.runWhenReady) and lets it go;
 * folder taps open the deck directly inside that folder.
 */
class DeckWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = render(ctx)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return super.onReceive(ctx, intent)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: return
        val ref = intent.getStringExtra(EXTRA_REF) ?: return
        val pending = goAsync()
        scope.launch {
            try {
                when (kind) {
                    "mode" -> LinkHub.runWhenReady("mode", ref)
                    "preset" -> LinkHub.runWhenReady("preset", ref)
                    "workspace" -> ref.toIntOrNull()?.let { LinkHub.runWhenReady("workspace", it) }
                    "app" -> LinkHub.runWhenReady("deck", "app:$ref")
                    else -> LinkHub.runWhenReady("deck", ref)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_RUN = "dev.ii.tools.deck.RUN"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_REF = "ref"
        private const val MAX_KEYS = 8
        private const val ICON_PX = 72
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val KEY_IDS = intArrayOf(R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4, R.id.key5, R.id.key6, R.id.key7)
        private val ICON_IDS = intArrayOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3, R.id.icon4, R.id.icon5, R.id.icon6, R.id.icon7)
        private val LABEL_IDS = intArrayOf(R.id.label0, R.id.label1, R.id.label2, R.id.label3, R.id.label4, R.id.label5, R.id.label6, R.id.label7)

        /** Redraws every deck widget; the deck changed. */
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, DeckWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(ctx)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun render(ctx: Context): RemoteViews {
            DeckStore.init(ctx)
            Symbols.init(ctx)
            AppIcons.init(ctx)
            val keys = DeckStore.keys.value.take(MAX_KEYS)
            val views = RemoteViews(ctx.packageName, R.layout.widget_deck)
            views.setViewVisibility(R.id.widget_empty, if (keys.isEmpty()) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(
                R.id.widget_empty,
                PendingIntent.getActivity(ctx, 0, Intent(ctx, DeckActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            KEY_IDS.forEachIndexed { i, keyId ->
                val key = keys.getOrNull(i)
                // An empty cell keeps its place, so the grid does not stretch.
                views.setViewVisibility(keyId, if (key == null) (if (keys.isEmpty()) View.GONE else View.INVISIBLE) else View.VISIBLE)
                if (key == null) return@forEachIndexed
                views.setTextViewText(LABEL_IDS[i], key.label)
                val (bmp, tinted) = icon(ctx, key)
                bmp?.let { views.setImageViewBitmap(ICON_IDS[i], it) }
                if (tinted) {
                    views.setColorStateList(ICON_IDS[i], "setImageTintList", R.color.widget_on_key)
                } else {
                    views.setColorStateList(ICON_IDS[i], "setImageTintList", null as ColorStateList?)
                }
                if (key.isFolder) {
                    val open = Intent(ctx, DeckActivity::class.java)
                        .putExtra(DeckActivity.EXTRA_FOLDER, key.ref)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    views.setOnClickPendingIntent(
                        keyId,
                        PendingIntent.getActivity(ctx, 100 + i, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                } else {
                    val run = Intent(ctx, DeckWidget::class.java)
                        .setAction(ACTION_RUN)
                        .putExtra(EXTRA_KIND, key.kind)
                        .putExtra(EXTRA_REF, key.ref)
                    views.setOnClickPendingIntent(
                        keyId,
                        PendingIntent.getBroadcast(ctx, i, run, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            return views
        }

        /** Returns `(bitmap, needsTint)`: app icons keep full colour; symbols/initials are white masks tinted by the layout. */
        private fun icon(ctx: Context, key: DeckKey): Pair<Bitmap?, Boolean> {
            if (key.kind == "app") {
                val iconName = if (key.icon.isEmpty() || key.icon == "apps") key.ref.removeSuffix(".desktop") else key.icon
                AppIcons.bitmapNow(iconName)?.let { bmp ->
                    val scaled = if (bmp.width == ICON_PX && bmp.height == ICON_PX) bmp else scaleFit(bmp, ICON_PX)
                    return scaled to false
                }
                return initialBitmap(key.label.take(1).uppercase()) to true
            }
            val symbol = when (key.kind) {
                "workspace" -> "grid_view"
                "folder" -> key.icon.ifEmpty { "folder" }
                else -> key.icon
            }
            val fallback = if (key.isFolder) R.drawable.rounded_folder_open else R.drawable.rounded_bolt
            return symbolBitmap(ctx, symbol, fallback) to true
        }

        private fun symbolBitmap(ctx: Context, symbol: String, @DrawableRes fallback: Int): Bitmap? {
            bundledSymbol(symbol)?.let { res ->
                return ContextCompat.getDrawable(ctx, res)?.toBitmap(ICON_PX, ICON_PX)
            }
            val d = Symbols.pathNow(symbol) ?: return ContextCompat.getDrawable(ctx, fallback)?.toBitmap(ICON_PX, ICON_PX)
            return runCatching {
                val path = PathParser.createPathFromPathData(d)
                path.transform(Matrix().apply { setScale(ICON_PX / 960f, ICON_PX / 960f) })
                Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
                }
            }.getOrNull()
        }

        private fun initialBitmap(letter: String): Bitmap =
            Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = ICON_PX * 0.72f
                    textAlign = Paint.Align.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
                val y = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(letter.ifEmpty { "?" }, ICON_PX / 2f, y, paint)
            }

        private fun scaleFit(src: Bitmap, box: Int): Bitmap {
            val scale = minOf(box.toFloat() / src.width.coerceAtLeast(1), box.toFloat() / src.height.coerceAtLeast(1))
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, w, h, true)
        }
    }
}
