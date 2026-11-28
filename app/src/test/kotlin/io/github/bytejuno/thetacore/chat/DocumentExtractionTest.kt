package io.github.bytejuno.thetacore.chat

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import io.github.bytejuno.thetacore.agent.ledger.DocumentLimits
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DocumentExtractionTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun resources() { PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication()) }

    private fun extract(bytes: ByteArray, mime: String? = "text/plain") = DocumentExtraction.extract("sample.txt", mime, bytes, temp.root)
    private fun rejected(reason: DocumentFailure, action: () -> Unit) {
        try { action(); fail("expected $reason") } catch (error: DocumentException) { assertEquals(reason, error.reason) }
    }
    private fun pdf(pages: Int = 1, encrypted: Boolean = false, text: String? = "Real PDF attachment"): ByteArray = PDDocument().use { doc ->
        repeat(pages) {
            val page = PDPage(); doc.addPage(page)
            if (text != null) PDPageContentStream(doc, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f); it.newLineAtOffset(40f, 700f)
                it.showText(text); it.endText()
            }
        }
        if (encrypted) doc.protect(StandardProtectionPolicy("owner", "secret", AccessPermission()))
        ByteArrayOutputStream().also(doc::save).toByteArray()
    }

    @Test fun extractsUtf8TextCodeMarkdownJsonAndCsv() {
        for (mime in listOf("text/plain", "text/x-kotlin", "text/markdown", "application/json", "text/csv")) {
            val text = "文档\nval x = 42\nalpha,beta\n"
            assertEquals(text, extract(text.toByteArray(), mime).text)
        }
        assertEquals("hello", extract("\uFEFFhello".toByteArray()).text)
    }
    @Test fun extractsRealPdfWithProductionLibrary() {
        assertTrue(extract(pdf(), "application/pdf").text.contains("Real PDF attachment"))
    }
    @Test fun rejectsEncryptedEmptyDamagedAndTooManyPages() {
        rejected(DocumentFailure.ENCRYPTED) { extract(pdf(encrypted = true), "application/pdf") }
        rejected(DocumentFailure.EMPTY) { extract(pdf(text = null), "application/pdf") }
        rejected(DocumentFailure.TOO_MANY_PAGES) { extract(pdf(pages = 101, text = null), "application/pdf") }
        rejected(DocumentFailure.INVALID) { extract("%PDF-1.4\nbroken".toByteArray(), "application/pdf") }
        rejected(DocumentFailure.INVALID) { extract("not a pdf".toByteArray(), "application/pdf") }
    }
    @Test fun rejectsEmptyBinaryIllegalMimeMalformedUtf8AndLimits() {
        rejected(DocumentFailure.EMPTY) { extract(byteArrayOf()) }
        rejected(DocumentFailure.EMPTY) { extract(" \n".toByteArray()) }
        rejected(DocumentFailure.UNSUPPORTED) { extract(byteArrayOf(0, 1, 2)) }
        for (mime in listOf(null, "image/png", "text/../html", "text/plain;bad"))
            rejected(DocumentFailure.UNSUPPORTED) { extract("hello".toByteArray(), mime) }
        rejected(DocumentFailure.UNSUPPORTED) { DocumentExtraction.extract("data.bin", "application/octet-stream", "hello".toByteArray(), temp.root) }
        val code = DocumentExtraction.extract("Main.kt", "application/octet-stream", "val x = 1".toByteArray(), temp.root)
        assertEquals("val x = 1", code.text)
        rejected(DocumentFailure.INVALID) { extract(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejected(DocumentFailure.TOO_LARGE) { DocumentExtraction.readBounded(ByteArray(DocumentLimits.BYTES + 1).inputStream()) }
        rejected(DocumentFailure.TOO_LONG) { extract("a".repeat(DocumentLimits.CHARACTERS + 1).toByteArray()) }
        assertEquals(DocumentLimits.CHARACTERS, extract("a".repeat(DocumentLimits.CHARACTERS).toByteArray()).text.length)
    }
    @Test fun boundsPdfTextAndRemovesFilenameControlCharacters() {
        rejected(DocumentFailure.TOO_LONG) { extract(pdf(text = "a".repeat(DocumentLimits.CHARACTERS + 1)), "application/pdf") }
        val doc = DocumentExtraction.extract("bad\n\u202Ename.md", "text/markdown", "hello".toByteArray(), temp.root)
        assertEquals("badname.md", doc.name)
    }
}
