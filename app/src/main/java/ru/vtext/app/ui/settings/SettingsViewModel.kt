package ru.vtext.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.BuildConfig
import ru.vtext.app.data.AppearanceSettingsStore
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.UpdateInfo
import ru.vtext.app.data.UpdateRepository
import ru.vtext.app.player.PlaybackCoordinator
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.ModelStatus
import ru.vtext.app.tts.NeuralModel
import ru.vtext.app.tts.NeuralSettings
import ru.vtext.app.tts.TtsEngineType
import ru.vtext.app.tts.TtsManager
import ru.vtext.app.tts.VoiceInfo

/** Состояние проверки обновления из GitHub Releases. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val version: String, val sizeBytes: Long) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Ready(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

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
    val dynamicColor: Boolean = false,
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
)

class SettingsViewModel(
    private val manager: TtsManager,
    private val playback: PlaybackCoordinator,
    private val playbackSettings: PlaybackSettingsStore,
    private val appearance: AppearanceSettingsStore,
    private val updates: UpdateRepository,
) : ViewModel() {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var pendingUpdate: UpdateInfo? = null
    private var downloadedApk: File? = null

    private val tuning = MutableStateFlow(
        VoiceTuning(
            pitch = playbackSettings.pitch,
            volume = playbackSettings.volume,
            fragmentPauseMs = playbackSettings.fragmentPauseMs,
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
        appearance.dynamicColor,
    ) { neural, engineState, system, voiceTuning, dynamicColor ->
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
            dynamicColor = dynamicColor,
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
            dynamicColor = appearance.dynamicColor.value,
        ),
    )

    fun selectEngine(type: TtsEngineType) = manager.selectEngine(type)

    fun selectSystemVoice(name: String?) {
        playback.pause()
        manager.setVoice(name)
        manager.previewCurrentVoice()
    }

    fun selectSileroVoice(id: String) {
        playback.pause()
        manager.selectSileroVoice(id)
        if (manager.neuralSettings.value.stateOf(NeuralModel.DEFAULT_SILERO)?.status == ModelStatus.READY) {
            manager.previewCurrentVoice()
        }
    }

    fun cancelDownload() = manager.cancelDownload()

    fun importModel(id: String, uri: android.net.Uri) = manager.importModel(id, uri)

    fun importSileroDictionary(uri: android.net.Uri) = manager.importSileroDictionary(uri)

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

    /** Проигрывает пример текущего голоса с учётом свежих настроек тембра/громкости. */
    fun previewCurrentVoice() {
        playback.pause()
        manager.previewCurrentVoice()
    }

    fun setDynamicColor(enabled: Boolean) = appearance.setDynamicColor(enabled)

    // ----- Обновления -----

    fun checkUpdates() {
        val current = _updateState.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            runCatching { updates.latest() }
                .onSuccess { info ->
                    if (info != null && UpdateRepository.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                        pendingUpdate = info
                        _updateState.value = UpdateState.Available(info.version, info.sizeBytes)
                    } else {
                        pendingUpdate = null
                        _updateState.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                    }
                }
                .onFailure { error ->
                    _updateState.value = UpdateState.Failed(
                        error.message ?: "Не удалось проверить обновления",
                    )
                }
        }
    }

    fun downloadUpdate() {
        val info = pendingUpdate ?: return
        if (_updateState.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0f)
            runCatching {
                updates.download(info) { progress ->
                    _updateState.value = UpdateState.Downloading(progress)
                }
            }
                .onSuccess { file ->
                    downloadedApk = file
                    _updateState.value = UpdateState.Ready(info.version)
                }
                .onFailure { error ->
                    _updateState.value = UpdateState.Failed(
                        error.message ?: "Не удалось скачать обновление",
                    )
                }
        }
    }

    fun downloadedUpdate(): File? = downloadedApk

    companion object {
        fun factory(
            manager: TtsManager,
            playback: PlaybackCoordinator,
            playbackSettings: PlaybackSettingsStore,
            appearance: AppearanceSettingsStore,
            updates: UpdateRepository,
        ) = viewModelFactory {
            initializer { SettingsViewModel(manager, playback, playbackSettings, appearance, updates) }
        }
    }
}
