package ru.vtext.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import ru.vtext.app.player.audio.SpeedPolicy

/**
 * Шторка скорости — «колесо» с точкой, как у таймера сна.
 *
 * Значение под точкой применяется, когда карусель останавливается: у TTS
 * это пересинтез текущего фрагмента, поэтому изменения склеиваются задержкой.
 *
 * @param allowFine точная зона 0,85–1,15 с шагом 0,01.
 * @param applyLive применять значение сразу после остановки карусели.
 * @param applyDelayMs задержка перед применением (у TTS — 350 мс).
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
    val options = remember(allowFine) { speedOptions(allowFine) }
    val initialIndex = remember {
        options.indices.minByOrNull { abs(options[it] - SpeedPolicy.snap(current)) } ?: 0
    }
    var pendingIndex by remember { mutableIntStateOf(initialIndex) }
    var applied by remember { mutableFloatStateOf(options[initialIndex]) }
    var applyJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val dismissWithSelection = {
        if (!applyLive) {
            val option = options[pendingIndex]
            if (abs(option - applied) > 0.0001f) onChange(option)
        }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = dismissWithSelection) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(198.dp)
                .padding(bottom = 10.dp),
        ) {
            Text(
                text = "Скорость",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val side = (maxWidth - ITEM_WIDTH) / 2
                        val listState = rememberLazyListState()
                        LaunchedEffect(Unit) {
                            listState.scrollToItem(initialIndex)
                        }
                        // Применяем только после остановки карусели.
                        LaunchedEffect(listState) {
                            snapshotFlow {
                                if (listState.isScrollInProgress) {
                                    null
                                } else {
                                    centeredSpeedIndex(listState)
                                }
                            }
                                .filterNotNull()
                                .distinctUntilChanged()
                                .collect { index ->
                                    pendingIndex = index
                                    if (!applyLive) return@collect
                                    val option = options[index]
                                    if (abs(option - applied) <= 0.0001f) return@collect
                                    applied = option
                                    applyJob?.cancel()
                                    applyJob = scope.launch {
                                        if (applyDelayMs > 0) delay(applyDelayMs)
                                        onChange(option)
                                    }
                                }
                        }
                        LazyRow(
                            state = listState,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = side),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            flingBehavior = rememberSnapFlingBehavior(listState),
                        ) {
                            itemsIndexed(options) { index, speed ->
                                val selected = index == pendingIndex
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1f else 0.92f,
                                    label = "speedItem",
                                )
                                Text(
                                    text = SpeedPolicy.chipLabel(speed),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = 28.sp,
                                        lineHeight = 34.sp,
                                    ),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .width(ITEM_WIDTH)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .clickable {
                                            scope.launch {
                                                listState.animateScrollToItem(index)
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                    // Точка-индикатор: напротив неё стоит выбранная скорость.
                    Box(
                        Modifier
                            .offset(y = (-10).dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

/** Индекс скорости, которая стоит ровно напротив точки в центре карусели. */
private fun centeredSpeedIndex(
    listState: androidx.compose.foundation.lazy.LazyListState,
): Int? {
    val info = listState.layoutInfo
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    return info.visibleItemsInfo.minByOrNull { item ->
        abs(item.offset + item.size / 2f - center)
    }?.index
}

/**
 * Сетка скоростей: 0,5…2,0 шагом 0,1, а в зоне 0,85–1,15 — шагом 0,01.
 */
private fun speedOptions(allowFine: Boolean): List<Float> {
    val coarse = mutableListOf<Float>()
    var value = SpeedPolicy.MIN
    while (value <= SpeedPolicy.MAX + 0.0001f) {
        coarse += round2(value)
        value += 0.1f
    }
    if (!allowFine) return coarse
    coarse.removeAll { it > 0.85f && it < 1.15f }
    val fine = mutableListOf<Float>()
    var precise = 0.85f
    while (precise <= 1.1501f) {
        fine += round2(precise)
        precise += 0.01f
    }
    return (coarse + fine).sorted()
}

private fun round2(value: Float): Float = (value * 100).roundToInt() / 100f

private val ITEM_WIDTH = 88.dp
