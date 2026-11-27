package ru.vtext.app.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.vtext.app.R
import ru.vtext.app.data.Annotation
import ru.vtext.app.data.AnnotationKind

/** Лист «Цитаты и заметки»: две вкладки, переход к месту, шаринг и удаление. */
@Composable
fun AnnotationsScreen(
    annotations: List<Annotation>,
    initialTab: AnnotationKind,
    onBack: () -> Unit,
    onOpen: (Annotation) -> Unit,
    onDelete: (Annotation) -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(if (initialTab == AnnotationKind.NOTE) 1 else 0) }
    val kind = if (tab == 0) AnnotationKind.QUOTE else AnnotationKind.NOTE
    val visible = annotations.filter { it.kind == kind }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.reader_annotations),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(48.dp))
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.annotations_quotes)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.annotations_notes)) },
                )
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (kind == AnnotationKind.QUOTE) {
                                R.string.annotations_quotes_empty
                            } else {
                                R.string.annotations_notes_empty
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                ) {
                    items(visible, key = { it.id }) { annotation ->
                        AnnotationRow(
                            annotation = annotation,
                            onClick = { onOpen(annotation) },
                            onShare = { sharePlainText(context, annotation.text) },
                            onCopy = { copyPlainText(context, annotation.text) },
                            onDelete = { onDelete(annotation) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationRow(
    annotation: Annotation,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(bottom = 12.dp),
        ) {
            // Сверху — выделенный текст, под ним — заметка пользователя.
            Text(
                text = annotation.text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            if (annotation.kind == AnnotationKind.NOTE && annotation.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = annotation.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.annotation_actions),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.annotations_copy)) },
                    onClick = {
                        menu = false
                        onCopy()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.annotations_share)) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** Экран новой заметки: слева «←», справа галочка, заголовок по центру. */
@Composable
fun NoteEditorScreen(
    quoted: String,
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
                Text(
                    text = stringResource(R.string.note_new_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSave) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.save),
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    text = quoted,
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(stringResource(R.string.note_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
            }
        }
    }
}

fun sharePlainText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

fun copyPlainText(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(null, text))
}
