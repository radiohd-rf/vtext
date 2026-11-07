package ru.vtext.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.player.PlaybackCoordinator
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.ModelStatus
import ru.vtext.app.tts.NeuralModel
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
    val pitch: Float = PlaybackSettingsStore.DEFAULT_PITCH,
    val volume: Float = PlaybackSettingsStore.DEFAULT_VOLUME,
    val fragmentPauseMs: Int = PlaybackSettingsStore.DEFAULT_FRAGMENT_PAUSE_MS,
    val supertonicSteps: Int = PlaybackSettingsStore.DEFAULT_SUPERTONIC_STEPS,
)

private data class SystemVoicesState(
    val voices: List<VoiceInfo>,
    val selected: String?,
    val engineState: EngineState,
)

private data class VoiceTuning(
    val pitch: Float,
    val volume: Float,
    val fragmentPauseMs: Int,
    val supertonicSteps: Int,
)

class SettingsViewModel(
    private val manager: TtsManager,
    private val playback: PlaybackCoordinator,
    private val playbackSettings: PlaybackSettingsStore,
) : ViewModel() {

    private val tuning = MutableStateFlow(
        VoiceTuning(
            pitch = playbackSettings.pitch,
            volume = playbackSettings.volume,
            fragmentPauseMs = playbackSettings.fragmentPauseMs,
            supertonicSteps = playbackSettings.supertonicSteps,
        ),
    )

    private val systemVoices = combine(
        manager.systemEngine.voices,
        manager.systemEngine.selectedVoice,
        manager.systemEngine.state,
    ) { voices, selected, engineState -> SystemVoicesState(voices, selected, engineState) }

    val state: StateFlow<SettingsUiState> = combine(
        manager.neuralSettings,
        manager.state,
        systemVoices,
        tuning,
    ) { neural, engineState, system, voiceTuning ->
        SettingsUiState(
            engineType = neural.engineType,
            engineState = engineState,
            systemEngineState = system.engineState,
            systemVoices = system.voices,
            systemSelectedVoice = system.selected,
            neural = neural,
            pitch = voiceTuning.pitch,
            volume = voiceTuning.volume,
            fragmentPauseMs = voiceTuning.fragmentPauseMs,
            supertonicSteps = voiceTuning.supertonicSteps,
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
            pitch = playbackSettings.pitch,
            volume = playbackSettings.volume,
            fragmentPauseMs = playbackSettings.fragmentPauseMs,
            supertonicSteps = playbackSettings.supertonicSteps,
        ),
    )

    fun selectEngine(type: TtsEngineType) = manager.selectEngine(type)

    fun selectSystemVoice(name: String?) {
        playback.pause()
        manager.setVoice(name)
        manager.previewCurrentVoice()
    }

    fun selectPiperModel(id: String) {
        playback.pause()
        manager.selectPiperModel(id)
        if (manager.neuralSettings.value.stateOf(id)?.status == ModelStatus.READY) {
            manager.previewCurrentVoice()
        }
    }

    fun selectSupertonicSpeaker(speaker: Int) {
        playback.pause()
        manager.selectSupertonicSpeaker(speaker)
        val model = NeuralModel.DEFAULT_SUPERTONIC
        if (manager.neuralSettings.value.stateOf(model)?.status == ModelStatus.READY) {
            manager.previewCurrentVoice()
        }
    }

    fun setVolume(value: Float) {
        val safe = value.coerceIn(PlaybackSettingsStore.MIN_VOLUME, PlaybackSettingsStore.MAX_VOLUME)
        playbackSettings.volume = safe
        manager.setVolume(safe)
        tuning.update { it.copy(volume = safe) }
    }

    fun setPitch(value: Float) {
        val safe = value.coerceIn(PlaybackSettingsStore.MIN_PITCH, PlaybackSettingsStore.MAX_PITCH)
        playbackSettings.pitch = safe
        manager.setPitch(safe)
        tuning.update { it.copy(pitch = safe) }
    }

    fun setFragmentPause(pauseMs: Int) {
        val safe = pauseMs.coerceIn(
            PlaybackSettingsStore.MIN_FRAGMENT_PAUSE_MS,
            PlaybackSettingsStore.MAX_FRAGMENT_PAUSE_MS,
        )
        playback.setFragmentPause(safe)
        tuning.update { it.copy(fragmentPauseMs = safe) }
    }

    fun setSupertonicSteps(steps: Int) {
        val safe = steps.coerceIn(
            PlaybackSettingsStore.MIN_SUPERTONIC_STEPS,
            PlaybackSettingsStore.MAX_SUPERTONIC_STEPS,
        )
        playbackSettings.supertonicSteps = safe
        manager.setSupertonicSteps(safe)
        tuning.update { it.copy(supertonicSteps = safe) }
    }

    /** Проигрывает пример текущего голоса с учётом свежих настроек тембра/громкости. */
    fun previewCurrentVoice() {
        playback.pause()
        manager.previewCurrentVoice()
    }

    fun downloadModel(id: String) = manager.downloadModel(id)

    fun importModel(id: String, uri: android.net.Uri) = manager.importModel(id, uri)

    fun selectSileroVoice(id: String) {
        playback.pause()
        manager.selectSileroVoice(id)
        if (manager.neuralSettings.value.stateOf(NeuralModel.DEFAULT_SILERO)?.status == ModelStatus.READY) {
            manager.previewCurrentVoice()
        }
    }

    fun cancelDownload() = manager.cancelDownload()

    companion object {
        fun factory(
            manager: TtsManager,
            playback: PlaybackCoordinator,
            playbackSettings: PlaybackSettingsStore,
        ) = viewModelFactory {
            initializer { SettingsViewModel(manager, playback, playbackSettings) }
        }
    }
}
