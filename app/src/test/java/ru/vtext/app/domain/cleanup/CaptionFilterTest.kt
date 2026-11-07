package ru.vtext.app.domain.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionFilterTest {

    @Test
    fun removesFigureAndTableCaptions() {
        val text = """
            Рис. 0.1. Краткая хронология
            Основной текст остаётся.
            Таблица 2. Сравнение алгоритмов
            Figure 3. Architecture
        """.trimIndent()

        val result = CaptionFilter.removeCaptions(text)

        assertTrue(result.contains("Основной текст"))
        assertTrue(!result.contains("Рис."))
        assertTrue(!result.contains("Таблица"))
        assertTrue(!result.contains("Figure"))
    }

    @Test
    fun keepsReferencesInsideSentences() {
        val text = "На рис. 0.2 показан график, но это часть предложения."

        assertEquals(text, CaptionFilter.removeCaptions(text))
    }
}
