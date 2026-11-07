package ru.vtext.app.tts

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.data.TtsSettingsStore

enum class ModelStatus { NOT_INSTALLED, DOWNLOADING, EXTRACTING, READY, ERROR }

data class NeuralModelState(
    val model: NeuralModel,
    val status: ModelStatus = ModelStatus.NOT_INSTALLED,
    val progress: Float = 0f,
    val error: String? = null,
)

data class NeuralSettings(
    val engineType: TtsEngineType = TtsEngineType.SYSTEM,
    val models: List<NeuralModelState> = emptyList(),
    val piperModelId: String = NeuralModel.DEFAULT_PIPER.id,
    val supertonicSpeaker: Int = 0,
    val usingSystemFallback: Boolean = false,
) {
    fun stateOf(model: NeuralModel): NeuralModelState? = models.firstOrNull { it.model == model }
    fun stateOf(id: String): NeuralModelState? = models.firstOrNull { it.model.id == id }
}

/**
 * Выбирает между системным TTS и локальными нейродвижками, хранит состояние
 * загрузки моделей и транслирует единый интерфейс [SpeechEngine] для плеера.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsManager(
    context: Context,
    private val settings: TtsSettingsStore,
    private val playbackSettings: PlaybackSettingsStore,
) : SpeechEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = ModelRepository(appContext)

    val systemEngine: SpeechEngine = AndroidSpeechEngine(appContext, settings)

    private val _engineType = MutableStateFlow(settings.engineType)
    val engineType: StateFlow<TtsEngineType> = _engineType.asStateFlow()

    private val _models = MutableStateFlow(
        NeuralModel.entries.map { model ->
            NeuralModelState(
                model = model,
                status = if (repository.isInstalled(model)) ModelStatus.READY else ModelStatus.NOT_INSTALLED,
            )
        },
    )
    val models: StateFlow<List<NeuralModelState>> = _models.asStateFlow()

    private val _neuralSettings = MutableStateFlow(
        NeuralSettings(
            engineType = _engineType.value,
            models = _models.value,
            piperModelId = settings.piperModelId,
            supertonicSpeaker = settings.supertonicSpeaker,
        ),
    )
    val neuralSettings: StateFlow<NeuralSettings> = _neuralSettings.asStateFlow()

    private val _active = MutableStateFlow<SpeechEngine>(systemEngine)
    private val _engineChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val engineChanges: Flow<Unit> = _engineChanges.asSharedFlow()

    override val state: StateFlow<EngineState> = _active
        .flatMapLatest { it.state }
        .stateIn(scope, SharingStarted.Eagerly, _active.value.state.value)

    override val events: SharedFlow<SpeechEvent> = _active
        .flatMapLatest { it.events }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val voices: StateFlow<List<VoiceInfo>> = _active
        .flatMapLatest { it.voices }
        .stateIn(scope, SharingStarted.Eagerly, _active.value.voices.value)

    override val selectedVoice: StateFlow<String?> = _active
        .flatMapLatest { it.selectedVoice }
        .stateIn(scope, SharingStarted.Eagerly, _active.value.selectedVoice.value)

    private var neuralEngine: OnnxSpeechEngine? = null
    private var downloadJob: Job? = null
    private var previewJob: Job? = null
    private var rate = playbackSettings.speed
    private var volume = playbackSettings.volume
    private var supertonicSteps = playbackSettings.supertonicSteps

    init {
        systemEngine.setRate(rate)
        systemEngine.setPitch(playbackSettings.pitch)
        systemEngine.setVolume(volume)
        activate()
    }

    // ----- Управление выбором движка -----

    fun selectEngine(type: TtsEngineType) {
        if (_engineType.value == type) return
        settings.engineType = type
        _engineType.value = type
        switchActive()
    }

    fun selectPiperModel(id: String) {
        val model = NeuralModel.byId(id) ?: return
        if (model.engine != TtsEngineType.PIPER) return
        settings.piperModelId = model.id
        if (_engineType.value == TtsEngineType.PIPER) {
            switchActive()
        } else {
            publish()
        }
    }

    fun selectSupertonicSpeaker(speaker: Int) {
        val safe = speaker.coerceIn(0, 9)
        settings.supertonicSpeaker = safe
        neuralEngine?.setVoice(safe.toString())
        publish()
    }

    /**
     * Короткий пример текущего голоса. Если движок ещё загружается (например,
     * только что выбран другой голос Piper), пример прозвучит после готовности.
     */
    fun previewCurrentVoice() {
        previewJob?.cancel()
        val engine = _active.value
        previewJob = scope.launch {
            if (engine.state.value == EngineState.INITIALIZING) {
                engine.state.first { it != EngineState.INITIALIZING }
            }
            if (engine.state.value == EngineState.READY) {
                android.util.Log.i("vtext-tts", "voice preview: ${engine.javaClass.simpleName}")
                // Пример всегда звучит в естественном темпе, независимо от скорости книги.
                engine.setRate(1f)
                engine.speak(PREVIEW_TEXT, PREVIEW_UTTERANCE_ID)
            }
        }
    }

    fun downloadModel(id: String) {
        val model = NeuralModel.byId(id) ?: return
        if (downloadJob?.isActive == true) return
        if (repository.isInstalled(model)) {
            updateModel(model.id) { it.copy(status = ModelStatus.READY, progress = 1f, error = null) }
            switchActive()
            return
        }
        downloadJob = scope.launch {
            updateModel(model.id) {
                it.copy(status = ModelStatus.DOWNLOADING, progress = 0f, error = null)
            }
            try {
                repository.download(model) { progress ->
                    if (progress >= 1f) {
                        updateModel(model.id) {
                            it.copy(status = ModelStatus.EXTRACTING, progress = 1f)
                        }
                    } else {
                        updateModel(model.id) { it.copy(status = ModelStatus.DOWNLOADING, progress = progress) }
                    }
                }
                updateModel(model.id) {
                    it.copy(status = ModelStatus.READY, progress = 1f, error = null)
                }
                switchActive()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                updateModel(model.id) {
                    it.copy(status = ModelStatus.NOT_INSTALLED, progress = 0f, error = null)
                }
                throw cancel
            } catch (error: Throwable) {
                updateModel(model.id) {
                    it.copy(
                        status = ModelStatus.ERROR,
                        progress = 0f,
                        error = error.message ?: "Не удалось скачать модель",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _models.value.forEach { state ->
            if (state.status == ModelStatus.DOWNLOADING || state.status == ModelStatus.EXTRACTING) {
                updateModel(state.model.id) {
                    it.copy(status = ModelStatus.NOT_INSTALLED, progress = 0f, error = null)
                }
            }
        }
    }

    // ----- SpeechEngine -----

    override fun setRate(rate: Float) {
        this.rate = rate
        systemEngine.setRate(rate)
        neuralEngine?.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        // Тон поддерживает только системный TTS; у нейродвижков такого параметра нет.
        systemEngine.setPitch(pitch)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        systemEngine.setVolume(volume)
        neuralEngine?.setVolume(volume)
    }

    fun setSupertonicSteps(steps: Int) {
        supertonicSteps = steps
        neuralEngine?.setSupertonicSteps(steps)
    }

    override fun setVoice(name: String?) {
        if (_active.value === systemEngine) {
            systemEngine.setVoice(name)
        } else {
            neuralEngine?.setVoice(name)
        }
    }

    override fun speak(text: String, utteranceId: String) {
        previewJob?.cancel()
        _active.value.setRate(rate)
        _active.value.speak(text, utteranceId)
    }

    override fun prefetch(text: String, utteranceId: String) {
        _active.value.prefetch(text, utteranceId)
    }

    override fun stop() {
        previewJob?.cancel()
        _active.value.stop()
    }

    override fun shutdown() {
        downloadJob?.cancel()
        systemEngine.shutdown()
        neuralEngine?.shutdown()
        neuralEngine = null
    }

    // ----- Внутреннее -----

    private fun selectedNeuralModel(): NeuralModel? = when (_engineType.value) {
        TtsEngineType.SYSTEM -> null
        TtsEngineType.PIPER -> NeuralModel.byId(settings.piperModelId) ?: NeuralModel.DEFAULT_PIPER
        TtsEngineType.SUPERTONIC -> NeuralModel.DEFAULT_SUPERTONIC
    }

    private fun activate() {
        _active.value = resolveEngine()
        _active.value.setRate(rate)
        publish()
    }

    /** Переключение движка во время воспроизведения: плеер заново озвучит текущий фрагмент. */
    private fun switchActive() {
        previewJob?.cancel()
        val desired = resolveEngine()
        if (desired === _active.value) {
            publish()
            return
        }
        _active.value.stop()
        _active.value = desired
        desired.setRate(rate)
        publish()
        _engineChanges.tryEmit(Unit)
    }

    private fun resolveEngine(): SpeechEngine {
        val model = selectedNeuralModel() ?: return useSystemEngine()
        if (!repository.isInstalled(model)) return useSystemEngine()
        val current = neuralEngine
        if (current != null && current.model == model) return current
        current?.shutdown()
        val engine = OnnxSpeechEngine(
            context = appContext,
            model = model,
            modelDir = repository.modelDir(model),
            initialRate = rate,
            initialSpeaker = settings.supertonicSpeaker,
            initialVolume = volume,
            supertonicSteps = supertonicSteps,
        )
        neuralEngine = engine
        return engine
    }

    private fun useSystemEngine(): SpeechEngine {
        neuralEngine?.shutdown()
        neuralEngine = null
        return systemEngine
    }

    private fun updateModel(id: String, transform: (NeuralModelState) -> NeuralModelState) {
        _models.update { states ->
            states.map { if (it.model.id == id) transform(it) else it }
        }
        publish()
    }

    private fun publish() {
        _neuralSettings.value = NeuralSettings(
            engineType = _engineType.value,
            models = _models.value,
            piperModelId = settings.piperModelId,
            supertonicSpeaker = settings.supertonicSpeaker,
            usingSystemFallback = _engineType.value != TtsEngineType.SYSTEM && _active.value === systemEngine,
        )
    }

    private companion object {
        const val PREVIEW_TEXT = "Привет! Так звучит этот голос. Так будет читаться книга."
        const val PREVIEW_UTTERANCE_ID = "voice-preview"
    }
}
