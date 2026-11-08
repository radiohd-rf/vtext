# Сторонние компоненты и лицензии

Проект vtext распространяется по **Apache License 2.0** (см. `LICENSE`).
Ниже — компоненты, которые используются в сборке, и лицензии, на условиях которых
они предоставлены. Файлы лицензий доступны в репозиториях соответствующих проектов.

## Библиотеки

| Компонент | Версия | Лицензия | Ссылка |
|---|---|---|---|
| Kotlin stdlib | 2.0.21 | Apache 2.0 | https://github.com/JetBrains/kotlin |
| AndroidX Core KTX | 1.13.1 | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Activity Compose | 1.9.3 | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Lifecycle (ViewModel, Runtime Compose) | 2.8.7 | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Navigation Compose | 2.8.4 | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| AndroidX Media (MediaSessionCompat) | 1.7.0 | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| Jetpack Compose (UI, Graphics, Tooling) | BOM 2024.10.01 | Apache 2.0 | https://developer.android.com/jetpack/compose |
| Compose Material 3 | BOM 2024.10.01 | Apache 2.0 | https://developer.android.com/jetpack/compose |
| Material Icons Extended | BOM 2024.10.01 | Apache 2.0 | https://developer.android.com/jetpack/compose |
| Kotlin Coroutines | 1.7.3 | Apache 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| PyTorch Android Lite | 2.1.0 | BSD-3-Clause (стиль) | https://github.com/pytorch/pytorch |
| PDFBox-Android | 2.0.27.0 | Apache 2.0 | https://github.com/TomRoush/PdfBox-Android |
| JUnit | 4.13.2 | Eclipse Public License 1.0 | https://junit.org/junit4/ |

## Данные и модели

| Компонент | Лицензия | Примечание |
|---|---|---|
| Модели **Silero v5** (`.ptl`, accentor, homosolver, BERT-токенизатор, фразовые правила) | **CC BY-NC-SA 4.0** | Файлы моделей **не входят** в репозиторий и не распространяются вместе с APK: пользователь импортирует их сам. Некоммерческое использование, обязательная атрибуция, производные — под той же лицензией. |
| Словарь ударений **RUAccent** (`accents.json.gz`) | MIT | © Denis Petrov, https://github.com/Den4ikAI/ruaccent. Входит в архив модели Silero, а не в исходники. |
| Тестовые книги Project Gutenberg (`derzhavin.epub`, «Московия…») | Общественное достояние | Тексты Г. Р. Державина и исторический очерк; используются только как фикстуры парсеров. |
| Иконка приложения и графика | Apache 2.0 | Собственная работа, сгенерирована `tools/icon/make_icon.py`. |

## Важное ограничение

Код vtext можно использовать по Apache 2.0, но **синтез голосами Silero регулируется
CC BY-NC-SA 4.0** — это некоммерческая лицензия. Коммерческое использование приложения
с моделью Silero требует отдельного разрешения от авторов модели. Системный TTS Android
на это ограничение не влияет.

## Шрифты

Приложение использует системные семейства шрифтов Android (`serif`, `sans-serif`) и не
включает сторонних шрифтовых файлов.

---

Если вы считаете, что какой-то компонент указан неверно, создайте issue в репозитории.
