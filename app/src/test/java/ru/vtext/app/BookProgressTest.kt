package ru.vtext.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.vtext.app.domain.bookPercent
import ru.vtext.app.domain.model.Chapter

class BookProgressTest {

    private val chapters = listOf(
        Chapter("Один", listOf("a", "b")),
        Chapter("Два", listOf("a", "b", "c", "d")),
        Chapter("Три", listOf("a", "b", "c", "d")),
    )

    @Test
    fun `start of the book is zero`() {
        assertEquals(0, bookPercent(chapters, 0, 0))
    }

    @Test
    fun `counts segments of previous chapters`() {
        // 2 сегмента первой главы + 3 текущих = 5 из 10.
        assertEquals(50, bookPercent(chapters, 1, 3))
    }

    @Test
    fun `last segment is almost a hundred`() {
        assertEquals(90, bookPercent(chapters, 2, 3))
    }

    @Test
    fun `out of range is clamped`() {
        assertEquals(0, bookPercent(chapters, -5, -5))
        assertEquals(100, bookPercent(chapters, 99, 99))
    }

    @Test
    fun `empty book is zero`() {
        assertEquals(0, bookPercent(emptyList(), 0, 0))
    }
}
