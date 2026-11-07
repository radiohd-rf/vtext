package ru.vtext.app.tts

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor

/**
 * Silero v5 (ru) на PyTorch Lite: ударения, длительности, питч, Tacotron и HiFiGAN-вокодер.
 * Модель распространяется по лицензии CC BY-NC 4.0.
 */
class SileroSpeechEngine(
    val modelDir: File,
    initialRate: Float = 1f,
    initialVoice: String = DEFAULT_VOICE,
    initialVolume: Float = 1f,
) : SpeechEngine {

    override val state = MutableStateFlow(EngineState.INITIALIZING)
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()
    override val voices = MutableStateFlow(SileroVoice.entries.map { it.toInfo() })
    override val selectedVoice = MutableStateFlow(DEFAULT_VOICE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val synthMutex = Mutex()

    @Volatile
    private var rate = initialRate.coerceIn(MIN_RATE, MAX_RATE)

    @Volatile
    private var volume = initialVolume.coerceIn(0f, 1f)

    @Volatile
    private var speaker = SileroVoice.fromId(initialVoice).index

    private var accentor: Module? = null
    private var durationPredictor: Module? = null
    private var pitchPredictor: Module? = null
    private var acoustic: Module? = null
    private var vocoder: Module? = null
    private var meta: SileroMeta? = null
    private var stressDictionary: SileroStressDictionary? = null
    private var homosolver: SileroHomosolver? = null

    private var playbackJob: Job? = null
    private var pending: Pair<String, String>? = null
    private var generation = 0

    // Предсинтез первого куска следующего фрагмента.
    private val prefetchLock = Any()
    private var prefetchJob: Job? = null
    private var activePrefetchId: String? = null
    private var pendingPrefetch: PrefetchRequest? = null
    private var prefetched: Prefetched? = null

    @Volatile
    private var pipelineGenerating = false

    @Volatile
    private var activeTrack: AudioTrack? = null

    init {
        selectedVoice.value = SileroVoice.fromIndex(speaker).id
        ioScope.launch {
            try {
                val loaded = load()
                meta = loaded
                state.value = EngineState.READY
                Log.i(TAG, "Silero готов: ${loaded.symbols.length} символов, ${loaded.speakers.size} голосов")
                val queued = pending
                pending = null
                if (queued != null) speak(queued.first, queued.second)
            } catch (error: Throwable) {
                state.value = EngineState.ERROR
                Log.e(TAG, "Не удалось загрузить Silero", error)
                _events.tryEmit(SpeechEvent.Error(null, error.message ?: "Не удалось загрузить Silero"))
            }
        }
    }

    private fun load(): SileroMeta {
        val config = File(modelDir, META_FILE)
        val meta = SileroMeta.parse(JSONObject(config.readText()))
        accentor = LiteModuleLoader.load(File(modelDir, "accentor.ptl").absolutePath)
        durationPredictor = LiteModuleLoader.load(File(modelDir, "dur.ptl").absolutePath)
        pitchPredictor = LiteModuleLoader.load(File(modelDir, "pitch.ptl").absolutePath)
        acoustic = LiteModuleLoader.load(File(modelDir, "tacotron.ptl").absolutePath)
        vocoder = LiteModuleLoader.load(File(modelDir, "vocoder48.ptl").absolutePath)
        stressDictionary = SileroStressDictionary.open(File(modelDir, STRESS_FILE))
        Log.i(TAG, "Словарь ударений: ${if (stressDictionary != null) "подключён" else "нет"}")
        val solver = loadHomosolver()
        homosolver = solver
        Log.i(TAG, "Омографы: ${if (solver != null) "подключены" else "нет"}")
        return meta
    }

    private fun loadHomosolver(): SileroHomosolver? = runCatching {
        val model = File(modelDir, HOMOSOLVER_FILE)
        val vocab = File(modelDir, VOCAB_FILE)
        val dictionary = File(modelDir, HOMODICT_FILE)
        val phrases = File(modelDir, PHRASES_FILE)
        if (!model.isFile || !vocab.isFile || !dictionary.isFile) return null
        SileroHomosolver(
            module = LiteModuleLoader.load(model.absolutePath),
            tokenizer = SileroBertTokenizer(vocab),
            homodict = SileroHomosolver.parseHomodict(dictionary),
            phrases = if (phrases.isFile) SileroHomosolver.parsePhrases(phrases) else emptyMap(),
        )
    }.getOrNull()

    override fun setRate(rate: Float) {
        val value = rate.coerceIn(MIN_RATE, MAX_RATE)
        if (this.rate != value) {
            this.rate = value
            invalidatePrefetch()
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { activeTrack?.setVolume(this.volume) }
    }

    override fun setVoice(name: String?) {
        val index = SileroVoice.fromId(name).index
        if (speaker != index) {
            speaker = index
            invalidatePrefetch()
        }
        selectedVoice.value = SileroVoice.fromIndex(speaker).id
    }

    override fun speak(text: String, utteranceId: String) {
        if (text.isBlank()) return
        if (meta == null) {
            if (state.value == EngineState.ERROR) {
                _events.tryEmit(SpeechEvent.Error(utteranceId, "Silero не загружен"))
            } else {
                pending = text to utteranceId
            }
            return
        }
        cancelPrefetch(except = utteranceId)
        playbackJob?.cancel()
        val token = ++generation
        pipelineGenerating = true
        playbackJob = ioScope.launch { play(token, text, utteranceId) }
    }

    override fun prefetch(text: String, utteranceId: String) {
        if (text.isBlank()) return
        val config = meta ?: return
        val chunk = SileroText
            .chunkPlan(SileroText.normalizeForSpeech(text, loadUserTerms()), CHUNK_CHARS)
            .firstOrNull() ?: return
        synchronized(prefetchLock) {
            if (prefetched?.utteranceId == utteranceId) return
            pendingPrefetch = PrefetchRequest(utteranceId, chunk.text, chunk)
        }
        startPrefetchIfIdle()
    }

    /** Синтез идёт только когда текущий фрагмент уже полностью сгенерирован. */
    private fun startPrefetchIfIdle() {
        if (pipelineGenerating) return
        val request = synchronized(prefetchLock) {
            pendingPrefetch?.also { pendingPrefetch = null }
        } ?: return
        if (prefetchJob?.isActive == true) {
            synchronized(prefetchLock) { pendingPrefetch = request }
            return
        }
        val config = meta ?: return
        prefetchJob = ioScope.launch {
            try {
                synchronized(prefetchLock) { activePrefetchId = request.utteranceId }
                val audio = synthesize(config, request.text)
                if (audio.isEmpty()) return@launch
                synchronized(prefetchLock) {
                    prefetched = Prefetched(request.utteranceId, request.text, audio)
                }
                Log.i(TAG, "prefetch ${request.utteranceId}: ${"%.2f".format(audio.size / SAMPLE_RATE.toFloat())} c")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.w(TAG, "prefetch ${request.utteranceId} не удался", error)
            } finally {
                synchronized(prefetchLock) {
                    if (activePrefetchId == request.utteranceId) activePrefetchId = null
                }
            }
        }
    }

    /** Отменяет предсинтез, кроме того, что нужен для [except]. */
    private fun cancelPrefetch(except: String? = null) {
        val job = synchronized(prefetchLock) {
            if (activePrefetchId != null && activePrefetchId != except) prefetchJob else null
        }
        job?.cancel()
        if (job != null) prefetchJob = null
    }

    private fun invalidatePrefetch() {
        synchronized(prefetchLock) {
            prefetched = null
            pendingPrefetch = null
        }
        prefetchJob?.cancel()
        prefetchJob = null
    }

    private fun takePrefetched(utteranceId: String): Prefetched? = synchronized(prefetchLock) {
        val value = prefetched
        if (value != null && value.utteranceId == utteranceId) {
            prefetched = null
            value
        } else {
            null
        }
    }

    /** Если предсинтез этого фрагмента ещё идёт — дожидаемся его вместо повторного синтеза. */
    private suspend fun awaitPrefetch(utteranceId: String): Prefetched? {
        val job = synchronized(prefetchLock) {
            if (activePrefetchId == utteranceId) prefetchJob else null
        }
        job?.join()
        return takePrefetched(utteranceId)
    }

    private fun onPipelineFinished() {
        pipelineGenerating = false
        startPrefetchIfIdle()
    }

    /** Фрагмент почти готов: можно начинать предсинтез следующего. */
    private fun onPipelineNearlyFinished() {
        if (!pipelineGenerating) return
        pipelineGenerating = false
        startPrefetchIfIdle()
    }

    override fun stop() {
        generation++
        playbackJob?.cancel()
        playbackJob = null
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    override fun shutdown() {
        stop()
        cancelPrefetch()
        invalidatePrefetch()
        scope.cancel()
        ioScope.cancel()
        listOfNotNull(accentor, durationPredictor, pitchPredictor, acoustic, vocoder).forEach {
            runCatching { it.destroy() }
        }
        accentor = null
        durationPredictor = null
        pitchPredictor = null
        acoustic = null
        vocoder = null
        stressDictionary?.close()
        stressDictionary = null
        homosolver = null
    }

    // ----- Воспроизведение -----

    private suspend fun play(token: Int, text: String, utteranceId: String) {
        val config = meta ?: return
        val chunks = SileroText.chunkPlan(SileroText.normalizeForSpeech(text, loadUserTerms()), CHUNK_CHARS)
        if (chunks.isEmpty()) {
            onPipelineFinished()
            _events.tryEmit(SpeechEvent.Done(utteranceId))
            return
        }
        val cached = awaitPrefetch(utteranceId)?.takeIf { it.text == chunks.first().text }
        if (cached != null) Log.i(TAG, "cache hit $utteranceId")
        synchronized(prefetchLock) {
            if (pendingPrefetch?.utteranceId == utteranceId) pendingPrefetch = null
        }
        val firstToGenerate = if (cached != null) 1 else 0
        val audioChannel = kotlinx.coroutines.channels.Channel<Rendered>(capacity = 2)
        var track: AudioTrack? = null
        var written = 0L
        var started = false
        try {
            kotlinx.coroutines.coroutineScope {
                val producer = launch(Dispatchers.Default) {
                    try {
                        if (cached != null) {
                            audioChannel.send(Rendered(cached.audio, chunks.first().pauseMs))
                        }
                        for (index in firstToGenerate until chunks.size) {
                            if (token != generation) break
                            val chunk = chunks[index]
                            val audio = synthesize(config, chunk.text)
                            if (token != generation) break
                            if (audio.isNotEmpty()) audioChannel.send(Rendered(audio, chunk.pauseMs))
                            // К концу фрагмента освобождаем CPU под предсинтез следующего.
                            if (chunks.size - 1 - index <= NEAR_END_CHUNKS) onPipelineNearlyFinished()
                        }
                    } finally {
                        audioChannel.close()
                        onPipelineFinished()
                    }
                }
                try {
                    for (rendered in audioChannel) {
                        if (token != generation) break
                        if (track == null) {
                            track = createTrack().also {
                                activeTrack = it
                                it.setVolume(volume)
                                it.play()
                            }
                            _events.tryEmit(SpeechEvent.Started(utteranceId))
                            started = true
                        }
                        val audioTrack = track ?: break
                        val audio = rendered.audio
                        var offset = 0
                        while (offset < audio.size) {
                            if (token != generation) break
                            val count = minOf(WRITE_CHUNK, audio.size - offset)
                            val result = audioTrack.write(audio, offset, count, AudioTrack.WRITE_BLOCKING)
                            if (result < 0) break
                            offset += result
                            written += result
                        }
                        if (rendered.pauseMs > 0) {
                            val silence = FloatArray(SAMPLE_RATE * rendered.pauseMs / 1000)
                            var silent = 0
                            while (silent < silence.size) {
                                if (token != generation) break
                                val count = minOf(WRITE_CHUNK, silence.size - silent)
                                val result = audioTrack.write(silence, silent, count, AudioTrack.WRITE_BLOCKING)
                                if (result < 0) break
                                silent += result
                                written += result
                            }
                        }
                    }
                } finally {
                    producer.cancel()
                    audioChannel.close()
                }
            }
            if (token != generation) return
            val audioTrack = track
            if (audioTrack == null) {
                _events.tryEmit(SpeechEvent.Done(utteranceId))
                return
            }
            while (token == generation && audioTrack.playbackHeadPosition.toLong() < written) {
                kotlinx.coroutines.delay(40)
            }
            if (token != generation) return
            _events.tryEmit(SpeechEvent.Done(utteranceId))
            Log.i(TAG, "Silero [$utteranceId] ${"%.2f".format(written / SAMPLE_RATE.toFloat())} c")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.e(TAG, "Silero: ошибка синтеза", error)
            _events.tryEmit(SpeechEvent.Error(utteranceId, error.message ?: "Ошибка синтеза"))
        } finally {
            if (track != null) {
                if (started && token == generation) {
                    runCatching { track.stop() }
                } else {
                    runCatching { track.pause() }
                    runCatching { track.flush() }
                    runCatching { track.stop() }
                }
                runCatching { track.release() }
                if (activeTrack === track) activeTrack = null
            }
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(BUFFER_BYTES)
        return AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // ----- Синтез -----

    private class Rendered(val audio: FloatArray, val pauseMs: Int)

    private class PrefetchRequest(val utteranceId: String, val text: String, val chunk: SileroText.Chunk)

    private class Prefetched(val utteranceId: String, val text: String, val audio: FloatArray)

    /** Словарь произношения читается при каждом фрагменте: правки применяются без перезапуска. */
    private fun loadUserTerms(): Map<String, String> = runCatching {
        val file = File(modelDir.parentFile, DICTIONARY_FILE)
        if (file.isFile) SileroText.parseDictionary(file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private suspend fun synthesize(config: SileroMeta, text: String): FloatArray = synthMutex.withLock {
        withContext(Dispatchers.Default) {
            val accentorModule = accentor ?: return@withContext FloatArray(0)
            val durModule = durationPredictor ?: return@withContext FloatArray(0)
            val pitchModule = pitchPredictor ?: return@withContext FloatArray(0)
            val acousticModule = acoustic ?: return@withContext FloatArray(0)
            val vocoderModule = vocoder ?: return@withContext FloatArray(0)

            val homosolved = homosolver?.solve(text) ?: text
            val accented = SileroText.accent(
                homosolved,
                lookupStress = { word -> stressDictionary?.stressIndex(word) ?: -1 },
            ) { words ->
                predictStress(accentorModule, words)
            }
            Log.d(TAG, "Silero текст: $accented")

            val sequence = SileroText.buildSequence(
                accentedText = accented,
                sos = config.sos,
                eos = config.eos,
                symbols = config.symbols,
                symbolToId = config.symbolToId,
                typeIds = config.typeIds,
            )
            val length = sequence.ids.size
            if (length < 3) return@withContext FloatArray(0)

            val textTensor = Tensor.fromBlob(sequence.ids, longArrayOf(1, length.toLong()))
            val speakerTensor = Tensor.fromBlob(longArrayOf(speaker.toLong()), longArrayOf(1))
            val maskTensor = Tensor.fromBlob(FloatArray(length), longArrayOf(1, length.toLong()))
            val typesTensor = Tensor.fromBlob(sequence.types, longArrayOf(1, length.toLong()))
            val focusTensor = Tensor.fromBlob(sequence.focus, longArrayOf(1, length.toLong()))
            // Эмфаза расширяет лимит длительности первого символа (как в оригинале).
            val emphasized = sequence.types.isNotEmpty() && sequence.types[0] >= SileroText.EMPHASIS_TYPE_SHIFT
            val sosCap = if (emphasized) 20f else SOS_CAP

            val durations = durModule.forward(
                IValue.from(textTensor),
                IValue.from(speakerTensor),
                IValue.from(maskTensor),
                IValue.from(typesTensor),
                IValue.from(focusTensor),
            ).toTensor().dataAsFloatArray
            val scaled = FloatArray(length) { index ->
                val frames = exp(durations[index]) - 1f
                val positive = if (frames < 0f) 0f else frames
                (positive / rate).roundToLong().coerceAtLeast(0L).toFloat()
            }
            scaled[0] = minOf(scaled[0], sosCap)
            scaled[length - 1] = minOf(scaled[length - 1], 7f)
            if (length >= 2) scaled[length - 2] = 13f
            if (length >= 3) scaled[length - 3] = minOf(scaled[length - 3], 13f)
            // «…» — тянем последнюю гласную: тот самый per-symbol duration из Silero.
            if (text.trimEnd().endsWith("…")) {
                val vowelIndex = (length - 2 downTo 1).firstOrNull { index ->
                    val symbol = config.idToSymbol[sequence.ids[index].toInt()]
                    symbol != null && symbol in VOWELS
                }
                if (vowelIndex != null) {
                    scaled[vowelIndex] = (scaled[vowelIndex] * ELLIPSIS_STRETCH).roundToLong().coerceAtLeast(1L).toFloat()
                }
            }
            val durationTensor = Tensor.fromBlob(scaled, longArrayOf(1, length.toLong()))

            val pitchResult = pitchModule.forward(
                IValue.from(textTensor),
                IValue.from(speakerTensor),
                IValue.from(maskTensor),
                IValue.from(typesTensor),
                IValue.from(focusTensor),
            ).toTensor()
            val pitchShape = pitchResult.shape()
            val pitchData = pitchResult.dataAsFloatArray
            for (i in pitchData.indices) {
                if (pitchData[i] > -0.001f && pitchData[i] < 0.001f) pitchData[i] = 0f
            }
            val pitchTensor = Tensor.fromBlob(pitchData, pitchShape)

            val mel = acousticModule.forward(
                IValue.from(textTensor),
                IValue.from(speakerTensor),
                IValue.from(maskTensor),
                IValue.from(durationTensor),
                IValue.from(pitchTensor),
            ).toTensor()
            val audio = vocoderModule.forward(IValue.from(mel)).toTensor()
            audio.dataAsFloatArray
        }
    }

    private fun predictStress(module: Module, words: List<String>): List<SileroText.WordPrediction> {
        val input = IValue.listFrom(*words.map { IValue.from(it) }.toTypedArray())
        val output = module.forward(input).toTuple()
        val stress = output[0].toTensor()
        val yo = output[1].toTensor()
        val stressData = stress.dataAsFloatArray
        val stressShape = stress.shape()
        val yoData = yo.dataAsFloatArray
        val yoShape = yo.shape()
        val stressClasses = stressShape[1].toInt()
        val yoClasses = yoShape[1].toInt()
        return words.indices.map { row ->
            var bestStress = 0
            var bestStressValue = Float.NEGATIVE_INFINITY
            for (c in 0 until stressClasses) {
                val value = stressData[row * stressClasses + c]
                if (value > bestStressValue) {
                    bestStressValue = value
                    bestStress = c
                }
            }
            var bestYo = 0
            var bestYoValue = Float.NEGATIVE_INFINITY
            for (c in 0 until yoClasses) {
                val value = yoData[row * yoClasses + c]
                if (value > bestYoValue) {
                    bestYoValue = value
                    bestYo = c
                }
            }
            SileroText.WordPrediction(
                stressVowel = bestStress,
                stressProbability = softmaxProbability(stressData, row, stressClasses, bestStress),
                yoVowel = bestYo,
                yoProbability = softmaxProbability(yoData, row, yoClasses, bestYo),
            )
        }
    }

    /** Мягкий максимум только для выбранного класса: exp(x - max) / sum(exp(x - max)). */
    private fun softmaxProbability(data: FloatArray, row: Int, classes: Int, target: Int): Float {
        var max = Float.NEGATIVE_INFINITY
        for (c in 0 until classes) {
            val value = data[row * classes + c]
            if (value > max) max = value
        }
        var sum = 0.0
        for (c in 0 until classes) sum += exp((data[row * classes + c] - max).toDouble())
        val value = exp((data[row * classes + target] - max).toDouble())
        return (value / sum).toFloat()
    }

    private fun SileroVoice.toInfo(): VoiceInfo = VoiceInfo(
        name = id,
        localeTag = "ru-RU",
        displayName = title,
        requiresNetwork = false,
        gender = gender,
    )

    companion object {
        private const val TAG = "vtext-tts"
        private const val META_FILE = "meta.json"
        private const val STRESS_FILE = "stress.bin"
        private const val HOMOSOLVER_FILE = "homosolver.ptl"
        private const val VOCAB_FILE = "vocab.txt"
        private const val HOMODICT_FILE = "homodict.json"
        private const val PHRASES_FILE = "phrases.json"
        const val DICTIONARY_FILE = "silero-dictionary.txt"
        private const val SAMPLE_RATE = 48_000
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 3f
        private const val SOS_CAP = 5f
        private const val ELLIPSIS_STRETCH = 1.5f
        private const val VOWELS = "аеёиоуыэюя"
        private const val CHUNK_CHARS = 220
        private const val BUFFER_BYTES = 32_768
        private const val WRITE_CHUNK = 4096
        private const val NEAR_END_CHUNKS = 3
        const val DEFAULT_VOICE = "xenia"
    }
}

/** Голоса Silero v5 (ru). */
enum class SileroVoice(
    val id: String,
    val index: Int,
    val title: String,
    val gender: VoiceGender,
) {
    AIDAR("aidar", 0, "Айдар", VoiceGender.MALE),
    BAYA("baya", 1, "Байя", VoiceGender.FEMALE),
    KSENIYA("kseniya", 2, "Ксения", VoiceGender.FEMALE),
    EUGENE("eugene", 3, "Евгений", VoiceGender.MALE),
    XENIA("xenia", 4, "Xenia", VoiceGender.FEMALE),
    ;

    companion object {
        fun fromId(id: String?): SileroVoice = entries.firstOrNull { it.id == id } ?: XENIA
        fun fromIndex(index: Int): SileroVoice = entries.firstOrNull { it.index == index } ?: XENIA
    }
}

/** Метаданные символов и типов предложений Silero. */
internal class SileroMeta(
    val symbols: String,
    val symbolToId: Map<Char, Int>,
    val idToSymbol: Map<Int, Char>,
    val sos: String,
    val eos: String,
    val speakers: List<Map<String, Int>>,
    val typeIds: Map<String, Int>,
) {
    companion object {
        fun parse(json: JSONObject): SileroMeta {
            val symbols = json.getString("symbols")
            val idsJson = json.getJSONObject("symbol_to_id")
            val symbolToId = buildMap {
                idsJson.keys().forEach { key ->
                    if (key.isNotEmpty()) put(key[0], idsJson.getInt(key))
                }
            }
            val typesJson = json.getJSONObject("type2id")
            val typeIds = buildMap {
                typesJson.keys().forEach { put(it, typesJson.getInt(it)) }
            }
            val speakersJson = json.getJSONArray("speakers")
            val speakers = (0 until speakersJson.length()).map { index ->
                val item = speakersJson.getJSONObject(index)
                buildMap { item.keys().forEach { put(it, item.getInt(it)) } }
            }
            return SileroMeta(
                symbols = symbols,
                symbolToId = symbolToId,
                idToSymbol = symbolToId.entries.associate { (char, id) -> id to char },
                sos = json.getString("sos"),
                eos = json.getString("eos"),
                speakers = speakers,
                typeIds = typeIds,
            )
        }
    }
}
