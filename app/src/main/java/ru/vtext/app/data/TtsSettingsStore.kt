package ru.vtext.app.data

import android.content.Context

/** Пользовательский выбор голоса системного TTS. */
class TtsSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)

    var voiceName: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_VOICE) else editor.putString(KEY_VOICE, value)
            editor.apply()
        }

    private companion object {
        const val KEY_VOICE = "voice_name"
    }
}
