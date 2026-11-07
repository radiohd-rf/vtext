# AGENTS.md

Инструкции для AI-агентов, работающих в этом репозитории.

## О проекте

- **Название:** vtext
- **Платформа:** Android
- **Назначение:** приложение читает вслух текстовые файлы и книги, которые загружает пользователь, с помощью TTS (text-to-speech).
- **Ключевой сценарий:** пользователь выбирает текстовый файл → приложение разбирает текст → озвучивает его через TTS с возможностью паузы, продолжения и навигации по фрагментам.
- **UX-референс:** Яндекс Книги, но только сценарий прослушивания книги, которую ещё никто не озвучил — речь генерируется на устройстве через TTS. Без магазина, покупок, подписок и студийных аудиокниг. Подробные границы скоупа — в `MEMORY.md`.

## Правила работы

1. Перед началом работы прочитай `MEMORY.md` — там накопленные решения, стек и контекст.
2. После значимых изменений дополняй `MEMORY.md` (архитектура, решения, грабли, договорённости).
3. Не удаляй и не перезаписывай чужие файлы без проверки — неизвестный файл может быть работой пользователя.
4. Стиль кода и структура — единообразно с уже существующим кодом.
5. Не коммить секреты, ключи, `local.properties`, keystore-файлы и локальные конфиги (см. `.gitignore`).
6. Учитывай ограничения Android: жизненный цикл, Doze, фоновое воспроизведение, разрешения на чтение файлов (Storage Access Framework).

## Стек

- Kotlin 2.0.21, AGP 8.7.3, Gradle 8.11.1, JDK 17.
- Jetpack Compose (BOM 2024.10.01) + Material 3, Navigation Compose, MVVM.
- minSdk 24, compileSdk/targetSdk 35.
- Форматы: TXT, FB2, EPUB, PDF, DOCX.
- Язык озвучивания: только русский; в настройках показываются только русские голоса TTS.
- TTS: системный `android.speech.tts.TextToSpeech` за интерфейсом `SpeechEngine`, плюс локальные нейродвижки Piper и Supertonic 3 через `sherpa-onnx`.
- Нейро-TTS: `com.k2-fsa: sherpa-onnx 2.0.1` (AAR в `app/libs/`), `piper-phonemize 1.5.9` (AAR) для espeak-фонем Piper; модели скачиваются в приложении.
- PDF: `com.tom-roush:pdfbox-android` (Apache 2.0), текст извлекается постранично.
- DOCX: собственный разбор ZIP + `word/document.xml` (заголовки по стилям Heading/Заголовок).
- Хранение: SAF-импорт с копированием в `filesDir/books/<id>/`.

## Архитектура

```
UI (Compose) → ViewModel (StateFlow) → domain (модели, TextSegmenter, parsers) → data (BookRepository, ProgressStore)
                                        ↘ player (PlaybackCoordinator) → tts (SpeechEngine)
                                                   ↕
                                          player (PlaybackService, foreground + MediaSession)
```

- `domain/model` — `Book`, `Chapter`, `ParsedBook`.
- `domain/parser` — `TxtParser`, `Fb2Parser`, `EpubParser`, `PdfParser`, `DocxParser`, `TextDecoder`, `HtmlText`, `XmlSanitizer`.
- `data` — библиотека в приватном хранилище (`meta.json`, `chapters.json`), прогресс в SharedPreferences, выбранный голос в `TtsSettingsStore`, скорость в `PlaybackSettingsStore`.
- `tts` — `AndroidSpeechEngine` (системный), `OnnxSpeechEngine` (Piper/Supertonic на sherpa-onnx), `TtsManager` (выбор движка и загрузка моделей), `ModelRepository` (скачивание/распаковка `.tar.bz2`).
- `player` — `PlaybackCoordinator` (единый источник правды о воспроизведении, живёт в `Application`) и `PlaybackService` (foreground-сервис, уведомление с управлением, `MediaSessionCompat`).
- `ui/library` — список книг, импорт, удаление долгим нажатием; `ui/book` — плеер с подсветкой фрагмента, скоростью и таймером сна; `ui/settings` — выбор движка, загрузка моделей, голоса с разделением по полу и предпросмотром, громкость, тон, пауза и качество Supertonic.

## Структура проекта

```
vtext/
├── app/
│   ├── build.gradle.kts
│   ├── libs/                          # sherpa-onnx.aar, piper-phonemize.aar
│   └── src/
│       ├── main/java/ru/vtext/app/
│       │   ├── data/                  # BookRepository, ProgressStore, TtsSettingsStore
│       │   ├── domain/{model,parser}/
│       │   ├── player/                # PlaybackCoordinator, PlaybackService
│       │   ├── tts/
│       │   ├── ui/{library,book,settings,theme}/
│       │   ├── MainActivity.kt
│       │   └── VtextApp.kt
│       ├── main/res/
│       └── test/java/ru/vtext/app/   # unit-тесты парсеров и сегментации
├── gradle/                           # wrapper
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── AGENTS.md
├── MEMORY.md
└── README.md
```

## Команды

```powershell
.\gradlew.bat :app:assembleDebug       # сборка APK
.\gradlew.bat :app:testDebugUnitTest   # юнит-тесты
.\gradlew.bat :app:installDebug        # установка на подключённое устройство
```

Если тесты падают с `Could not find or load main class worker.org.gradle.process.internal.worker.GradleWorkerMain`,
задай `GRADLE_USER_HOME` на путь без кириллицы (в этом окружении — junction `G:\gradle-home`).

## TODO

- Обработка окончания книги сделана; осталось проверить на разных устройствах.
- Инструментальные UI-тесты.
- Проверка PDF/DOCX на больших реальных книгах (производительность и память).
- Сравнить Piper и Supertonic 3 на устройстве и выбрать движок по умолчанию.
- Проверить нейродвижки на слабом устройстве (память ~400–650 МБ в пике).
