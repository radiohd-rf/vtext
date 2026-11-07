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
import ru.vtext.app.tts.NeuralSettings
import ru.vtext.app.tts.TtsEngineType
import ru.vtext.app.tts.TtsManager
import ru.vtext.app.tts.VoiceInfo

data class SettingsUiState(
    val engineType: TtsEngineType = TtsEngineType.SYSTEM,
    val engineState: EngineState = EngineState.INITIALIZING,
    val systemEngineState: EngineState = EngineState.INITIALIZING,
    val systemVoices: List<VoiceInfo> = emptyList(),
    val systemSelectedVoice: String? = null,
    val neural: NeuralSettings = NeuralSettings(),
)

class SettingsViewModel(private val manager: TtsManager) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        manager.neuralSettings,
        manager.systemEngine.voices,
        manager.systemEngine.selectedVoice,
        manager.systemEngine.state,
        manager.state,
    ) { neural, voices, selected, systemState, engineState ->
        SettingsUiState(
            engineType = neural.engineType,
            engineState = engineState,
            systemEngineState = systemState,
            systemVoices = voices,
            systemSelectedVoice = selected,
            neural = neural,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            engineType = manager.engineType.value,
            engineState = manager.state.value,
            systemEngineState = manager.systemEngine.state.value,
            systemVoices = manager.systemEngine.voices.value,
            systemSelectedVoice = manager.systemEngine.selectedVoice.value,
            neural = manager.neuralSettings.value,
        ),
    )

    fun selectEngine(type: TtsEngineType) = manager.selectEngine(type)

    fun selectSystemVoice(name: String?) = manager.setVoice(name)

    fun selectPiperModel(id: String) = manager.selectPiperModel(id)

    fun selectSupertonicSpeaker(speaker: Int) = manager.selectSupertonicSpeaker(speaker)

    fun downloadModel(id: String) = manager.downloadModel(id)

    fun cancelDownload() = manager.cancelDownload()

    companion object {
        fun factory(manager: TtsManager) = viewModelFactory {
            initializer { SettingsViewModel(manager) }
        }
    }
}
