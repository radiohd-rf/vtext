package ru.vtext.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.vtext.app.data.TtsSettingsStore

class AndroidSpeechEngine(
    context: Context,
    private val settings: TtsSettingsStore,
) : SpeechEngine {

    private val _state = MutableStateFlow(EngineState.INITIALIZING)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    override val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()

    private val _selectedVoice = MutableStateFlow<String?>(null)
    override val selectedVoice: StateFlow<String?> = _selectedVoice.asStateFlow()

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false
    private var rate = 1f
    private var pitch = 1f
    private var volume = 1f

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                applyLanguage(engine)
                engine.setSpeechRate(rate)
                engine.setPitch(pitch)
                engine.setOnUtteranceProgressListener(progressListener())
                ready = true
                publishVoices(engine)
                applySavedVoice(engine)
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

    override fun setPitch(pitch: Float) {
        this.pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        tts?.setPitch(this.pitch)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
    }

    override fun setVoice(name: String?) {
        settings.voiceName = name
        val engine = tts ?: return
        if (name.isNullOrBlank()) {
            applyDefaultRussianVoice(engine)
            return
        }
        val voice = engine.voices
            ?.firstOrNull { it.name == name && it.locale.language.equals("ru", ignoreCase = true) }
        if (voice != null) {
            engine.voice = voice
            _selectedVoice.value = voice.name
        } else {
            applyDefaultRussianVoice(engine)
        }
    }

    override fun speak(text: String, utteranceId: String) {
        if (!ready || text.isBlank()) return
        val spoken = ru.vtext.app.domain.TextMarkup.strip(text)
        tts?.setSpeechRate(rate)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        ready = false
        tts?.shutdown()
        tts = null
    }

    private fun applyLanguage(engine: TextToSpeech) {
        // Приложение только для русского языка: явно просим русский голос,
        // не полагаясь на локаль устройства.
        val result = engine.setLanguage(RUSSIAN)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    private fun publishVoices(engine: TextToSpeech) {
        val voices = engine.voices
            ?.filter { it.locale.language.equals("ru", ignoreCase = true) }
            ?.map { voice -> voice.toInfo() }
            // Один и тот же голос может прийти из нескольких движков — не дублируем.
            ?.distinctBy { it.name }
            // Сначала офлайн-голоса, потом сетевые; внутри — по техническому имени.
            ?.sortedWith(compareBy({ it.requiresNetwork }, { it.name }))
            ?: emptyList()

        // Android не даёт голосам человеческих имён: у всех «Русский (Россия)».
        // Нумеруем их, иначе список выглядит как одинаковые строки.
        val totals = voices.groupingBy { it.localeTag }.eachCount()
        val seen = mutableMapOf<String, Int>()
        _voices.value = voices.map { voice ->
            val index = (seen[voice.localeTag] ?: 0) + 1
            seen[voice.localeTag] = index
            if ((totals[voice.localeTag] ?: 1) > 1) {
                voice.copy(displayName = "${voice.displayName} · $index")
            } else {
                voice
            }
        }
    }

    private fun applySavedVoice(engine: TextToSpeech) {
        val saved = settings.voiceName
        val voice = saved?.let { name ->
            engine.voices?.firstOrNull {
                it.name == name && it.locale.language.equals("ru", ignoreCase = true)
            }
        }
        if (voice != null) {
            engine.voice = voice
            _selectedVoice.value = voice.name
        } else {
            applyDefaultRussianVoice(engine)
        }
    }

    /** Голос по умолчанию среди русских: предпочитаем офлайн-голос. */
    private fun applyDefaultRussianVoice(engine: TextToSpeech) {
        val russianVoices = engine.voices
            ?.filter { it.locale.language.equals("ru", ignoreCase = true) }
            .orEmpty()
        val voice = russianVoices.firstOrNull { !it.isNetworkConnectionRequired }
            ?: russianVoices.firstOrNull()
        if (voice != null) {
            engine.voice = voice
        } else {
            engine.defaultVoice?.let { engine.voice = it }
        }
        _selectedVoice.value = null
    }

    private fun Voice.toInfo(): VoiceInfo = VoiceInfo(
        name = name,
        localeTag = locale.toLanguageTag(),
        displayName = locale.displayName.replaceFirstChar { it.uppercase() },
        requiresNetwork = isNetworkConnectionRequired,
        gender = inferGender(),
    )

    /** Не все движки сообщают пол голоса: используем features и имя как подсказку. */
    private fun Voice.inferGender(): VoiceGender {
        val hints = (features.orEmpty() + name).joinToString(" ").lowercase()
        return when {
            "female" in hints || "woman" in hints -> VoiceGender.FEMALE
            "male" in hints || "man" in hints -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }

    private fun progressListener() = object : UtteranceProgressListener() {
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
            _events.tryEmit(SpeechEvent.Error(utteranceId, "Ошибка синтеза речи ($errorCode)"))
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            _events.tryEmit(SpeechEvent.Stopped(utteranceId))
        }
    }

    private companion object {
        val RUSSIAN: Locale = Locale("ru", "RU")
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2f
        const val MIN_VOLUME = 0f
        const val MAX_VOLUME = 1f
    }
}
