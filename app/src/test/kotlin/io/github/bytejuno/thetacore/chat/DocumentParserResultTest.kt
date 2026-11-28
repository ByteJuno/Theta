package io.github.bytejuno.thetacore.chat

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class DocumentParserResultTest {
    private fun payload(length: Int = 5, bytes: ByteArray = "hello".toByteArray()) = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use {
            it.writeUTF("OK"); it.writeUTF("sample.txt"); it.writeUTF("text/plain")
            it.writeLong(5); it.writeInt(length); it.write(bytes)
        }
    }.toByteArray()
    @Test fun acceptsCompleteBoundedPayload() {
        val result = readDocumentParserResult(DataInputStream(payload().inputStream()))
        assertEquals("hello", result.text); assertEquals("sample.txt", result.name)
    }
    @Test fun rejectsTruncatedExtraAndUnboundedPayloads() {
        for (bytes in listOf(payload(bytes = "hel".toByteArray()), payload(length = 4), payload(length = Int.MAX_VALUE))) {
            try { readDocumentParserResult(DataInputStream(bytes.inputStream())); fail("incomplete payload accepted") }
            catch (_: IOException) { }
        }
    }
}
