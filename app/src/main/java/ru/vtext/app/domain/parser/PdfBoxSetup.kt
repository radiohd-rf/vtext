package ru.vtext.app.domain.parser

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Ленивая инициализация PDFBox: `PDFBoxResourceLoader.init` заметно тормозит
 * холодный старт, а нужен только при разборе PDF. Вызываем на IO-потоке
 * перед первым разбором, контекст приложения прикладывает [attach].
 */
object PdfBoxSetup {
    @Volatile
    private var context: Context? = null

    @Volatile
    private var ready = false

    fun attach(appContext: Context) {
        context = appContext.applicationContext
    }

    fun ensure() {
        if (ready) return
        val ctx = context ?: return
        synchronized(this) {
            if (!ready) {
                PDFBoxResourceLoader.init(ctx)
                ready = true
            }
        }
    }
}
