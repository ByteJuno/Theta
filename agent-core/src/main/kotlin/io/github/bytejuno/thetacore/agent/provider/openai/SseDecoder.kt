package io.github.bytejuno.thetacore.agent.provider.openai

import io.github.bytejuno.thetacore.agent.protocol.protocol
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Streaming byte parser. The larger wire-frame cap is distinct from the 64 KiB model-event cap. */
internal class SseDecoder(private val maxFrameBytes: Int = 8 * 1024 * 1024) {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    private var dataSeen = false
    private var event: String? = null
    private var afterCr = false
    private var firstLine = true
    private var frameBytes = 0

    suspend fun feed(bytes: ByteArray, receive: suspend (String?, String?, Boolean) -> Unit) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            if (afterCr) {
                afterCr = false
                if (value == 10) continue
            }
            if (value == 13 || value == 10) {
                finishLine(receive)
                afterCr = value == 13
            } else {
                frameBytes++
                protocol(frameBytes <= maxFrameBytes, "SSE frame exceeds wire limit")
                line.write(value)
            }
        }
    }

    private suspend fun finishLine(receive: suspend (String?, String?, Boolean) -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var value = decoder.decode(ByteBuffer.wrap(line.toByteArray())).toString()
        line.reset()
        if (firstLine) { value = value.removePrefix("﻿"); firstLine = false }
        if (value.isEmpty()) {
            if (dataSeen || event != null) receive(event, if (dataSeen) data.toString().removeSuffix("\n") else null, false)
            data.setLength(0); dataSeen = false; event = null; frameBytes = 0
        } else if (value.startsWith(':')) {
            receive(null, null, true)
        } else {
            val colon = value.indexOf(':')
            val field = if (colon < 0) value else value.substring(0, colon)
            val content = if (colon < 0) "" else value.substring(colon + 1).removePrefix(" ")
            when (field) {
                "data" -> { dataSeen = true; data.append(content).append('\n') }
                "event" -> event = content
            }
        }
    }
    fun endOfInput() {
        protocol(line.size() == 0 && !dataSeen && event == null, "Incomplete SSE frame at EOF")
    }
}
