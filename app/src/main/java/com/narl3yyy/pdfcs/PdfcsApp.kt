package com.narl3yyy.pdfcs

import android.app.Application
import com.narl3yyy.pdfcs.engine.DocumentSession
import com.narl3yyy.pdfcs.library.AppSettings
import com.narl3yyy.pdfcs.library.DocumentRepository
import com.narl3yyy.pdfcs.library.ScanSession
import com.narl3yyy.pdfcs.work.Notifications
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

class PdfcsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        AppGraph.init(this)
        Notifications.createChannels(this)
    }
}

/** Process-wide singletons. The app is small enough that a DI framework would be noise. */
object AppGraph {
    lateinit var app: Application
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var session: ScanSession
        private set
    lateinit var documents: DocumentRepository
        private set

    private val open = HashMap<String, DocumentSession>()

    fun init(app: Application) {
        this.app = app
        settings = AppSettings(app)
        session = ScanSession(app)
        documents = DocumentRepository(app)
    }

    /** The editing session for [file], shared by the reader and the page organiser. */
    @Synchronized
    fun sessionFor(file: File): DocumentSession =
        open.getOrPut(file.canonicalPath) { DocumentSession(file, app.cacheDir) }

    @Synchronized
    fun closeSession(file: File) {
        open.remove(file.canonicalPath)?.close()
    }
}
