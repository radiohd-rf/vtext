package ru.vtext.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.vtext.app.data.Annotation
import ru.vtext.app.data.AnnotationKind
import ru.vtext.app.ui.reader.ReaderSelection
import ru.vtext.app.ui.reader.TextPos
import ru.vtext.app.ui.reader.rangeIn

/** Диапазоны выделения и сохранённых цитат внутри кусков текста. */
class AnnotationRangeTest {

    @Test
    fun selectionCoversOneParagraph() {
        val selection = ReaderSelection(0, TextPos(2, 3), TextPos(2, 9))
        assertEquals(3 until 9, selection.rangeIn(segment = 2, charOffset = 0, length = 20))
        assertNull(selection.rangeIn(segment = 1, charOffset = 0, length = 20))
        assertNull(selection.rangeIn(segment = 3, charOffset = 0, length = 20))
    }

    @Test
    fun selectionSpansSeveralParagraphs() {
        val selection = ReaderSelection(0, TextPos(2, 5), TextPos(4, 4))
        // Последний абзац выделен с начала.
        assertEquals(0 until 12, selection.rangeIn(segment = 3, charOffset = 0, length = 12))
        // Первый — от смещения до конца.
        assertEquals(5 until 12, selection.rangeIn(segment = 2, charOffset = 0, length = 12))
        // Последний абзац: кусок с начала и до конца выделения.
        assertEquals(0 until 4, selection.rangeIn(segment = 4, charOffset = 0, length = 30))
        // Кусок страницы, начавшийся позже конца выделения, в диапазон не попадает.
        assertNull(selection.rangeIn(segment = 4, charOffset = 10, length = 30))
    }

    @Test
    fun selectionRangeClampsToBlock() {
        val selection = ReaderSelection(0, TextPos(1, 0), TextPos(1, 100))
        assertEquals(0 until 7, selection.rangeIn(segment = 1, charOffset = 0, length = 7))
    }

    @Test
    fun annotationRangeOnPartOfPage() {
        val annotation = Annotation(
            id = "1",
            kind = AnnotationKind.QUOTE,
            chapterIndex = 0,
            startSegment = 5,
            startOffset = 120,
            endSegment = 5,
            endOffset = 140,
            text = "цитата",
            createdAt = 0L,
        )
        // Страница начинается с 100-го символа абзаца.
        assertEquals(20 until 40, annotation.rangeIn(segment = 5, charOffset = 100, length = 60))
        // Следующая страница начинается с 160-го — диапазон уже не попадает.
        assertNull(annotation.rangeIn(segment = 5, charOffset = 160, length = 60))
    }

    @Test
    fun annotationCrossesParagraphs() {
        val annotation = Annotation(
            id = "2",
            kind = AnnotationKind.NOTE,
            chapterIndex = 3,
            startSegment = 7,
            startOffset = 10,
            endSegment = 9,
            endOffset = 5,
            text = "заметка",
            createdAt = 0L,
        )
        assertEquals(10 until 30, annotation.rangeIn(segment = 7, charOffset = 0, length = 30))
        assertEquals(0 until 30, annotation.rangeIn(segment = 8, charOffset = 0, length = 30))
        assertEquals(0 until 5, annotation.rangeIn(segment = 9, charOffset = 0, length = 30))
    }
}
