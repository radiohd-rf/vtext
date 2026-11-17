package ru.vtext.app.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.vtext.app.data.AudioBookmark
import ru.vtext.app.player.audio.AudioPlaybackCoordinator
import ru.vtext.app.player.audio.AudioPlayerState

class AudioPlayerViewModel(
    private val coordinator: AudioPlaybackCoordinator,
) : ViewModel() {

    val state = coordinator.state

    fun load(bookId: String, autoPlay: Boolean = false) {
        if (state.value.bookId == bookId) {
            // Уже открыта: перечитываем папку — могли добавить новые файлы.
            coordinator.refreshTracks(bookId)
            return
        }
        coordinator.load(bookId, autoPlay)
    }

    fun togglePlay() = coordinator.togglePlay()

    fun seekTo(positionMs: Long) = coordinator.seekTo(positionMs)

    fun seekBy(deltaMs: Long) = coordinator.seekBy(deltaMs)

    fun nextChapter() = coordinator.nextChapter()

    fun previousChapter() = coordinator.previousChapter()

    fun selectChapter(index: Int) = coordinator.selectChapter(index)

    fun setSpeed(value: Float) = coordinator.setSpeed(value)

    fun setSleepTimer(minutes: Int?) = coordinator.setSleepTimer(minutes)

    fun sleepUntilChapterEnd() = coordinator.setSleepUntilChapterEnd()

    fun addBookmark(label: String) = coordinator.addBookmark(label)

    fun removeBookmark(id: String) = coordinator.removeBookmark(id)

    fun jumpToBookmark(bookmark: AudioBookmark) = coordinator.jumpToBookmark(bookmark)

    fun dismissError() {
        // Ошибка живёт в состоянии координатора до следующего действия.
    }

    override fun onCleared() {
        // Плеер принадлежит Application: не останавливаем воспроизведение при уходе с экрана.
        super.onCleared()
    }

    companion object {
        fun factory(coordinator: AudioPlaybackCoordinator) = viewModelFactory {
            initializer { AudioPlayerViewModel(coordinator) }
        }
    }
}

/** Удобные производные значения для UI. */
val AudioPlayerState.canGoNext: Boolean get() = chapterIndex < tracks.lastIndex
val AudioPlayerState.canGoPrevious: Boolean get() = chapterIndex > 0 || positionMs > 3000
