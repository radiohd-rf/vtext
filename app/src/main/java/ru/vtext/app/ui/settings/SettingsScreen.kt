package ru.vtext.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Context
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
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineTitle = stringResource(R.string.engine_title)
    val neuralFallback = stringResource(R.string.neural_fallback)
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
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            sectionHeader(engineTitle)
            items(TtsEngineType.entries, key = { it.id }) { type ->
                EngineRow(
                    type = type,
                    selected = state.engineType == type,
                    onClick = { viewModel.selectEngine(type) },
                )
            }

            if (state.neural.usingSystemFallback) {
                item {
                    Text(
                        text = neuralFallback,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
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

            voiceTuningSection(state, viewModel, context)

            when (state.engineType) {
                TtsEngineType.SYSTEM -> systemVoicesSection(state, viewModel, context)
                TtsEngineType.PIPER -> piperSection(state, viewModel, context)
                TtsEngineType.SUPERTONIC -> supertonicSection(state, viewModel, context)
                TtsEngineType.SILERO -> sileroSection(state, viewModel, context)
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

private fun LazyListScope.groupHeader(title: String) {
    item {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp),
        )
    }
}

private fun LazyListScope.voiceTuningSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    sectionHeader(context.getString(R.string.voice_tuning_title))
    item {
        TuningSlider(
            title = stringResource(R.string.volume),
            valueText = stringResource(R.string.volume_value, (state.volume * 100).toInt()),
            value = state.volume,
            range = PlaybackSettingsStore.MIN_VOLUME..PlaybackSettingsStore.MAX_VOLUME,
            steps = 19,
            onValueChange = viewModel::setVolume,
            onValueChangeFinished = viewModel::previewCurrentVoice,
        )
    }
    item {
        TuningSlider(
            title = stringResource(R.string.fragment_pause),
            valueText = if (state.fragmentPauseMs == 0) {
                stringResource(R.string.fragment_pause_off)
            } else {
                stringResource(R.string.fragment_pause_value, state.fragmentPauseMs)
            },
            value = state.fragmentPauseMs.toFloat(),
            range = PlaybackSettingsStore.MIN_FRAGMENT_PAUSE_MS.toFloat()..
                PlaybackSettingsStore.MAX_FRAGMENT_PAUSE_MS.toFloat(),
            steps = 19,
            onValueChange = { viewModel.setFragmentPause(it.toInt()) },
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
    if (state.engineType == TtsEngineType.SUPERTONIC) {
        groupHeader(context.getString(R.string.supertonic_quality_title))
        item {
            SelectableRow(
                title = stringResource(R.string.quality_fast),
                subtitle = stringResource(R.string.quality_fast_desc),
                selected = state.supertonicSteps == PlaybackSettingsStore.FAST_SUPERTONIC_STEPS,
                onClick = {
                    viewModel.setSupertonicSteps(PlaybackSettingsStore.FAST_SUPERTONIC_STEPS)
                    viewModel.previewCurrentVoice()
                },
            )
        }
        item {
            SelectableRow(
                title = stringResource(R.string.quality_high),
                subtitle = stringResource(R.string.quality_high_desc),
                selected = state.supertonicSteps == PlaybackSettingsStore.QUALITY_SUPERTONIC_STEPS,
                onClick = {
                    viewModel.setSupertonicSteps(PlaybackSettingsStore.QUALITY_SUPERTONIC_STEPS)
                    viewModel.previewCurrentVoice()
                },
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun EngineRow(
    type: TtsEngineType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = when (type) {
        TtsEngineType.SYSTEM -> stringResource(R.string.engine_system)
        TtsEngineType.PIPER -> stringResource(R.string.engine_piper)
        TtsEngineType.SUPERTONIC -> stringResource(R.string.engine_supertonic)
        TtsEngineType.SILERO -> stringResource(R.string.engine_silero)
    }
    val subtitle = when (type) {
        TtsEngineType.SYSTEM -> stringResource(R.string.engine_system_desc)
        TtsEngineType.PIPER -> stringResource(R.string.engine_piper_desc)
        TtsEngineType.SUPERTONIC -> stringResource(R.string.engine_supertonic_desc)
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
        HorizontalDivider()
    }

    items(state.systemVoices, key = { it.name }) { voice ->
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
                if (voice.requiresNetwork) {
                    append(" · ")
                    append(context.getString(R.string.voice_network))
                }
            },
            selected = state.systemSelectedVoice == voice.name,
            onClick = { viewModel.selectSystemVoice(voice.name) },
        )
    }
}

private fun LazyListScope.piperSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    sectionHeader(context.getString(R.string.piper_voices_title))
    val piperModels = state.neural.models.filter { it.model.engine == TtsEngineType.PIPER }
    val female = piperModels.filter { it.model.gender == VoiceGender.FEMALE }
    val male = piperModels.filter { it.model.gender == VoiceGender.MALE }
    val other = piperModels.filter { it.model.gender == VoiceGender.UNKNOWN }

    if (female.isNotEmpty()) {
        groupHeader(context.getString(R.string.voices_female_title))
        items(female, key = { it.model.id }) { modelState ->
            PiperModelRow(state, modelState, viewModel)
        }
    }
    if (male.isNotEmpty()) {
        groupHeader(context.getString(R.string.voices_male_title))
        items(male, key = { it.model.id }) { modelState ->
            PiperModelRow(state, modelState, viewModel)
        }
    }
    if (other.isNotEmpty()) {
        groupHeader(context.getString(R.string.voices_other_title))
        items(other, key = { it.model.id }) { modelState ->
            PiperModelRow(state, modelState, viewModel)
        }
    }
}

@Composable
private fun PiperModelRow(
    state: SettingsUiState,
    modelState: NeuralModelState,
    viewModel: SettingsViewModel,
) {
    ModelRow(
        item = modelState,
        selected = state.neural.piperModelId == modelState.model.id,
        onSelect = { viewModel.selectPiperModel(modelState.model.id) },
        onDownload = { viewModel.downloadModel(modelState.model.id) },
        onCancel = viewModel::cancelDownload,
    )
}

private fun LazyListScope.supertonicSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    val model = NeuralModel.DEFAULT_SUPERTONIC
    val modelState = state.neural.stateOf(model)
    sectionHeader(context.getString(R.string.supertonic_model_title))
    if (modelState != null) {
        item(key = modelState.model.id) {
            ModelRow(
                item = modelState,
                selected = state.engineType == TtsEngineType.SUPERTONIC,
                onSelect = { viewModel.selectEngine(TtsEngineType.SUPERTONIC) },
                onDownload = { viewModel.downloadModel(modelState.model.id) },
                onCancel = viewModel::cancelDownload,
            )
        }
    }

    sectionHeader(context.getString(R.string.supertonic_speakers_title))
    if (modelState?.status != ModelStatus.READY) {
        item {
            Text(
                text = context.getString(R.string.supertonic_download_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    val femaleIds = (0..4).toList()
    val maleIds = (5..9).toList()
    groupHeader(context.getString(R.string.voices_female_title))
    items(femaleIds, key = { "f$it" }) { speaker ->
        SelectableRow(
            title = context.getString(R.string.speaker_female, speaker + 1),
            subtitle = null,
            selected = state.neural.supertonicSpeaker == speaker,
            onClick = { viewModel.selectSupertonicSpeaker(speaker) },
        )
    }
    groupHeader(context.getString(R.string.voices_male_title))
    items(maleIds, key = { "m$it" }) { speaker ->
        SelectableRow(
            title = context.getString(R.string.speaker_male, speaker - 4),
            subtitle = null,
            selected = state.neural.supertonicSpeaker == speaker,
            onClick = { viewModel.selectSupertonicSpeaker(speaker) },
        )
    }
}

private fun LazyListScope.sileroSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    val model = NeuralModel.DEFAULT_SILERO
    val modelState = state.neural.stateOf(model)
    sectionHeader(context.getString(R.string.silero_model_title))
    if (modelState != null) {
        item(key = modelState.model.id) {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) viewModel.importModel(modelState.model.id, uri)
            }
            ModelRow(
                item = modelState,
                selected = state.engineType == TtsEngineType.SILERO,
                onSelect = { viewModel.selectEngine(TtsEngineType.SILERO) },
                onDownload = { launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                onCancel = viewModel::cancelDownload,
                importLabel = stringResource(R.string.import_model),
            )
        }
    }

    if (modelState?.status != ModelStatus.READY) {
        item {
            Text(
                text = context.getString(R.string.silero_import_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        return
    }

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
    sectionHeader(context.getString(R.string.silero_dictionary_title))
    item(key = "silero-dictionary") {
        val dictionaryLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) viewModel.importSileroDictionary(uri)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
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

@Composable
private fun ModelRow(
    item: NeuralModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    importLabel: String? = null,
) {
    val ready = item.status == ModelStatus.READY
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = ready, onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = { if (ready) onSelect() },
            )
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(text = item.model.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = modelSubtitle(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == ModelStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            when (item.status) {
                ModelStatus.NOT_INSTALLED -> TextButton(onClick = onDownload) {
                    Text(importLabel ?: stringResource(R.string.download))
                }
                ModelStatus.DOWNLOADING -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
                ModelStatus.EXTRACTING -> CircularProgressIndicator(Modifier.size(20.dp))
                ModelStatus.READY -> if (!selected) TextButton(onClick = onSelect) {
                    Text(stringResource(R.string.choose))
                }
                ModelStatus.ERROR -> TextButton(onClick = onDownload) {
                    Text(importLabel ?: stringResource(R.string.retry))
                }
            }
        }
        when (item.status) {
            ModelStatus.DOWNLOADING -> LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            ModelStatus.EXTRACTING -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
            else -> HorizontalDivider()
        }
    }
}

@Composable
private fun modelSubtitle(item: NeuralModelState): String {
    val base = when (item.status) {
        ModelStatus.NOT_INSTALLED, ModelStatus.READY ->
            stringResource(R.string.model_size_mb, item.model.approxMegabytes)
        ModelStatus.DOWNLOADING ->
            stringResource(R.string.model_downloading, (item.progress * 100).toInt())
        ModelStatus.EXTRACTING -> stringResource(R.string.model_extracting)
        ModelStatus.ERROR -> item.error ?: stringResource(R.string.model_size_mb, item.model.approxMegabytes)
    }
    val gender = when (item.model.gender) {
        VoiceGender.FEMALE -> stringResource(R.string.gender_female)
        VoiceGender.MALE -> stringResource(R.string.gender_male)
        VoiceGender.UNKNOWN -> null
    }
    return if (gender != null) "$gender · $base" else base
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
