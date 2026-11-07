package ru.vtext.app.domain.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTextCleanerTest {

    @Test
    fun removesRepeatedHeaderFooterAndPageNumbers() {
        val pages = (1..5).map { number ->
            "Книга\nСтрока $number-а\nСтрока $number-б\nСтрока $number-в\n$number"
        }

        val result = ImportTextCleaner.cleanPages(pages)

        assertTrue(result.pages.first().contains("Книга"))
        result.pages.drop(1).forEach { page ->
            assertFalse(page.contains("Книга"))
        }
        result.pages.forEachIndexed { index, page ->
            assertFalse("Номер страницы остался: $page", page.trim().endsWith("${index + 1}"))
            assertTrue(page.contains("Строка ${index + 1}-а"))
        }
        assertEquals(5, result.removedPageNumbers)
    }

    @Test
    fun keepsUniqueFirstLines() {
        val pages = listOf(
            "Начало один\nТекст\nЕщё\nИ ещё\nКонец",
            "Начало два\nТекст\nЕщё\nИ ещё\nКонец",
            "Начало три\nТекст\nЕщё\nИ ещё\nКонец",
            "Начало четыре\nТекст\nЕщё\nИ ещё\nКонец",
        )

        val result = ImportTextCleaner.cleanPages(pages)

        assertTrue(result.pages[0].contains("Начало один"))
        assertTrue(result.pages[1].contains("Начало два"))
        assertTrue(result.pages[2].contains("Начало три"))
        assertTrue(result.pages[3].contains("Начало четыре"))
    }

    @Test
    fun doesNotRemoveStandaloneNumberFromSinglePage() {
        val result = ImportTextCleaner.cleanText("1984\nДжордж Оруэлл\nТекст романа")

        assertTrue(result.text().contains("1984"))
    }

    @Test
    fun removesLabeledPageNumberEvenOnSinglePage() {
        val result = ImportTextCleaner.cleanText("Стр. 12\nТекст главы")

        assertFalse(result.text().contains("Стр. 12"))
        assertTrue(result.text().contains("Текст главы"))
        assertEquals(1, result.removedPageNumbers)
    }

    @Test
    fun joinsHyphenatedWords() {
        val result = ImportTextCleaner.cleanText("Это дол-\nгая история.")

        assertTrue(result.text().contains("долгая"))
        assertEquals(1, result.joinedHyphenations)
    }

    @Test
    fun keepsMeaningfulHyphenBeforeUppercase() {
        val result = ImportTextCleaner.cleanText("Санкт-\nПетербург")

        assertTrue(result.text().contains("Санкт-"))
        assertTrue(result.text().contains("Петербург"))
        assertFalse(result.text().contains("СанктПетербург"))
    }

    @Test
    fun keepsRepeatedLinesInBody() {
        val pages = (1..5).map { number ->
            "Уникально $number\nСтрока a\nСтрока b\nДа.\nСтрока c\nСтрока d\nКонец $number"
        }

        val result = ImportTextCleaner.cleanPages(pages)

        result.pages.forEach { page ->
            assertTrue(page.contains("Да."))
        }
    }

    @Test
    fun doesNotRemoveSparseChapterNumbers() {
        val pages = (1..10).map { number ->
            if (number == 1 || number == 6) {
                "${if (number == 1) 1 else 2}\nСтрока a $number\nСтрока b $number\nСтрока c $number\nКонец $number"
            } else {
                "Строка a $number\nСтрока b $number\nСтрока c $number\nСтрока d $number\nКонец $number"
            }
        }

        val result = ImportTextCleaner.cleanPages(pages)

        assertTrue(result.pages[0].startsWith("1"))
        assertTrue(result.pages[5].startsWith("2"))
    }

    @Test
    fun protectsShortTitlePage() {
        val pages = listOf("Название книги\nАвтор") +
            (1..4).map { number -> "Название книги\nТекст $number\nЕщё\nИ ещё\n$number" }

        val result = ImportTextCleaner.cleanPages(pages)

        assertTrue(result.pages.first().contains("Название книги"))
        result.pages.drop(1).forEach { page ->
            assertFalse(page.contains("Название книги"))
        }
    }
}
