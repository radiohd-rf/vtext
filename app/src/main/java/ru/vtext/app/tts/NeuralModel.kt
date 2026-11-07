package ru.vtext.app.tts

/** Движок озвучивания, выбранный пользователем. */
enum class TtsEngineType(val id: String) {
    SYSTEM("system"),
    PIPER("piper"),
    SUPERTONIC("supertonic"),
    SILERO("silero"),
    ;

    companion object {
        fun fromId(id: String?): TtsEngineType = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Загружаемая нейросетевая модель. Каждый архив с sherpa-onnx содержит один
 * корневой каталог, который при установке распаковывается в filesDir/tts/<id>.
 */
enum class NeuralModel(
    val id: String,
    val engine: TtsEngineType,
    val displayName: String,
    val url: String,
    val approxBytes: Long,
    val rootDir: String,
    private val onnxFile: String? = null,
    val gender: VoiceGender = VoiceGender.UNKNOWN,
) {
    PIPER_IRINA(
        id = "piper-irina",
        engine = TtsEngineType.PIPER,
        displayName = "Ирина",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium-int8.tar.bz2",
        approxBytes = 21_149_417L,
        rootDir = "vits-piper-ru_RU-irina-medium-int8",
        onnxFile = "ru_RU-irina-medium.onnx",
        gender = VoiceGender.FEMALE,
    ),
    PIPER_DENIS(
        id = "piper-denis",
        engine = TtsEngineType.PIPER,
        displayName = "Денис",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-denis-medium-int8.tar.bz2",
        approxBytes = 21_000_000L,
        rootDir = "vits-piper-ru_RU-denis-medium-int8",
        onnxFile = "ru_RU-denis-medium.onnx",
        gender = VoiceGender.MALE,
    ),
    PIPER_DMITRI(
        id = "piper-dmitri",
        engine = TtsEngineType.PIPER,
        displayName = "Дмитрий",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-dmitri-medium-int8.tar.bz2",
        approxBytes = 21_000_000L,
        rootDir = "vits-piper-ru_RU-dmitri-medium-int8",
        onnxFile = "ru_RU-dmitri-medium.onnx",
        gender = VoiceGender.MALE,
    ),
    PIPER_RUSLAN(
        id = "piper-ruslan",
        engine = TtsEngineType.PIPER,
        displayName = "Руслан",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium-int8.tar.bz2",
        approxBytes = 21_000_000L,
        rootDir = "vits-piper-ru_RU-ruslan-medium-int8",
        onnxFile = "ru_RU-ruslan-medium.onnx",
        gender = VoiceGender.MALE,
    ),
    SUPERTONIC_3(
        id = "supertonic-3",
        engine = TtsEngineType.SUPERTONIC,
        displayName = "Supertonic 3",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        approxBytes = 128_774_318L,
        rootDir = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    ),
    SILERO_5(
        id = "silero-5-ru",
        engine = TtsEngineType.SILERO,
        displayName = "Silero v5",
        url = "",
        approxBytes = 103_000_000L,
        rootDir = "silero-5-ru",
    ),
    ;

    val approxMegabytes: Int get() = ((approxBytes + 1_048_575L) / 1_048_576L).toInt()

    /** Файлы, наличие которых означает, что модель распакована полностью. */
    val requiredFiles: List<String>
        get() = when (engine) {
            TtsEngineType.PIPER -> listOfNotNull(
                onnxFile,
                "tokens.txt",
                "espeak-ng-data/phondata",
                "espeak-ng-data/phontab",
            )
            TtsEngineType.SUPERTONIC -> listOf(
                "duration_predictor.int8.onnx",
                "text_encoder.int8.onnx",
                "vector_estimator.int8.onnx",
                "vocoder.int8.onnx",
                "tts.json",
                "unicode_indexer.bin",
                "voice.bin",
            )
            TtsEngineType.SILERO -> listOf(
                "accentor.ptl",
                "dur.ptl",
                "pitch.ptl",
                "tacotron.ptl",
                "vocoder48.ptl",
                "meta.json",
            )
            TtsEngineType.SYSTEM -> emptyList()
        }

    val piperOnnxFile: String? get() = onnxFile

    companion object {
        val DEFAULT_PIPER: NeuralModel = PIPER_IRINA
        val DEFAULT_SUPERTONIC: NeuralModel = SUPERTONIC_3
        val DEFAULT_SILERO: NeuralModel = SILERO_5

        fun byId(id: String?): NeuralModel? = entries.firstOrNull { it.id == id }

        fun forEngine(engine: TtsEngineType): List<NeuralModel> =
            entries.filter { it.engine == engine }

        const val SUPERTONIC_SPEAKERS = 10

        /** Supertonic 3: sid 0–4 — женские стили F1–F5, sid 5–9 — мужские M1–M5. */
        fun supertonicGender(speaker: Int): VoiceGender = when (speaker) {
            in 0..4 -> VoiceGender.FEMALE
            in 5..9 -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
    }
}
