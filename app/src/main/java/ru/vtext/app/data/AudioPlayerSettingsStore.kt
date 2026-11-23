package ru.vtext.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Настройки плеера аудиокниг: на сколько секунд перематывают кнопки «назад» и «вперёд».
 * Значения хранятся в SharedPreferences и транслируются через StateFlow.
 */
class AudioPlayerSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("audio_player", Context.MODE_PRIVATE)

    private val _backSkipMs = MutableStateFlow(
        prefs.getInt(KEY_BACK, DEFAULT_BACK).takeIf { it in SKIP_OPTIONS } ?: DEFAULT_BACK,
    )
    val backSkipMs: StateFlow<Int> = _backSkipMs.asStateFlow()

    private val _forwardSkipMs = MutableStateFlow(
        prefs.getInt(KEY_FORWARD, DEFAULT_FORWARD).takeIf { it in SKIP_OPTIONS } ?: DEFAULT_FORWARD,
    )
    val forwardSkipMs: StateFlow<Int> = _forwardSkipMs.asStateFlow()

    fun setBackSkip(ms: Int) {
        if (ms !in SKIP_OPTIONS) return
        prefs.edit().putInt(KEY_BACK, ms).apply()
        _backSkipMs.value = ms
    }

    fun setForwardSkip(ms: Int) {
        if (ms !in SKIP_OPTIONS) return
        prefs.edit().putInt(KEY_FORWARD, ms).apply()
        _forwardSkipMs.value = ms
    }

    companion object {
        val SKIP_OPTIONS = listOf(15_000, 30_000, 60_000)
        const val DEFAULT_BACK = 15_000
        const val DEFAULT_FORWARD = 30_000
        private const val KEY_BACK = "skip_back_ms"
        private const val KEY_FORWARD = "skip_forward_ms"
    }
}
