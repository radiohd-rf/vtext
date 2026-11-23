package ru.vtext.app.ui.update

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlin.math.roundToInt
import ru.vtext.app.R
import ru.vtext.app.VtextApp

/**
 * Модалка «доступно обновление» при запуске приложения.
 * Живёт поверх навигации, поэтому проверка не зависит от текущего экрана.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: UpdatePromptViewModel = viewModel(
        key = "startup-update",
        factory = UpdatePromptViewModel.factory(container.updates),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkOnce() }

    // Файл скачан — отдаём системному инсталлятору и закрываем модалку.
    LaunchedEffect(state) {
        val ready = state as? UpdatePromptState.Ready ?: return@LaunchedEffect
        installUpdate(context, ready.file)
        viewModel.consumeReady()
    }

    when (val current = state) {
        UpdatePromptState.Hidden, is UpdatePromptState.Ready -> Unit

        is UpdatePromptState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_prompt_title)) },
            text = {
                val size = formatSize(current.info.sizeBytes)
                Text(
                    if (size != null) {
                        stringResource(R.string.update_prompt_text, current.info.version, size)
                    } else {
                        stringResource(R.string.update_prompt_text_no_size, current.info.version)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::downloadAndInstall) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )

        is UpdatePromptState.Downloading -> AlertDialog(
            onDismissRequest = viewModel::cancelDownload,
            title = { Text(stringResource(R.string.update_prompt_downloading_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            R.string.update_downloading,
                            (current.progress * 100).roundToInt(),
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { current.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDownload) {
                    Text(stringResource(R.string.update_cancel))
                }
            },
        )

        is UpdatePromptState.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_prompt_failed_title)) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = viewModel::retry) {
                    Text(stringResource(R.string.retry_update))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }
}

private fun installUpdate(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun formatSize(bytes: Long): String? {
    if (bytes <= 0L) return null
    val megabytes = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.getDefault(), "%.1f МБ", megabytes)
}
