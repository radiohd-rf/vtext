package ru.vtext.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.data.PlaybackSettingsStore
import ru.vtext.app.tts.EngineState
import ru.vtext.app.tts.ModelStatus
import ru.vtext.app.tts.NeuralModel
import ru.vtext.app.tts.NeuralModelState
import ru.vtext.app.tts.SileroVoice
import ru.vtext.app.tts.TtsEngineType
import ru.vtext.app.tts.VoiceGender
import ru.vtext.app.tts.VoiceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as VtextApp).container
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            container.ttsManager,
            container.playback,
            container.playbackSettings,
            container.appearance,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var modelPrompt by remember { mutableStateOf(false) }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importModel(NeuralModel.DEFAULT_SILERO.id, uri)
        } else {
            // Файл не выбран — остаёмся на системном TTS.
            viewModel.selectEngine(TtsEngineType.SYSTEM)
        }
    }
    val engineTitle = stringResource(R.string.engine_title)
    val neuralLoading = stringResource(R.string.neural_loading)
    val neuralUnavailable = stringResource(R.string.neural_unavailable)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 760.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                appearanceSection(state, viewModel, context)
                sectionHeader(engineTitle)
                items(TtsEngineType.entries, key = { it.id }) { type ->
                    EngineRow(
                        type = type,
                        selected = state.engineType == type,
                        onClick = {
                            val model = state.neural.stateOf(NeuralModel.DEFAULT_SILERO)
                            if (type == TtsEngineType.SILERO && model?.status != ModelStatus.READY) {
                                modelPrompt = true
                            } else {
                                viewModel.selectEngine(type)
                            }
                        },
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.voice_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            if (state.engineType != TtsEngineType.SYSTEM) {
                when (state.engineState) {
                    EngineState.INITIALIZING -> if (!state.neural.usingSystemFallback) item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = neuralLoading,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                    EngineState.ERROR -> item {
                        Text(
                            text = neuralUnavailable,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    EngineState.READY -> Unit
                }
            }

            when (state.engineType) {
                TtsEngineType.SYSTEM -> systemVoicesSection(state, viewModel, context)
                TtsEngineType.SILERO -> sileroVoicesSection(state, viewModel, context)
            }

            voiceTuningSection(state, viewModel, context)

            if (state.engineType == TtsEngineType.SILERO) {
                sileroDictionarySection(state, viewModel, context)
            }
        }
    }
    }

    if (modelPrompt) {
        AlertDialog(
            onDismissRequest = { modelPrompt = false },
            title = { Text(stringResource(R.string.model_prompt_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_prompt_text,
                        NeuralModel.DEFAULT_SILERO.approxMegabytes,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelPrompt = false
                        viewModel.selectEngine(TtsEngineType.SILERO)
                        modelPicker.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*"),
                        )
                    },
                ) {
                    Text(stringResource(R.string.import_model))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelPrompt = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp),
        )
    }
}

private fun LazyListScope.groupHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        )
    }
}

private fun LazyListScope.appearanceSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    sectionHeader(context.getString(R.string.appearance_title))
    item {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        SettingsCard(selected = false, onClick = null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dynamic_color_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (supported) {
                            stringResource(R.string.dynamic_color_desc)
                        } else {
                            stringResource(R.string.dynamic_color_unavailable)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.dynamicColor && supported,
                    onCheckedChange = viewModel::setDynamicColor,
                    enabled = supported,
                )
            }
        }
    }
}

/** Карточка настройки: мягкая плашка, тонкая рамка, без тяжёлых теней. */
@Composable
private fun SettingsCard(
    selected: Boolean,
    onClick: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
            },
        ),
        content = content,
    )
}

private fun LazyListScope.voiceTuningSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    sectionHeader(context.getString(R.string.voice_tuning_title))
    item {
        val volumePercent = ((state.volume * 10).roundToInt() * 10).coerceIn(10, 100)
        TuningSlider(
            title = stringResource(R.string.volume),
            valueText = stringResource(R.string.percent_value, volumePercent),
            value = volumePercent.toFloat(),
            range = 10f..100f,
            steps = 8,
            onValueChange = { percent -> viewModel.setVolume(percent / 100f) },
            onValueChangeFinished = viewModel::previewCurrentVoice,
        )
    }
    item {
        val pausePercent = (
            state.fragmentPauseMs.toFloat() / PlaybackSettingsStore.MAX_FRAGMENT_PAUSE_MS * 10
            ).roundToInt().coerceIn(0, 10) * 10
        TuningSlider(
            title = stringResource(R.string.fragment_pause),
            valueText = stringResource(R.string.percent_value, pausePercent),
            value = pausePercent.toFloat(),
            range = 0f..100f,
            steps = 9,
            onValueChange = { percent ->
                viewModel.setFragmentPause(
                    (percent / 100f * PlaybackSettingsStore.MAX_FRAGMENT_PAUSE_MS).roundToInt(),
                )
            },
        )
    }
    if (state.engineType == TtsEngineType.SYSTEM) {
        item {
            TuningSlider(
                title = stringResource(R.string.pitch),
                valueText = stringResource(R.string.pitch_value, state.pitch),
                value = state.pitch,
                range = PlaybackSettingsStore.MIN_PITCH..PlaybackSettingsStore.MAX_PITCH,
                steps = 14,
                onValueChange = viewModel::setPitch,
                onValueChangeFinished = viewModel::previewCurrentVoice,
            )
        }
    }
}


