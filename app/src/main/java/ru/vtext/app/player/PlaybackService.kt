package ru.vtext.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.vtext.app.MainActivity
import ru.vtext.app.R
import ru.vtext.app.VtextApp

/**
 * Foreground-сервис воспроизведения: держит процесс живым, показывает уведомление
 * и отдаёт управление на экран блокировки/наушники через MediaSession.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var session: MediaSessionCompat

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        coordinator = (application as VtextApp).container.playback
        createChannel()
        session = MediaSessionCompat(this, SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = coordinator.play()
                override fun onPause() = coordinator.pause()
                override fun onSkipToNext() = coordinator.next()
                override fun onSkipToPrevious() = coordinator.previous()
                override fun onStop() = coordinator.stop()
                override fun onSeekTo(pos: Long) = coordinator.selectSegment(pos.toInt())
            })
            isActive = true
        }

        scope.launch {
            coordinator.state.collect { state ->
                if (state.bookId == null) {
                    stopPlayback()
                } else {
                    updateSession(state)
                    notify(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification(coordinator.state.value))
        when (intent?.action) {
            ACTION_TOGGLE -> coordinator.togglePlay()
            ACTION_NEXT -> coordinator.next()
            ACTION_PREVIOUS -> coordinator.previous()
            ACTION_STOP -> coordinator.stop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        isRunning = false
        super.onDestroy()
    }

    private fun stopPlayback() {
        if (stopping) return
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(state: PlayerState) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun updateSession(state: PlayerState) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.author)
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .setState(
                    if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    state.segmentIndex.toLong(),
                    state.speed,
                )
                .build(),
        )
    }

    private fun buildNotification(state: PlayerState): Notification {
        val chapter = state.chapter
        val text = if (chapter != null) {
            buildString {
                append(chapter.title)
                if (chapter.segments.isNotEmpty()) {
                    append(" · ")
                    append(state.segmentIndex + 1)
                    append('/')
                    append(chapter.segments.size)
                }
            }
        } else {
            getString(R.string.app_name)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_BOOK_ID, state.bookId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseAction = NotificationCompat.Action(
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (state.isPlaying) R.string.pause else R.string.play),
            serviceIntent(ACTION_TOGGLE, 1),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vtext)
            .setContentTitle(state.bookTitle ?: getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.previous),
                serviceIntent(ACTION_PREVIOUS, 2),
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.next),
                serviceIntent(ACTION_NEXT, 3),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_playback),
                serviceIntent(ACTION_STOP, 4),
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val SESSION_TAG = "vtext"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "ru.vtext.app.action.TOGGLE"
        private const val ACTION_NEXT = "ru.vtext.app.action.NEXT"
        private const val ACTION_PREVIOUS = "ru.vtext.app.action.PREVIOUS"
        private const val ACTION_STOP = "ru.vtext.app.action.STOP"

        const val EXTRA_BOOK_ID = "ru.vtext.app.extra.BOOK_ID"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java),
            )
        }
    }
}
