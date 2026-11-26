package ru.vtext.app

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.vtext.app.ui.reader.sentenceRange

/** Границы предложения для выделения удержанием. */
class SentenceRangeTest {

    private fun selected(text: String, offset: Int): String {
        val range = sentenceRange(text, offset)
        return text.substring(range.first, range.last + 1)
    }

    @Test
    fun takesCurrentSentence() {
        val text = "Первое предложение. Второе предложение!"
        assertEquals("Первое предложение.", selected(text, 3))
        assertEquals("Второе предложение!", selected(text, 25))
    }

    @Test
    fun keepsAbbreviationsInside() {
        val text = "Это было в 1892 г. и осталось в памяти. Дальше другое."
        assertEquals("Это было в 1892 г. и осталось в памяти.", selected(text, 5))
    }

    @Test
    fun keepsInitialsInside() {
        val text = "Об этом писал А. П. Чехов в письме. Потом забыли."
        assertEquals("Об этом писал А. П. Чехов в письме.", selected(text, 3))
    }

    @Test
    fun keepsGluedInitialsInside() {
        val text = "Об этом писал М.А. Булгаков в письме. Потом забыли."
        assertEquals("Об этом писал М.А. Булгаков в письме.", selected(text, 3))
    }

    @Test
    fun keepsGluedInitialsOfThree() {
        val text = "Написал А.С.Пушкин роман. Потом письма."
        assertEquals("Написал А.С.Пушкин роман.", selected(text, 2))
    }

    @Test
    fun keepsGluedAbbreviations() {
        val text = "Взяли яблоки, груши и т.п. и поехали дальше. Потом стемнело."
        assertEquals("Взяли яблоки, груши и т.п. и поехали дальше.", selected(text, 5))
    }

    @Test
    fun questionMarkClosesSentence() {
        val text = "Кто здесь был? Никто не ответил."
        assertEquals("Кто здесь был?", selected(text, 4))
    }

    @Test
    fun keepsClosingQuote() {
        val text = "Он крикнул: «Стой!» И убежал."
        assertEquals("Он крикнул: «Стой!»", selected(text, 4))
    }

    @Test
    fun lastSentenceWithoutDot() {
        val text = "Первое. Второе без точки"
        assertEquals("Второе без точки", selected(text, 12))
    }

    @Test
    fun ellipsisClosesSentence() {
        val text = "Он думал… Потом решил."
        assertEquals("Он думал…", selected(text, 2))
    }

    @Test
    fun offsetIsClamped() {
        val text = "Одно предложение."
        assertEquals("Одно предложение.", selected(text, 500))
        assertEquals("Одно предложение.", selected(text, -3))
    }
}
