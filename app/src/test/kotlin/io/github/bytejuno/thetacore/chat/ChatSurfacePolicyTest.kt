package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import io.github.bytejuno.thetacore.agent.run.SessionPreview
import io.github.bytejuno.thetacore.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ChatSurfacePolicyTest {
    @Test fun themeUsesBrandLightDarkAndRejectsMalformedColors() {
        val light = thetaBrandScheme(false)
        val dark = thetaBrandScheme(true)
        assertEquals(0xFF315DCE.toInt(), light.primary.toArgb())
        assertEquals(0xFFFAF9F7.toInt(), light.surface.toArgb())
        assertEquals(0xFF121316.toInt(), dark.surface.toArgb())
        assertEquals(0xFFFFC94D.toInt(), thetaTokens(dark).brandAccent.toArgb())
        assertEquals(light.primaryContainer, thetaTokens(light).userBubbleContainer)
        assertNotEquals(thetaTokens(light).composerContainer, thetaTokens(dark).composerContainer)
        assertEquals(brandColor("#aAbBcC"), brandColor("#AABBCC"))
        for (invalid in listOf("red", "#123", "#11223344", "112233", "#GG1234"))
            assertThrows(IllegalArgumentException::class.java) { brandColor(invalid) }
    }

    @Test fun groupingUsesLocalDatesAcrossDstAndSearchPreservesOrder() {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 11, 1)
        fun entry(date: LocalDate, text: String) = SessionPreview("${date.atStartOfDay(zone).toInstant().toEpochMilli()}-id", text)
        val current = entry(today, "你好 World")
        val yesterday = entry(today.minusDays(1), "old world")
        val earlier = SessionPreview("legacy-uuid", "Earlier")
        val groups = groupedSessions(listOf(current, yesterday, earlier), "", today, zone)
        assertEquals(listOf(SessionDay.TODAY, SessionDay.YESTERDAY, SessionDay.EARLIER), groups.keys.toList())
        assertEquals(listOf(current, yesterday), groupedSessions(listOf(current, yesterday, earlier), " WORLD ", today, zone).values.flatten())
        assertEquals(listOf(current), groupedSessions(listOf(current, yesterday), "你好", today, zone).values.flatten())
        assertTrue(groupedSessions(listOf(current), "missing", today, zone).isEmpty())
        assertEquals(SessionDay.EARLIER, sessionDay("invalid", today, zone))
        assertEquals(SessionDay.EARLIER, sessionDay("9999999999999999999999-x", today, zone))
    }

    private fun record() = InvocationRecord(2, 1,
        InvocationInput(BindingValue.copyOf(callBinding("test", "request", "response", 0, "wire")), "chain", "session", "run", "step", 0, "{}", null, false, Risk.READ, modelName = "tool"),
        null, Status.SUCCEEDED, Risk.READ, null, null, emptyList(), null,
        LogicalResult(Status.SUCCEEDED, Effect.NONE, "done", false), emptyList(), null, false, false)

    @Test fun approvalUnknownRecoveryAndInputCannotBeCollapsed() {
        val done = record()
        assertFalse(forceToolExpanded(null))
        assertFalse(forceToolExpanded(done))
        assertFalse(forceToolExpanded(done.copy(state = Status.RUNNING)))
        assertTrue(forceToolExpanded(done, inputRequired = true))
        assertTrue(forceToolExpanded(done.copy(result = done.result!!.copy(effect = Effect.UNKNOWN))))
        assertTrue(forceToolExpanded(done.copy(recoveryPending = true)))
        val pending = done.copy(pending = ApprovalRequest("request", "call", "digest", "version", 1, Risk.WRITE, emptyList(), false))
        assertTrue(forceToolExpanded(pending))
        assertFalse(forceToolExpanded(pending.copy(input = pending.input.copy(unattended = true))))
    }
}
