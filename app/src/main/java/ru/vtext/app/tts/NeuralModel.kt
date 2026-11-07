package ru.vtext.app.tts

/** Движок озвучивания, выбранный пользователем. */
enum class TtsEngineType(val id: String) {
    SYSTEM("system"),
    SILERO("silero"),
    ;

    companion object {
        fun fromId(id: String?): TtsEngineType = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Загружаемая нейросетевая модель. Архив с моделью распаковывается
 * в filesDir/tts/<id>.
 */
enum class NeuralModel(
    val id: String,
    val engine: TtsEngineType,
    val displayName: String,
    val url: String,
    val approxBytes: Long,
    val rootDir: String,
    val gender: VoiceGender = VoiceGender.UNKNOWN,
) {
    SILERO_5(
        id = "silero-5-ru",
        engine = TtsEngineType.SILERO,
        displayName = "Silero v5",
        url = "",
        approxBytes = 166_000_000L,
        rootDir = "silero-5-ru",
    ),
    ;

    val approxMegabytes: Int get() = ((approxBytes + 1_048_575L) / 1_048_576L).toInt()

    /** Файлы, наличие которых означает, что модель распакована полностью. */
    val requiredFiles: List<String>
        get() = when (engine) {
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

    companion object {
        val DEFAULT_SILERO: NeuralModel = SILERO_5

        fun byId(id: String?): NeuralModel? = entries.firstOrNull { it.id == id }
    }
}
