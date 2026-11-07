package ru.vtext.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Локальный нейросетевой синтез через sherpa-onnx (Piper или Supertonic 3).
 * Синтез и воспроизведение идут в фоне, наружу выставляются те же события,
 * что и у системного TTS.
 */
class OnnxSpeechEngine(
    context: Context,
    val model: NeuralModel,
    private val modelDir: File,
    initialRate: Float,
    initialSpeaker: Int,
    initialVolume: Float = 1f,
    supertonicSteps: Int = DEFAULT_SUPERTONIC_STEPS,
) : SpeechEngine {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(EngineState.INITIALIZING)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()

    private val _voices = MutableStateFlow(model.voices())
    override val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()

    private val _selectedVoice = MutableStateFlow(
        if (model.engine == TtsEngineType.SUPERTONIC) initialSpeaker.coerceIn(0, 9).toString() else null,
    )
    override val selectedVoice: StateFlow<String?> = _selectedVoice.asStateFlow()

    private var tts: OfflineTts? = null
    private var rate: Float = initialRate.coerceIn(MIN_RATE, MAX_RATE)
    private var speaker: Int = initialSpeaker.coerceIn(0, 9)
    private var volume: Float = initialVolume.coerceIn(0f, 1f)
    private var steps: Int = supertonicSteps.coerceIn(MIN_STEPS, MAX_STEPS)

    private var playJob: Job? = null
    private var pending: Pair<String, String>? = null
    private val generateMutex = Mutex()
    private val prefetchLock = Any()
    private var prefetchJob: Job? = null
    @Volatile
    private var prefetchTargetId: String? = null
    @Volatile
    private var prefetchedId: String? = null
    @Volatile
    private var prefetchedAudio: GeneratedAudio? = null
    @Volatile
    private var pendingPrefetch: Pair<String, String>? = null

    @Volatile
    private var currentUtteranceId: String? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var generation = 0

    init {
        scope.launch {
            try {
                val engine = withContext(Dispatchers.IO) { createTts() }
                tts = engine
                Log.i(TAG, "${model.id}: sampleRate=${engine.sampleRate()}, speakers=${engine.numSpeakers()}, lang=${engine.lang()}")
                _state.value = EngineState.READY
                val queued = pending
                pending = null
                if (queued != null) {
                    startPlayback(engine, queued.first, queued.second)
                    startPrefetchIfPossible()
                } else {
                    startPrefetchIfPossible()
                }
            } catch (error: Throwable) {
                _state.value = EngineState.ERROR
                Log.e(TAG, "Не удалось загрузить ${model.id}", error)
                _events.tryEmit(SpeechEvent.Error(null, error.message ?: "Не удалось загрузить модель"))
            }
        }
    }

    override fun setRate(rate: Float) {
        val value = rate.coerceIn(MIN_RATE, MAX_RATE)
        if (this.rate != value) {
            this.rate = value
            invalidatePrefetch()
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        try {
            activeTrack?.setVolume(this.volume)
        } catch (_: Throwable) {
        }
    }

    /** Качество Supertonic: число шагов генерации (4…10). */
    fun setSupertonicSteps(steps: Int) {
        val value = steps.coerceIn(MIN_STEPS, MAX_STEPS)
        if (this.steps != value) {
            this.steps = value
            invalidatePrefetch()
        }
    }

    override fun setVoice(name: String?) {
        if (model.engine != TtsEngineType.SUPERTONIC) return
        val id = name?.toIntOrNull() ?: 0
        val value = id.coerceIn(0, 9)
        if (speaker != value) {
            speaker = value
            invalidatePrefetch()
        }
        _selectedVoice.value = speaker.toString()
    }

    override fun speak(text: String, utteranceId: String) {
        if (text.isBlank()) return
        currentUtteranceId = utteranceId
        discardOtherPrefetch(utteranceId)
        val engine = tts
        if (engine == null) {
            if (_state.value == EngineState.ERROR) {
                _events.tryEmit(SpeechEvent.Error(utteranceId, "Нейроголос не загружен"))
            } else {
                pending = text to utteranceId
            }
            return
        }
        startPlayback(engine, text, utteranceId)
    }

    override fun prefetch(text: String, utteranceId: String) {
        if (text.isBlank()) return
        if (utteranceId == currentUtteranceId) return
        synchronized(prefetchLock) {
            if (prefetchTargetId == utteranceId || prefetchedId == utteranceId) return
            pendingPrefetch = text to utteranceId
            prefetchTargetId = utteranceId
        }
        startPrefetchIfPossible()
    }

    private fun startPrefetchIfPossible() {
        val engine = tts ?: return
        val request: Pair<String, String>
        synchronized(prefetchLock) {
            if (prefetchJob?.isActive == true) return
            if (prefetchedId != null) return
            val next = pendingPrefetch ?: return
            pendingPrefetch = null
            if (next.second == currentUtteranceId) return
            prefetchTargetId = next.second
            request = next
        }
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // Даём текущему speak первым занять generateMutex.
                delay(PREFETCH_START_DELAY_MS)
                val audio = generateMutex.withLock {
                    engine.generateWithConfig(request.first, generationConfigFor(request.first))
                }
                synchronized(prefetchLock) {
                    if (prefetchTargetId == request.second) {
                        prefetchedId = request.second
                        prefetchedAudio = audio
                        prefetchTargetId = null
                        Log.i(TAG, "prefetched ${request.second}")
                    }
                }
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "prefetch failed ${request.second}", error)
                }
                synchronized(prefetchLock) {
                    if (prefetchTargetId == request.second) {
                        prefetchTargetId = null
                    }
                }
            }
        }
    }

    private suspend fun obtainAudio(
        engine: OfflineTts,
        text: String,
        utteranceId: String,
    ): GeneratedAudio {
        val running = synchronized(prefetchLock) {
            if (prefetchTargetId == utteranceId) prefetchJob else null
        }
        running?.join()
        val cached = synchronized(prefetchLock) {
            if (prefetchedId == utteranceId) {
                val audio = prefetchedAudio
                prefetchedId = null
                prefetchedAudio = null
                audio
            } else {
                null
            }
        }
        if (cached != null) {
            Log.i(TAG, "prefetch hit $utteranceId")
            return cached
        }
        return generateMutex.withLock {
            engine.generateWithConfig(text, generationConfigFor(text))
        }
    }

    private fun discardOtherPrefetch(utteranceId: String) {
        synchronized(prefetchLock) {
            if (prefetchedId != null && prefetchedId != utteranceId) {
                prefetchedId = null
                prefetchedAudio = null
            }
            if (prefetchTargetId != null && prefetchTargetId != utteranceId) {
                prefetchJob?.cancel()
                prefetchJob = null
                prefetchTargetId = null
                pendingPrefetch = null
            }
        }
    }

    private fun invalidatePrefetch() {
        synchronized(prefetchLock) {
            prefetchJob?.cancel()
            prefetchJob = null
            prefetchTargetId = null
            pendingPrefetch = null
            prefetchedId = null
            prefetchedAudio = null
        }
    }

    override fun stop() {
        val hadPlayback = playJob?.isActive == true || pending != null
        currentUtteranceId = null
        cancelCurrent()
        invalidatePrefetch()
        if (hadPlayback) {
            _events.tryEmit(SpeechEvent.Stopped(null))
        }
    }

    override fun shutdown() {
        val engine = tts
        tts = null
        currentUtteranceId = null
        cancelCurrent()
        invalidatePrefetch()
        if (engine == null) return
        // Ждём завершения возможного нативного синтеза и только потом освобождаем модель.
        scope.launch(Dispatchers.IO) {
            generateMutex.withLock { engine.release() }
        }
    }

    private fun startPlayback(engine: OfflineTts, text: String, utteranceId: String) {
        cancelCurrent()
        val token = generation
        playJob = scope.launch {
            playAudio(engine, text, utteranceId, token)
        }
    }

    /** Останавливает текущий синтез и воспроизведение, не трогая загруженную модель. */
    private fun cancelCurrent() {
        generation += 1
        pending = null
        val job = playJob
        playJob = null
        releaseTrack()
        job?.cancel()
    }

    private suspend fun playAudio(
        engine: OfflineTts,
        text: String,
        utteranceId: String,
        token: Int,
    ) = withContext(Dispatchers.IO) {
        var track: AudioTrack? = null
        try {
            val startedAt = System.currentTimeMillis()
            // Отменённый корутин-джоб не прерывает нативный generate, поэтому
            // новые вызовы ждут завершения предыдущего: OfflineTts не потокобезопасен.
            val audio = obtainAudio(engine, text, utteranceId)
            val synthesizedAt = System.currentTimeMillis()
            if (generation != token) return@withContext

            val samples = audio.samples
            val sampleRate = audio.sampleRate.takeIf { it > 0 }
                ?: engine.sampleRate().takeIf { it > 0 }
                ?: DEFAULT_SAMPLE_RATE
            if (samples.isEmpty()) {
                Log.w(TAG, "${model.id}: пустой результат")
                _events.tryEmit(SpeechEvent.Done(utteranceId))
                return@withContext
            }
            var peak = 0f
            for (sample in samples) {
                val value = kotlin.math.abs(sample)
                if (value > peak) peak = value
            }

            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(if (minBuffer > 0) minBuffer * 2 else DEFAULT_BUFFER_BYTES)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audioTrack
            activeTrack = audioTrack
            audioTrack.setVolume(volume)
            audioTrack.play()
            _events.tryEmit(SpeechEvent.Started(utteranceId))

            var written = 0
            while (written < samples.size) {
                if (generation != token) return@withContext
                val chunk = minOf(WRITE_CHUNK_SAMPLES, samples.size - written)
                val count = try {
                    audioTrack.write(samples, written, chunk, AudioTrack.WRITE_BLOCKING)
                } catch (_: Throwable) {
                    -1
                }
                if (count < 0) break
                written += count
            }

            if (generation != token) return@withContext
            while (generation == token && audioTrack.playbackHeadPosition.toLong() < written) {
                delay(20)
            }
            if (generation == token) {
                Log.i(
                    TAG,
                    "${model.id} [$utteranceId] -> ${"%.2f".format(written / sampleRate.toFloat())} c, " +
                        "peak=$peak, steps=$steps, synth=${synthesizedAt - startedAt} ms, " +
                        "total=${System.currentTimeMillis() - startedAt} ms",
                )
                _events.tryEmit(SpeechEvent.Done(utteranceId))
            }
        } catch (error: Throwable) {
            if (generation == token) {
                Log.e(TAG, "Ошибка синтеза ${model.id} [$utteranceId]", error)
                _events.tryEmit(SpeechEvent.Error(utteranceId, error.message ?: "Ошибка синтеза речи"))
            }
        } finally {
            if (activeTrack === track) {
                activeTrack = null
            }
            try {
                track?.release()
            } catch (_: Throwable) {
                // Трек уже освобождён из stop().
            }
        }
    }

    private fun releaseTrack() {
        val track = activeTrack ?: return
        activeTrack = null
        try {
            track.pause()
        } catch (_: Throwable) {
        }
        try {
            track.flush()
        } catch (_: Throwable) {
        }
        try {
            track.release()
        } catch (_: Throwable) {
        }
    }

    private fun generationConfigFor(text: String): GenerationConfig =
        if (model.engine == TtsEngineType.SUPERTONIC) {
            GenerationConfig(
                sid = speaker,
                numSteps = steps,
                speed = rate,
                extra = mapOf("lang" to "ru"),
            )
        } else {
            GenerationConfig(
                sid = 0,
                speed = rate,
                phonemeCodepoints = phonemize(text),
            )
        }

    private fun createTts(): OfflineTts {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val config = when (model.engine) {
            TtsEngineType.PIPER -> {
                ensurePhonemizer()
                val vits = OfflineTtsVitsModelConfig(
                    model = File(modelDir, model.piperOnnxFile.orEmpty()).absolutePath,
                    lexicon = "",
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = "",
                    dictDir = "",
                )
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = threads, debug = false),
                    maxNumSentences = 1,
                )
            }
            TtsEngineType.SUPERTONIC -> {
                val supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(modelDir, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(modelDir, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(modelDir, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(modelDir, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(modelDir, "tts.json").absolutePath,
                    unicodeIndexer = File(modelDir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(modelDir, "voice.bin").absolutePath,
                )
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(supertonic = supertonic, numThreads = threads, debug = false),
                    maxNumSentences = 1,
                )
            }
            TtsEngineType.SYSTEM -> error("Системный TTS не использует sherpa-onnx")
        }
        return OfflineTts(null, config)
    }

    /** Piper-модели не содержат лексикона: текст переводится в фонемы через espeak-ng. */
    private fun phonemize(text: String): Array<IntArray> {
        ensurePhonemizer()
        val result = piperPhonemizeText(text, PIPER_VOICE)
            ?: throw IllegalStateException("Не удалось разобрать текст для Piper")
        return try {
            Array(result.numSentences) { index -> result.getPhonemes(index) }
        } finally {
            result.close()
        }
    }

    private fun ensurePhonemizer() {
        if (phonemizerReady) return
        synchronized(PHONEMIZER_LOCK) {
            if (phonemizerReady) return
            piperPhonemizeInitialize(appContext)
            phonemizerReady = true
        }
    }

    private fun NeuralModel.voices(): List<VoiceInfo> = when (engine) {
        TtsEngineType.SUPERTONIC -> (0 until NeuralModel.SUPERTONIC_SPEAKERS).map { index ->
            val gender = NeuralModel.supertonicGender(index)
            val number = when (gender) {
                VoiceGender.FEMALE -> index + 1
                VoiceGender.MALE -> index - 4
                VoiceGender.UNKNOWN -> index + 1
            }
            VoiceInfo(
                name = index.toString(),
                localeTag = "ru-RU",
                displayName = when (gender) {
                    VoiceGender.FEMALE -> "Женский $number"
                    VoiceGender.MALE -> "Мужской $number"
                    VoiceGender.UNKNOWN -> "Голос $number"
                },
                requiresNetwork = false,
                gender = gender,
            )
        }
        TtsEngineType.PIPER -> listOf(
            VoiceInfo(
                name = id,
                localeTag = "ru-RU",
                displayName = displayName,
                requiresNetwork = false,
                gender = gender,
            ),
        )
        TtsEngineType.SYSTEM -> emptyList()
    }

    private companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 3f
        const val MIN_STEPS = 4
        const val MAX_STEPS = 10
        const val DEFAULT_SUPERTONIC_STEPS = 5
        const val DEFAULT_SAMPLE_RATE = 22_050
        const val DEFAULT_BUFFER_BYTES = 16_384
        const val WRITE_CHUNK_SAMPLES = 4096
        const val PREFETCH_START_DELAY_MS = 200L
        const val PIPER_VOICE = "ru"

        @Volatile
        private var phonemizerReady = false
        private val PHONEMIZER_LOCK = Any()
        private const val TAG = "vtext-tts"
    }
}
