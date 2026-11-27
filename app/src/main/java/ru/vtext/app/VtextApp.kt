package ru.vtext.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.vtext.app.domain.parser.PdfBoxSetup
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

    /** Фоновые задачи уровня приложения: синхронизация облачной библиотеки. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PdfBoxSetup.attach(this)
        container = AppContainer(this)
        appScope.launch {
            // Одноразовая уборка встроенных книг прежних версий: не держим
            // главный поток на старте.
            runCatching { container.audioRepository.removeLegacySeededBooks { bookId ->
                container.progressStore.clear(bookId)
                container.audioProgress.clear(bookId)
                container.audioPlayback.clearIfBook(bookId)
            } }
            runCatching { container.cloudLibrary.syncIfConnected() }
            // Пока пользователь смотрит библиотеку, разбираем отложенные книги,
            // чтобы первое открытие PDF было быстрым.
            kotlinx.coroutines.delay(3_000)
            runCatching { container.repository.warmUpDeferredBooks() }
        }
    }
}

class AppContainer(application: Application) {
    val repository = BookRepository(application)
    val progressStore = ProgressStore(application)
    val updates = ru.vtext.app.data.UpdateRepository(application)
    val appearance = ru.vtext.app.data.AppearanceSettingsStore(application)
    val readerSettings = ru.vtext.app.data.ReaderSettingsStore(application)
    val ttsSettings = TtsSettingsStore(application)
    val playbackSettings = PlaybackSettingsStore(application)
    val bookSettings = ru.vtext.app.data.BookSettingsStore(application)
    val bookmarks = ru.vtext.app.data.BookmarkStore(application)
    val annotations = ru.vtext.app.data.AnnotationStore(application)
    val bookFlags = ru.vtext.app.data.BookFlagsStore(application)

    // Тяжёлые части поднимаем по требованию: TTS-движок создаёт TextToSpeech,
    // аудиоплеер — ExoPlayer, а на холодном старте они не нужны.
    val ttsManager: TtsManager by lazy { TtsManager(application, ttsSettings, playbackSettings) }
    val speechEngine: SpeechEngine by lazy { ttsManager }
    val playback: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            application,
            progressStore,
            playbackSettings,
            speechEngine,
            bookSettings,
            bookmarks,
            ttsManager,
        )
    }

    // Аудиокниги: отдельный плеер, чтобы не смешивать время с текстовыми фрагментами.
    val yandexDisk: ru.vtext.app.data.YandexDiskClient by lazy { ru.vtext.app.data.YandexDiskClient() }
    val audioRepository: AudioRepository by lazy {
        AudioRepository(application, repository, yandexDisk)
    }
    val audioProgress = AudioProgressStore(application)
    val audioPlayerSettings = ru.vtext.app.data.AudioPlayerSettingsStore(application)
    val audioBookmarks = AudioBookmarkStore(application)
    val audioPlayback: AudioPlaybackCoordinator by lazy {
        AudioPlaybackCoordinator(
            application,
            repository,
            audioRepository,
            audioProgress,
            playbackSettings,
            audioBookmarks,
            yandexDisk,
        )
    }
    val cloudLibrary: ru.vtext.app.data.CloudLibraryRepository by lazy {
        ru.vtext.app.data.CloudLibraryRepository(
            application,
            repository,
            audioRepository,
            yandexDisk,
        ) { bookId ->
            // Книги отключённой библиотеки удалены — убираем и их следы.
            progressStore.clear(bookId)
            audioProgress.clear(bookId)
            audioPlayback.clearIfBook(bookId)
        }
    }
}
