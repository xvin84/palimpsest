package dev.xvin.palimpsest.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import android.widget.RemoteViews
import dev.xvin.palimpsest.MainActivity
import dev.xvin.palimpsest.R
import dev.xvin.palimpsest.playback.PlaybackService

/**
 * Home-screen widget: cover, title, artist and transport buttons. Buttons send media-button
 * intents straight to the playback service, which works from the background and resumes the
 * last queue when nothing is loaded.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, last)
    }

    data class State(val title: String?, val artist: String?, val playing: Boolean, val cover: Bitmap?)

    companion object {
        @Volatile private var last = State(null, null, false, null)

        fun update(context: Context, title: String?, artist: String?, playing: Boolean, artUri: Uri?, artData: ByteArray?) {
            val cover = runCatching {
                when {
                    artData != null -> decodeScaled(artData)
                    artUri != null -> context.contentResolver.openInputStream(artUri)?.use { decodeScaled(it.readBytes()) }
                    else -> null
                }
            }.getOrNull()
            last = State(title, artist, playing, cover)
            render(context, last)
        }

        fun setPlaying(context: Context, playing: Boolean) {
            if (last.playing == playing) return
            last = last.copy(playing = playing)
            render(context, last)
        }

        private fun decodeScaled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 192) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private fun render(context: Context, s: State) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(R.id.widget_title, s.title ?: "Palimpsest")
            v.setTextViewText(R.id.widget_artist, s.artist ?: "Нажми ▶, чтобы продолжить")
            if (s.cover != null) v.setImageViewBitmap(R.id.widget_cover, s.cover) else v.setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher_foreground)
            v.setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            v.setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
            v.setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, v)
        }

        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getForegroundService(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
