package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.UiText
import io.github.bytejuno.thetacore.uiText
import io.github.bytejuno.thetacore.agent.protocol.ModelErrorCode
import io.github.bytejuno.thetacore.agent.run.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class ChatDiagnosticsTest {
    private fun state(result: RunResult) = ChatState(loading = false, sessionId = "one", result = result)
    private val failure = RunResult(RunState.FAILED, "Model error: AUTH", 3, 2)

    @Test fun failedAndPausedExposeOnlyReasonCountsAndUnknownFlag() {
        val failed = requireNotNull(chatDiagnostic(state(failure)))
        assertEquals(uiText(R.string.chat_diagnostic_failed), failed.arguments[0])
        assertEquals(uiText(R.string.chat_diagnostic_model, "AUTH"), failed.arguments[1])
        assertEquals(3, failed.arguments[2]); assertEquals(2, failed.arguments[3])
        assertEquals(uiText(R.string.chat_diagnostic_not_reported), failed.arguments[4])
        val paused = requireNotNull(chatDiagnostic(state(RunResult(RunState.PAUSED, "Step limit", 50, 12, true))))
        assertEquals(uiText(R.string.chat_diagnostic_paused), paused.arguments[0])
        assertEquals(uiText(R.string.chat_diagnostic_budget), paused.arguments[1])
        assertEquals(50, paused.arguments[2]); assertEquals(12, paused.arguments[3])
        assertEquals(uiText(R.string.chat_diagnostic_unknown), paused.arguments[4])
        for (code in ModelErrorCode.entries)
            assertEquals(uiText(R.string.chat_diagnostic_model, code.name), diagnosticReason("Model error: $code"))
    }

    @Test fun successNewRunLoadingAndEmptySessionHidePreviousFailure() {
        val failed = state(failure)
        assertNotNull(chatDiagnostic(failed))
        assertNull(chatDiagnostic(failed.copy(result = null)))
        assertNull(chatDiagnostic(failed.copy(active = true)))
        assertNull(chatDiagnostic(failed.copy(loading = true)))
        assertNull(chatDiagnostic(failed.copy(sessionId = null)))
        assertNull(chatDiagnostic(failed.copy(result = failure.copy(state = RunState.COMPLETED))))
        val next = failed.copy(sessionId = "two", result = failure.copy(reason = "Model error: QUOTA"))
        assertEquals(uiText(R.string.chat_diagnostic_model, "QUOTA"), chatDiagnostic(next)!!.arguments[1])
    }

    @Test fun unknownBodiesHeadersTokensAndStacksNeverReachDisplayArguments() {
        val secrets = listOf("sk-proj-secretValue123", "arbitrary-key-without-prefix", "eyJhbGciOiJIUzI1NiJ9.secret.signature")
        for (secret in secrets) for (reason in listOf(
            secret, "Authorization: Bearer $secret", "authorization: Basic $secret", "api_key=$secret",
            "{\"error\":{\"message\":\"$secret\"}}", "java.lang.IllegalStateException: $secret\n at SomeClass.run(Some.kt:1)",
            "Model error: AUTH\nAuthorization: Bearer $secret", "Model error: AUTH $secret",
            "Tool step interrupted: $secret", "Persisted checkpoint requires explicit continuation or user review: $secret",
            "Request budget exceeded; checkpoint required; checkpoint $secret",
            "Model error: AUTH\u202E$secret", "Bearer\n$secret",
        )) {
            val result = requireNotNull(chatDiagnostic(state(failure.copy(reason = reason))))
            for (locale in listOf("values", "values-zh")) {
                val rendered = render(result, locale)
                assertFalse(rendered, rendered.contains(secret))
                assertFalse(rendered, rendered.contains("Authorization"))
                assertFalse(rendered, rendered.contains("SomeClass"))
                assertTrue(rendered.length < 600)
            }
        }
    }

    @Test fun oversizedReasonIsRejectedWithoutScanningOrCopyingIt() {
        val huge = "Authorization: Bearer " + "s".repeat(2_000_000)
        assertEquals(uiText(R.string.chat_diagnostic_omitted), diagnosticReason(huge))
        assertEquals(uiText(R.string.chat_diagnostic_omitted), diagnosticReason("x".repeat(MAX_DIAGNOSTIC_REASON_INPUT + 1)))
        assertEquals(uiText(R.string.chat_diagnostic_omitted), diagnosticReason(""))
        val diagnostic = chatDiagnostic(state(failure.copy(reason = huge, steps = Int.MAX_VALUE, retries = Int.MIN_VALUE)))!!
        assertEquals(0, diagnostic.arguments[3])
        for (locale in listOf("values", "values-zh")) assertTrue(render(diagnostic, locale).length < 600)
    }

    @Test fun persistedCheckpointSuffixIsRemovedAndBothLocalesAreReadable() {
        assertEquals(uiText(R.string.chat_diagnostic_context), diagnosticReason(
            "Request budget exceeded; checkpoint required; checkpoint secret-id; reduce input or choose a larger window"))
        val diagnostic = chatDiagnostic(state(failure))!!
        val english = render(diagnostic, "values")
        val chinese = render(diagnostic, "values-zh")
        assertTrue(english.contains("Model error code: AUTH")); assertTrue(english.contains("Steps: 3"))
        assertTrue(chinese.contains("模型错误码：AUTH")); assertTrue(chinese.contains("重试次数：2"))
        assertTrue(english.contains("does not prove")); assertTrue(chinese.contains("不代表"))
    }

    private val resources = mutableMapOf<String, Map<Int, String>>()
    private fun render(text: UiText, locale: String): String {
        val values = resources.getOrPut(locale) {
            val base = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(base, "$locale/strings.xml"))
            val nodes = document.getElementsByTagName("string")
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as org.w3c.dom.Element
                R.string::class.java.getField(element.getAttribute("name")).getInt(null) to element.textContent.replace("\\n", "\n")
            }
        }
        return String.format(Locale.ROOT, values.getValue(text.key), *text.arguments.map {
            if (it is UiText) render(it, locale) else it
        }.toTypedArray())
    }
}
