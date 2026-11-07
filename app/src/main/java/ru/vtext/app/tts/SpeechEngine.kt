package ru.vtext.app.tts

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class EngineState { INITIALIZING, READY, ERROR }

data class VoiceInfo(
    val name: String,
    val localeTag: String,
    val displayName: String,
    val requiresNetwork: Boolean,
)

sealed interface SpeechEvent {
    data class Started(val utteranceId: String) : SpeechEvent
    data class Done(val utteranceId: String) : SpeechEvent
    data class Error(val utteranceId: String?, val message: String?) : SpeechEvent
    data class Stopped(val utteranceId: String?) : SpeechEvent
}

/** Изоляция платформенного TTS: движок можно заменить или замокать в тестах. */
interface SpeechEngine {
    val state: StateFlow<EngineState>
    val events: SharedFlow<SpeechEvent>
    val voices: StateFlow<List<VoiceInfo>>
    val selectedVoice: StateFlow<String?>

    fun setRate(rate: Float)
    fun setVoice(name: String?)
    fun speak(text: String, utteranceId: String)
    fun stop()
    fun shutdown()
}
