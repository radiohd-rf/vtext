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

    /** Выбранный движок озвучивания: системный или Silero. */
    var engineType: TtsEngineType
        get() = TtsEngineType.fromId(prefs.getString(KEY_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value.id).apply()
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
        const val KEY_SILERO_VOICE = "silero_voice"
    }
}
