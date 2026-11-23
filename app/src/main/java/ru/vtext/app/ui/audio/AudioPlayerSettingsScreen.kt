package ru.vtext.app.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vtext.app.R
import ru.vtext.app.VtextApp
import ru.vtext.app.data.AudioPlayerSettingsStore

/** Настройки плеера аудиокниг: интервалы кнопок перемотки назад/вперёд. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = (context.applicationContext as VtextApp).container.audioPlayerSettings
    val backSkip by store.backSkipMs.collectAsStateWithLifecycle()
    val forwardSkip by store.forwardSkipMs.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.player_settings_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.player_settings_back),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SkipOptions(
                selected = backSkip,
                onSelect = store::setBackSkip,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.player_settings_forward),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SkipOptions(
                selected = forwardSkip,
                onSelect = store::setForwardSkip,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.player_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkipOptions(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AudioPlayerSettingsStore.SKIP_OPTIONS.forEach { ms ->
            FilterChip(
                selected = selected == ms,
                onClick = { onSelect(ms) },
                label = { Text("${ms / 1000} сек") },
            )
        }
    }
}
