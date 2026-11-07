package ru.vtext.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.SpeechEngine
import ru.vtext.app.tts.VoiceInfo

data class SettingsUiState(
    val voices: List<VoiceInfo> = emptyList(),
    val selected: String? = null,
    val engineState: EngineState = EngineState.INITIALIZING,
)

class SettingsViewModel(private val engine: SpeechEngine) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        engine.voices,
        engine.selectedVoice,
        engine.state,
    ) { voices, selected, engineState ->
        SettingsUiState(voices = voices, selected = selected, engineState = engineState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            voices = engine.voices.value,
            selected = engine.selectedVoice.value,
            engineState = engine.state.value,
        ),
    )

    fun selectVoice(name: String?) {
        engine.setVoice(name)
    }

    companion object {
        fun factory(engine: SpeechEngine) = viewModelFactory {
            initializer { SettingsViewModel(engine) }
        }
    }
}
