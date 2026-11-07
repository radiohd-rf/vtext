package ru.vtext.app

import android.app.Application
import ru.vtext.app.data.BookRepository
import ru.vtext.app.data.ProgressStore
import ru.vtext.app.tts.AndroidSpeechEngine
import ru.vtext.app.tts.SpeechEngine

class VtextApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val repository = BookRepository(application)
    val progressStore = ProgressStore(application)
    val speechEngine: SpeechEngine = AndroidSpeechEngine(application)
}
