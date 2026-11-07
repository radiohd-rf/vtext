package ru.vtext.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechEngine(context: Context) : SpeechEngine {

    private val _state = MutableStateFlow(EngineState.INITIALIZING)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false
    private var rate = 1f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val locale = Locale.getDefault()
                val result = engine.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.ENGLISH)
                }
                engine.setSpeechRate(rate)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { _events.tryEmit(SpeechEvent.Started(it)) }
                    }

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { _events.tryEmit(SpeechEvent.Done(it)) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _events.tryEmit(SpeechEvent.Error(utteranceId, null))
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _events.tryEmit(SpeechEvent.Error(utteranceId, "TTS error $errorCode"))
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        _events.tryEmit(SpeechEvent.Stopped(utteranceId))
                    }
                })
                ready = true
                _state.value = EngineState.READY
            } else {
                ready = false
                _state.value = EngineState.ERROR
            }
        }
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        tts?.setSpeechRate(rate)
    }

    override fun speak(text: String, utteranceId: String) {
        if (!ready || text.isBlank()) return
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        ready = false
        tts?.shutdown()
        tts = null
    }
}
