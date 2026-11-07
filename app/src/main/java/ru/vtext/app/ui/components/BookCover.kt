package ru.vtext.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vtext.app.domain.model.Book

/**
 * Обложка книги: картинка из `books/<id>/cover` или типографская заглушка
 * с инициалами названия. Декодирование — в IO, чтобы не дёргать кадры.
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    showFormat: Boolean = true,
) {
    val shape = RoundedCornerShape(corner)
    val cover = rememberCoverBitmap(book.coverPath)
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CoverFallback(book, corner)
            }
            if (showFormat) {
                FormatBadge(
                    text = book.format.name,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverFallback(book: Book, corner: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Text(
                text = initials(book.title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(22.dp)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
    }
}

@Composable
private fun FormatBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun rememberCoverBitmap(path: String?, sampleSize: Int = 2): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, sampleSize) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }.value

/** Первые буквы двух слов названия: «Война и мир» → «Ви». */
private fun initials(title: String): String {
    val words = title.split(' ', '—', '-', ':', '.', ',', '«', '»')
        .filter { it.isNotBlank() }
        .take(2)
    if (words.isEmpty()) return "?"
    return words.joinToString("") { word -> word.first().uppercase() }
}
