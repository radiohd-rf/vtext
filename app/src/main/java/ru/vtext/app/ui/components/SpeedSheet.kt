package ru.vtext.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.vtext.app.player.audio.SpeedPolicy

/**
 * Шторка скорости — общая для TTS-плеера и аудиокниг.
 *
 * Шкала непрерывная, значение привязывается к сетке на отпускании: иначе перетаскивание
 * «залипает» на границе зоны точной настройки.
 *
 * @param allowFine точная зона 0,85–1,15 с шагом 0,01 (у TTS каждый шаг — пересинтез).
 * @param applyLive применять значение на ходу, не дожидаясь отпускания шкалы.
 * @param applyDelayMs задержка перед применением: TTS перезапускает фрагмент,
 *   поэтому живые изменения склеиваются в одно через 350 мс.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    current: Float,
    onChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    allowFine: Boolean = true,
    applyLive: Boolean = true,
    applyDelayMs: Long = 0,
) {
    var value by remember(current) { mutableFloatStateOf(SpeedPolicy.snap(current)) }

    fun step(direction: Int): Float =
        if (allowFine) SpeedPolicy.next(value, direction)
        else SpeedPolicy.snap(value + direction * COARSE_STEP)

    LaunchedEffect(value, applyLive) {
        if (!applyLive) return@LaunchedEffect
        if (applyDelayMs > 0) kotlinx.coroutines.delay(applyDelayMs)
        onChange(value)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Шторка открывается чуть выше, чтобы не выглядывала кнопка play.
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Скорость",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = SpeedPolicy.format(value),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (allowFine) {
                    "шаг 0,1 · точнее в зоне 0,85–1,15"
                } else {
                    "шаг 0,1"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { value = step(-1) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Remove, "Медленнее")
                }
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = {
                        val snapped = SpeedPolicy.snap(value)
                        value = snapped
                        onChange(snapped)
                    },
                    valueRange = SpeedPolicy.MIN..SpeedPolicy.MAX,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                FilledTonalIconButton(
                    onClick = { value = step(+1) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Add, "Быстрее")
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private const val COARSE_STEP = 0.1f
