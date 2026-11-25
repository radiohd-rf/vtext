package ru.vtext.app.domain.cleanup

import org.junit.Assert.assertTrue
import org.junit.Test

class DropCapTest {

    @Test
    fun restoresDropCapLetter() {
        val page = """
            Дворец
            ворец Советов так и не был построен, и все же

            Д в 1930-е годы он существовал
        """.trimIndent()
        val cleaned = ImportTextCleaner.cleanPages(listOf(page)).pages.single()
        assertTrue(cleaned, cleaned.contains("Дворец Советов так и не был построен"))
        assertTrue(cleaned, cleaned.contains("в 1930-е годы он существовал"))
    }

    @Test
    fun restoresStandaloneDropCapLetter() {
        val page = """
            Дворец

            ворец Советов так и не был построен, и все же

            Д

            в 1930-е годы он существовал
        """.trimIndent()
        val cleaned = ImportTextCleaner.cleanPages(listOf(page)).pages.single()
        assertTrue(cleaned, cleaned.contains("Дворец Советов так и не был построен"))
        assertTrue(cleaned, cleaned.contains("в 1930-е годы он существовал"))
        assertTrue(cleaned, !cleaned.contains("\nД\n"))
    }

    @Test
    fun keepsNormalSingleLetterWords() {
        val page = """
            Обычный абзац текста заканчивается здесь.

            В 1930-е годы он существовал
        """.trimIndent()
        val cleaned = ImportTextCleaner.cleanPages(listOf(page)).pages.single()
        assertTrue(cleaned, cleaned.contains("В 1930-е годы он существовал"))
    }
}
