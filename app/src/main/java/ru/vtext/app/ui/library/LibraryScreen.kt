package ru.vtext.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.data.YandexDiskLinks
import ru.vtext.app.domain.model.Book
import ru.vtext.app.domain.model.BookKind
import ru.vtext.app.domain.model.TextPreset
import ru.vtext.app.ui.components.BookCover

private val bookMimeTypes = arrayOf(
    "text/plain",
    "application/epub+zip",
    "application/x-fictionbook+xml",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
    "*/*",
)

private val audioMimeTypes = arrayOf(
    "audio/mpeg",
    "audio/mp4",
    "audio/x-m4a",
    "audio/flac",
    "audio/ogg",
    "audio/opus",
    "audio/wav",
    "audio/x-wav",
    "audio/*",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenAudio: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(
            repository = container.repository,
            progressStore = container.progressStore,
            playback = container.playback,
            audioRepository = container.audioRepository,
            audioProgress = container.audioProgress,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }
    var pendingImport by remember { mutableStateOf<List<Uri>?>(null) }
    var pendingAudio by remember { mutableStateOf<List<Uri>?>(null) }
    var showYandexDialog by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(TextPreset.AUTO) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) pendingAudio = uris
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingImport = uris
            selectedPreset = TextPreset.AUTO
        }
    }
    val launchPicker: () -> Unit = { picker.launch(bookMimeTypes) }

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error)
        viewModel.dismissError()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.library_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.books_count,
                                state.books.size,
                                state.books.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // На пустой библиотеке кнопка уже есть в центре — FAB не дублируем.
            if (state.books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.add_book)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.books.isNotEmpty()) {
                LibraryToolbar(
                    query = state.query,
                    sort = state.sort,
                    onQueryChange = viewModel::setQuery,
                    onSortChange = viewModel::setSort,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.books.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.books.isEmpty() -> EmptyLibrary(
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        onAdd = { showAddSheet = true },
                    )
                    state.visibleBooks.isEmpty() -> NoResults(
                        query = state.query,
                        onReset = { viewModel.setQuery("") },
                    )
                    else -> BookList(
                        books = state.visibleBooks,
                        positions = state.positions,
                        onOpen = { id ->
                            val target = state.visibleBooks.firstOrNull { it.id == id }
                            if (target?.kind == BookKind.AUDIO) onOpenAudio(id) else onOpenBook(id)
                        },
                        onLongPress = { pendingDelete = it },
                    )
                }

                if (state.importing) {
                    ImportBanner(
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Text(
                    text = stringResource(R.string.add_book),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                AddOption(
                    title = stringResource(R.string.add_text_book),
                    subtitle = stringResource(R.string.add_text_book_hint),
                    onClick = {
                        showAddSheet = false
                        launchPicker()
                    },
                )
                AddOption(
                    title = stringResource(R.string.add_audio_book),
                    subtitle = stringResource(R.string.add_audio_book_hint),
                    onClick = {
                        showAddSheet = false
                        audioPicker.launch(audioMimeTypes)
                    },
                )
                AddOption(
                    title = stringResource(R.string.add_audio_link),
                    subtitle = stringResource(R.string.add_audio_link_hint),
                    onClick = {
                        showAddSheet = false
                        showYandexDialog = true
                    },
                )
            }
        }
    }

    if (showYandexDialog) {
        var link by remember { mutableStateOf("") }
        var linkError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showYandexDialog = false },
            title = { Text(stringResource(R.string.audio_by_link)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.audio_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = link,
                        onValueChange = { value ->
                            // Ссылки не содержат пробелов: автозамена клавиатуры иногда их вставляет.
                            link = value.filterNot { it.isWhitespace() }
                            linkError = null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        placeholder = {
                            Text(
                                text = "https://disk.yandex.ru/d/…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    linkError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val publicKey = YandexDiskLinks.normalize(link)
                        if (publicKey == null) {
                            linkError = context.getString(R.string.audio_link_invalid)
                        } else {
                            showYandexDialog = false
                            viewModel.importYandex(publicKey)
                        }
                    },
                ) {
                    Text(stringResource(R.string.audio_link_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showYandexDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingDelete?.let { book ->        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_book)) },
            text = { Text(stringResource(R.string.delete_book_message, book.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(book)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingAudio?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingAudio = null },
            title = { Text("Добавить аудиокнигу") },
            text = {
                Text(
                    if (uris.size > 1) {
                        "Выбрано файлов: ${uris.size}. Это одна книга (дорожки по порядку) " +
                            "или несколько отдельных?"
                    } else {
                        "Файл будет добавлен как аудиокнига."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.importAudio(uris, asOneBook = true)
                        pendingAudio = null
                    },
                ) {
                    Text(if (uris.size > 1) "Одна книга" else "Добавить")
                }
            },
            dismissButton = {
                if (uris.size > 1) {
                    TextButton(
                        onClick = {
                            viewModel.importAudio(uris, asOneBook = false)
                            pendingAudio = null
                        },
                    ) {
                        Text("Отдельные книги")
                    }
                } else {
                    TextButton(onClick = { pendingAudio = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

    pendingImport?.let { uris ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.import_type_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.import_type_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedPreset = preset }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedPreset == preset,
                                onClick = { selectedPreset = preset },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = stringResource(presetTitle(preset)),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(presetDescription(preset)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.import(uris, selectedPreset)
                        pendingImport = null
                    },
                ) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryToolbar(
    query: String,
    sort: LibrarySort,
    onQueryChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = CircleShape,
            placeholder = {
                Text(
                    text = stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            } else {
                null
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.width(8.dp))
        SortButton(sort = sort, onSortChange = onSortChange)
    }
}

@Composable
private fun SortButton(sort: LibrarySort, onSortChange: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.sort_title),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(sortLabel(option)) },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                    trailingIcon = if (option == sort) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.RECENT -> stringResource(R.string.sort_recent)
    LibrarySort.TITLE -> stringResource(R.string.sort_title_az)
    LibrarySort.AUTHOR -> stringResource(R.string.sort_author_az)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookList(
    books: List<Book>,
    positions: Map<String, ReadingPosition>,
    onOpen: (String) -> Unit,
    onLongPress: (Book) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(books, key = { it.id }) { book ->
            BookRow(
                book = book,
                position = positions[book.id],
                onClick = { onOpen(book.id) },
                onLongClick = { onLongPress(book) },
            )
        }
    }
}

/** Постер в списке: классические 2:3, но заметно компактнее прежней плитки. */
private val ListPosterWidth = 72.dp
private val ListPosterHeight = 108.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: Book,
    position: ReadingPosition?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "bookRowPress")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClickLabel = stringResource(R.string.open_book),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.delete_book),
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ListPosterWidth, ListPosterHeight)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            BookCover(
                book = book,
                modifier = Modifier.fillMaxSize(),
                corner = 6.dp,
                showFormat = false,
            )
            if (position != null && position.started) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd),
                    shape = RoundedCornerShape(topEnd = 6.dp, bottomStart = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = "${position.percent.coerceIn(1, 100)}%",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = book.author ?: stringResource(R.string.unknown_author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            BookKindBadge(book)
        }
    }
}

/** Плашка под автором: аудиокнига, текстовая книга или аудиокнига из сети. */
@Composable
private fun BookKindBadge(book: Book) {
    val online = book.audioPublicKey != null
    val audio = book.kind == BookKind.AUDIO
    val label = when {
        online -> stringResource(R.string.kind_audio_online)
        audio -> stringResource(R.string.kind_audio)
        else -> stringResource(R.string.kind_text)
    }
    val container = if (audio || online) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (audio || online) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun ImportBanner(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.importing),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddOption(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(36.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.empty_library_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.empty_library_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_book))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.supported_formats),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun NoResults(query: String, onReset: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            text = stringResource(R.string.no_results_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_results_hint, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.reset_search))
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun presetTitle(preset: TextPreset): Int = when (preset) {
    TextPreset.AUTO -> R.string.preset_auto
    TextPreset.BOOK -> R.string.preset_book
    TextPreset.POETRY -> R.string.preset_poetry
}

private fun presetDescription(preset: TextPreset): Int = when (preset) {
    TextPreset.AUTO -> R.string.preset_auto_desc
    TextPreset.BOOK -> R.string.preset_book_desc
    TextPreset.POETRY -> R.string.preset_poetry_desc
}
