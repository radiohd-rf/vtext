package ru.vtext.app.player.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ru.vtext.app.MainActivity
import ru.vtext.app.VtextApp

/**
 * Фоновое воспроизведение аудиокниг.
 *
 * MediaSessionService сам строит уведомление и связывает его с системой
 * (наушники, Bluetooth, экран блокировки). Плеер общий с координатором —
 * сервис только публикует сессию.
 */
class AudioPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val coordinator = (application as VtextApp).container.audioPlayback
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, coordinator.player)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Как и в TTS-плеере: при закрытии задачи останавливаем воспроизведение,
        // чтобы не оставлять невидимый сервис в уведомлении.
        val coordinator = (application as VtextApp).container.audioPlayback
        coordinator.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
