package ru.vtext.app.tts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

enum class EngineState { INITIALIZING, READY, ERROR }

enum class VoiceGender { FEMALE, MALE, UNKNOWN }

data class VoiceInfo(
    val name: String,
    val localeTag: String,
    val displayName: String,
    val requiresNetwork: Boolean,
    val gender: VoiceGender = VoiceGender.UNKNOWN,
)

sealed interface SpeechEvent {
    data class Started(val utteranceId: String) : SpeechEvent
    data class Done(val utteranceId: String) : SpeechEvent
    data class Error(val utteranceId: String?, val message: String?) : SpeechEvent
    data class Stopped(val utteranceId: String?) : SpeechEvent

    /** Текущее слово фрагмента (порядковый номер слова в тексте) для подсветки. */
    data class Word(val utteranceId: String, val index: Int) : SpeechEvent
}

/** Изоляция платформенного TTS: движок можно заменить или замокать в тестах. */
interface SpeechEngine {
    val state: StateFlow<EngineState>
    val events: SharedFlow<SpeechEvent>
    val voices: StateFlow<List<VoiceInfo>>
    val selectedVoice: StateFlow<String?>

    /** Сигнал о смене движка: текущий фрагмент нужно озвучить заново. */
    val engineChanges: Flow<Unit> get() = emptyFlow()

    fun setRate(rate: Float)
    fun setPitch(pitch: Float) {}
    fun setVolume(volume: Float) {}
    fun setVoice(name: String?)
    fun speak(text: String, utteranceId: String)

    /** Синтезирует следующий фрагмент заранее, пока звучит текущий. */
    fun prefetch(text: String, utteranceId: String) {}

    fun stop()
    fun shutdown()
}
