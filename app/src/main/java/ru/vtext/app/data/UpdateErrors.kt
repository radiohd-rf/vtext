package ru.vtext.app.data

import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Человеческое описание сетевой ошибки для карточки обновления. */
fun updateErrorMessage(error: Throwable): String = when (error) {
    is SocketTimeoutException -> "GitHub не отвечает. Проверьте соединение."
    is UnknownHostException -> "Нет связи с GitHub."
    else -> error.message ?: "Не удалось проверить обновления"
}