@Composable
private fun TuningSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    SettingsCard(selected = false, onClick = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}

@Composable
private fun EngineRow(
    type: TtsEngineType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = when (type) {
        TtsEngineType.SYSTEM -> stringResource(R.string.engine_system)
        TtsEngineType.SILERO -> stringResource(R.string.engine_silero)
    }
    val subtitle = when (type) {
        TtsEngineType.SYSTEM -> stringResource(R.string.engine_system_desc)
        TtsEngineType.SILERO -> stringResource(R.string.engine_silero_desc)
    }
    SelectableRow(
        title = title,
        subtitle = subtitle,
        selected = selected,
        onClick = onClick,
    )
}

private fun LazyListScope.systemVoicesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    sectionHeader(context.getString(R.string.voice_title))
    when (state.systemEngineState) {
        EngineState.INITIALIZING -> item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(
                    text = context.getString(R.string.tts_initializing),
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        EngineState.ERROR -> item {
            Text(
                text = context.getString(R.string.voice_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
        }
        EngineState.READY -> if (state.systemVoices.isEmpty()) {
            item {
                Text(
                    text = context.getString(R.string.voice_no_russian),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }

    item {
        SelectableRow(
            title = context.getString(R.string.voice_default),
            subtitle = null,
            selected = state.systemSelectedVoice == null,
            onClick = { viewModel.selectSystemVoice(null) },
        )
    }

    val (offline, online) = state.systemVoices.partition { !it.requiresNetwork }
    if (offline.isNotEmpty()) {
        groupHeader(context.getString(R.string.voices_offline_title))
        systemVoiceRows(offline, state, viewModel, "off")
    }
    if (online.isNotEmpty()) {
        groupHeader(context.getString(R.string.voices_network_title))
        systemVoiceRows(online, state, viewModel, "net")
    }
}

private fun LazyListScope.systemVoiceRows(
    voices: List<VoiceInfo>,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    keyPrefix: String,
) {
    items(voices, key = { "$keyPrefix-${it.name}" }) { voice ->
        val gender = when (voice.gender) {
            VoiceGender.FEMALE -> stringResource(R.string.gender_female)
            VoiceGender.MALE -> stringResource(R.string.gender_male)
            VoiceGender.UNKNOWN -> null
        }
        SelectableRow(
            title = voice.displayName,
            subtitle = buildString {
                if (gender != null) {
                    append(gender)
                    append(" · ")
                }
                append(voice.localeTag)
            },
            selected = state.systemSelectedVoice == voice.name,
            onClick = { viewModel.selectSystemVoice(voice.name) },
        )
    }
}

private fun LazyListScope.sileroVoicesSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    val model = NeuralModel.DEFAULT_SILERO
    val modelState = state.neural.stateOf(model)

    // Модель показываем только пока её нет: когда всё готово, отдельная карточка —
    // третий повтор «Silero v5» на одном экране и лишний шум.
    if (modelState?.status != ModelStatus.READY) return

    sectionHeader(context.getString(R.string.voice_title))
    val female = SileroVoice.entries.filter { it.gender == VoiceGender.FEMALE }
    val male = SileroVoice.entries.filter { it.gender == VoiceGender.MALE }
    groupHeader(context.getString(R.string.voices_female_title))
    items(female, key = { "sf-${it.id}" }) { voice ->
        SelectableRow(
            title = voice.title,
            subtitle = null,
            selected = state.neural.sileroVoice == voice.id,
            onClick = { viewModel.selectSileroVoice(voice.id) },
        )
    }
    groupHeader(context.getString(R.string.voices_male_title))
    items(male, key = { "sm-${it.id}" }) { voice ->
        SelectableRow(
            title = voice.title,
            subtitle = null,
            selected = state.neural.sileroVoice == voice.id,
            onClick = { viewModel.selectSileroVoice(voice.id) },
        )
    }
}

private fun LazyListScope.sileroDictionarySection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    if (state.neural.stateOf(NeuralModel.DEFAULT_SILERO)?.status != ModelStatus.READY) return
    sectionHeader(context.getString(R.string.silero_dictionary_title))
    item(key = "silero-dictionary") {
        val dictionaryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) viewModel.importSileroDictionary(uri)
        }
        SettingsCard(selected = false, onClick = null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.silero_dictionary_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { dictionaryLauncher.launch(arrayOf("text/plain", "*/*")) }) {
                    Text(stringResource(R.string.silero_dictionary_import))
                }
            }
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SettingsCard(selected = selected, onClick = null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
