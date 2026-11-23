package ru.vtext.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.vtext.app.BuildConfig
import ru.vtext.app.data.UpdateInfo
import ru.vtext.app.data.UpdateRepository
import ru.vtext.app.data.updateErrorMessage

/** Состояние модалки обновления, которая показывается при запуске приложения. */
sealed interface UpdatePromptState {
    /** Проверка ещё идёт, обновлений нет или пользователь нажал «Позже». */
    data object Hidden : UpdatePromptState

    data class Available(val info: UpdateInfo) : UpdatePromptState

    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdatePromptState

    data class Ready(val info: UpdateInfo, val file: File) : UpdatePromptState

    data class Failed(val info: UpdateInfo, val message: String) : UpdatePromptState
}

/**
 * Проверяет обновления один раз за запуск приложения: если версия на GitHub новее,
 * показывает модалку. «Позже» скрывает её до следующего запуска.
 */
class UpdatePromptViewModel(
    private val updates: UpdateRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdatePromptState>(UpdatePromptState.Hidden)
    val state: StateFlow<UpdatePromptState> = _state.asStateFlow()

    private var checked = false
    private var downloadJob: Job? = null

    fun checkOnce() {
        if (checked) return
        checked = true
        viewModelScope.launch {
            val info = runCatching { updates.latest() }.getOrNull() ?: return@launch
            if (UpdateRepository.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                _state.value = UpdatePromptState.Available(info)
            }
        }
    }

    fun dismiss() {
        _state.value = UpdatePromptState.Hidden
    }

    fun downloadAndInstall() {
        val available = _state.value as? UpdatePromptState.Available ?: return
        download(available.info)
    }

    /** Отмена загрузки: модалка скрывается, недокачанный файл не используется. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdatePromptState.Hidden
        viewModelScope.launch(Dispatchers.IO) { updates.clearDownloads() }
    }

    fun retry() {
        val failed = _state.value as? UpdatePromptState.Failed ?: return
        download(failed.info)
    }

    /** Инсталлятор запущен — модалка больше не нужна до следующего запуска. */
    fun consumeReady() {
        _state.value = UpdatePromptState.Hidden
    }

    private fun download(info: UpdateInfo) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = UpdatePromptState.Downloading(info, 0f)
            runCatching {
                updates.download(info) { progress ->
                    // После отмены колбэк ещё может прийти — не воскрешаем модалку.
                    if (downloadJob?.isActive == true) {
                        _state.value = UpdatePromptState.Downloading(info, progress)
                    }
                }
            }.onSuccess { file ->
                _state.value = UpdatePromptState.Ready(info, file)
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                _state.value = UpdatePromptState.Failed(info, updateErrorMessage(error))
            }
        }
    }

    companion object {
        fun factory(updates: UpdateRepository) = viewModelFactory {
            initializer { UpdatePromptViewModel(updates) }
        }
    }
}
