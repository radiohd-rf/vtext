package ru.vtext.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.data.TtsSettingsStore
import ru.vtext.app.player.PlaybackCoordinator
import ru.vtext.app.tts.AndroidSpeechEngine
import ru.vtext.app.tts.SpeechEngine

class VtextApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val repository = BookRepository(application)
    val progressStore = ProgressStore(application)
    val ttsSettings = TtsSettingsStore(application)
    val playbackSettings = PlaybackSettingsStore(application)
    val speechEngine: SpeechEngine = AndroidSpeechEngine(application, ttsSettings)
    val playback = PlaybackCoordinator(application, progressStore, playbackSettings, speechEngine)
}
