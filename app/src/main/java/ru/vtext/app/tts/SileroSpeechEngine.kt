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

    private var playbackJob: Job? = null
    private var pending: Pair<String, String>? = null
    private var generation = 0

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
        return meta
    }

    override fun setRate(rate: Float) {
        this.rate = rate.coerceIn(MIN_RATE, MAX_RATE)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        runCatching { activeTrack?.setVolume(this.volume) }
    }

    override fun setVoice(name: String?) {
        speaker = SileroVoice.fromId(name).index
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
        playbackJob?.cancel()
        val token = ++generation
        playbackJob = ioScope.launch { play(token, text, utteranceId) }
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
    }

    // ----- Воспроизведение -----

    private suspend fun play(token: Int, text: String, utteranceId: String) {
        val config = meta ?: return
        val chunks = SileroText.chunks(SileroText.normalizeForSpeech(text, loadUserTerms()), CHUNK_CHARS)
        if (chunks.isEmpty()) {
            _events.tryEmit(SpeechEvent.Done(utteranceId))
            return
        }
        val audioChannel = kotlinx.coroutines.channels.Channel<FloatArray>(capacity = 2)
        var track: AudioTrack? = null
        var written = 0L
        var started = false
        try {
            kotlinx.coroutines.coroutineScope {
                val producer = launch(Dispatchers.Default) {
                    try {
                        for (chunk in chunks) {
                            if (token != generation) break
                            val audio = synthesize(config, chunk)
                            if (token != generation) break
                            if (audio.isNotEmpty()) audioChannel.send(audio)
                        }
                    } finally {
                        audioChannel.close()
                    }
                }
                try {
                    for (audio in audioChannel) {
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
                        var offset = 0
                        while (offset < audio.size) {
                            if (token != generation) break
                            val count = minOf(WRITE_CHUNK, audio.size - offset)
                            val result = audioTrack.write(audio, offset, count, AudioTrack.WRITE_BLOCKING)
                            if (result < 0) break
                            offset += result
                            written += result
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

            val accented = SileroText.accent(
                text,
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
            val focusTensor = Tensor.fromBlob(LongArray(length), longArrayOf(1, length.toLong()))

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
            scaled[0] = minOf(scaled[0], SOS_CAP)
            scaled[length - 1] = minOf(scaled[length - 1], 7f)
            if (length >= 2) scaled[length - 2] = 13f
            if (length >= 3) scaled[length - 3] = minOf(scaled[length - 3], 13f)
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
        const val DICTIONARY_FILE = "silero-dictionary.txt"
        private const val SAMPLE_RATE = 48_000
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 3f
        private const val SOS_CAP = 5f
        private const val CHUNK_CHARS = 220
        private const val BUFFER_BYTES = 32_768
        private const val WRITE_CHUNK = 4096
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
                sos = json.getString("sos"),
                eos = json.getString("eos"),
                speakers = speakers,
                typeIds = typeIds,
            )
        }
    }
}
