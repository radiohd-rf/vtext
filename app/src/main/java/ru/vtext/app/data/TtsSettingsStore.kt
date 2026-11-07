package ru.vtext.app.data

import android.content.Context
import ru.vtext.app.tts.NeuralModel
import ru.vtext.app.tts.TtsEngineType

/** Пользовательский выбор движка и голоса TTS. */
class TtsSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)

    /** Имя голоса системного TTS. */
    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_VOICE) else editor.putString(KEY_VOICE, value)
            editor.apply()
        }

    /** Выбранный движок озвучивания: системный, Piper или Supertonic. */
    var engineType: TtsEngineType
        get() = TtsEngineType.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value.id).apply()
        }

    /** Выбранный голос Piper (отдельная модель на каждый голос). */
    var piperModelId: String
        get() = prefs.getString(KEY_PIPER_MODEL, null)
            ?.let { NeuralModel.byId(it) }
            ?.takeIf { it.engine == TtsEngineType.PIPER }
            ?.id
            ?: NeuralModel.DEFAULT_PIPER.id
        set(value) {
            prefs.edit().putString(KEY_PIPER_MODEL, value).apply()
        }

    /** Выбранный голос Supertonic 3 (sid от 0 до 9). */
    var supertonicSpeaker: Int
        get() = prefs.getInt(KEY_SUPERTONIC_SPEAKER, 0).coerceIn(0, SUPER_TONIC_SPEAKERS - 1)
        set(value) {
            prefs.edit().putInt(KEY_SUPERTONIC_SPEAKER, value).apply()
        }

    /** Выбранный голос Silero v5. */
    var sileroVoice: String
        get() = prefs.getString(KEY_SILERO_VOICE, null)
            ?.let { ru.vtext.app.tts.SileroVoice.fromId(it) }
            ?.id
            ?: ru.vtext.app.tts.SileroVoice.XENIA.id
        set(value) {
            prefs.edit().putString(KEY_SILERO_VOICE, value).apply()
        }

    private companion object {
        const val KEY_VOICE = "voice_name"
        const val KEY_ENGINE = "engine_type"
        const val KEY_PIPER_MODEL = "piper_model"
        const val KEY_SUPERTONIC_SPEAKER = "supertonic_speaker"
        const val KEY_SILERO_VOICE = "silero_voice"
        const val SUPER_TONIC_SPEAKERS = 10
    }
}
