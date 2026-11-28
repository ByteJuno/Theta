package io.github.bytejuno.thetacore.chat

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import io.github.bytejuno.thetacore.agent.ledger.DocumentLimits
import java.io.File
import java.io.InputStream
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal enum class DocumentFailure { UNSUPPORTED, TOO_LARGE, TOO_LONG, TOO_MANY_PAGES, ENCRYPTED, EMPTY, INVALID }
internal class DocumentException(val reason: DocumentFailure) : java.io.IOException(reason.name)

internal object DocumentExtraction {
    private val applications = setOf("application/json", "application/ld+json", "application/xml",
        "application/javascript", "application/x-javascript", "application/x-sh", "application/sql", "application/yaml")
    private val textExtensions = setOf("txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "kt", "kts", "java", "py",
        "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "rs", "go", "rb", "sh", "ps1", "sql", "xml", "yaml", "yml", "toml", "html", "css")
    val pickerTypes = arrayOf("text/*", "application/pdf", "application/octet-stream", *applications.toTypedArray())

    fun readBounded(input: InputStream, limit: Int = DocumentLimits.BYTES): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (n < 0) break
            if (n == 0) throw DocumentException(DocumentFailure.INVALID)
            if (output.size() > limit - n) throw DocumentException(DocumentFailure.TOO_LARGE)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }

    fun extract(name: String, mime: String?, bytes: ByteArray, scratch: File): ComposerDocument {
        if (bytes.isEmpty()) throw DocumentException(DocumentFailure.EMPTY)
        if (bytes.size > DocumentLimits.BYTES) throw DocumentException(DocumentFailure.TOO_LARGE)
        val supplied = mime?.lowercase(java.util.Locale.ROOT)
        val type = if (supplied == "application/octet-stream" && name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in textExtensions)
            "text/plain" else supplied
        val pdf = type == "application/pdf"
        if (!pdf && (type == null || !(type.startsWith("text/") && type.matches(Regex("text/[a-z0-9.+-]+"))) && type !in applications))
            throw DocumentException(DocumentFailure.UNSUPPORTED)
        val text = try {
            if (pdf) pdf(bytes, scratch) else {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            }
        } catch (error: DocumentException) { throw error }
        catch (_: InvalidPasswordException) { throw DocumentException(DocumentFailure.ENCRYPTED) }
        catch (_: Exception) { throw DocumentException(DocumentFailure.INVALID) }
        if (text.length > DocumentLimits.CHARACTERS) throw DocumentException(DocumentFailure.TOO_LONG)
        if (text.any { it.isISOControl() && it !in "\t\r\n" }) throw DocumentException(DocumentFailure.UNSUPPORTED)
        if (text.isBlank()) throw DocumentException(DocumentFailure.EMPTY)
        val safeName = name.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "").take(160).ifBlank { "document" }
        return ComposerDocument(UUID.randomUUID().toString(), safeName, requireNotNull(type), bytes.size.toLong(), text)
    }

    private fun pdf(bytes: ByteArray, scratch: File): String {
        if (!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) throw DocumentException(DocumentFailure.INVALID)
        val memory = MemoryUsageSetting.setupMixed(1024 * 1024, 24L * 1024 * 1024).setTempDir(scratch)
        PDDocument.load(bytes.inputStream(), memory).use { doc ->
            if (doc.isEncrypted) throw DocumentException(DocumentFailure.ENCRYPTED)
            if (doc.numberOfPages > DocumentLimits.PAGES) throw DocumentException(DocumentFailure.TOO_MANY_PAGES)
            var positions = 0
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    // Bound per-page layout buffers before writeText flushes that page.
                    if (++positions > DocumentLimits.CHARACTERS) throw DocumentException(DocumentFailure.TOO_LONG)
                    super.processTextPosition(text)
                }
            }
            val output = object : StringWriter() {
                private fun checkCount(count: Int) {
                    if (buffer.length > DocumentLimits.CHARACTERS - count) throw DocumentException(DocumentFailure.TOO_LONG)
                }
                override fun write(value: String, offset: Int, count: Int) { checkCount(count); super.write(value, offset, count) }
                override fun write(value: String) { checkCount(value.length); super.write(value) }
                override fun write(value: CharArray, offset: Int, count: Int) { checkCount(count); super.write(value, offset, count) }
                override fun write(value: Int) { checkCount(1); super.write(value) }
            }
            stripper.endPage = DocumentLimits.PAGES
            stripper.writeText(doc, output)
            return output.toString()
        }
    }
}
