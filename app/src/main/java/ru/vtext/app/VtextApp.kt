package ru.vtext.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import ru.vtext.app.data.AudioBookmarkStore
import ru.vtext.app.data.AudioProgressStore
import ru.vtext.app.data.AudioRepository
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.data.TtsSettingsStore
import ru.vtext.app.player.PlaybackCoordinator
import ru.vtext.app.player.audio.AudioPlaybackCoordinator
import ru.vtext.app.tts.SpeechEngine
import ru.vtext.app.tts.TtsManager

class VtextApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        seedDefaultBooks()
    }

    /** Добавляет встроенные онлайн-книги, которых ещё нет в библиотеке. */
    private fun seedDefaultBooks() {
        runCatching {
            runBlocking { container.audioRepository.seedDefaultOnlineBooks() }
        }
    }
}

class AppContainer(application: Application) {
    val repository = BookRepository(application)
    val progressStore = ProgressStore(application)
    val updates = ru.vtext.app.data.UpdateRepository(application)
    val appearance = ru.vtext.app.data.AppearanceSettingsStore(application)
    val ttsSettings = TtsSettingsStore(application)
    val playbackSettings = PlaybackSettingsStore(application)
    val bookSettings = ru.vtext.app.data.BookSettingsStore(application)
    val bookmarks = ru.vtext.app.data.BookmarkStore(application)
    val ttsManager = TtsManager(application, ttsSettings, playbackSettings)
    val speechEngine: SpeechEngine = ttsManager
    val playback = PlaybackCoordinator(
        application,
        progressStore,
        playbackSettings,
        speechEngine,
        bookSettings,
        bookmarks,
        ttsManager,
    )

    // Аудиокниги: отдельный плеер, чтобы не смешивать время с текстовыми фрагментами.
    val yandexDisk = ru.vtext.app.data.YandexDiskClient()
    val audioRepository = AudioRepository(application, repository, yandexDisk)
    val audioProgress = AudioProgressStore(application)
    val audioBookmarks = AudioBookmarkStore(application)
    val audioPlayback = AudioPlaybackCoordinator(
        application,
        repository,
        audioRepository,
        audioProgress,
        playbackSettings,
        audioBookmarks,
        yandexDisk,
    )
}
