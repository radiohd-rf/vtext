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
- Форматы: TXT, FB2, EPUB.
- TTS: системный `android.speech.tts.TextToSpeech` за интерфейсом `SpeechEngine`.
- Хранение: SAF-импорт с копированием в `filesDir/books/<id>/`.

## Архитектура

```
UI (Compose) → ViewModel (StateFlow) → domain (модели, TextSegmenter, parsers) → data (BookRepository, ProgressStore)
                                        ↘ tts (SpeechEngine)
```

- `domain/model` — `Book`, `Chapter`, `ParsedBook`.
- `domain/parser` — `TxtParser`, `Fb2Parser`, `EpubParser`, `TextDecoder`, `HtmlText`, `XmlSanitizer`.
- `data` — библиотека в приватном хранилище (`meta.json`, `chapters.json`), прогресс в SharedPreferences.
- `tts` — `AndroidSpeechEngine` с событиями Started/Done/Error/Stopped.
- `ui/library` — список книг и импорт; `ui/book` — плеер с подсветкой фрагмента, скоростью и таймером сна.

## Структура проекта

```
vtext/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/ru/vtext/app/
│       │   ├── data/
│       │   ├── domain/{model,parser}/
│       │   ├── tts/
│       │   ├── ui/{library,book,theme}/
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

- Foreground service для воспроизведения в фоне и уведомления с управлением.
- Сохранение настроек скорости/таймера между сессиями.
- Обработка окончания книги, удаление книг из библиотеки.
- Инструментальные UI-тесты.
