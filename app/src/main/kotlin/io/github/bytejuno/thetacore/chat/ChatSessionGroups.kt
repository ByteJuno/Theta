package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.run.SessionPreview
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class SessionDay { TODAY, YESTERDAY, EARLIER }
internal fun sessionDay(id: String, today: LocalDate, zone: ZoneId): SessionDay {
    // Chat and Entry sessions have a millisecond prefix; legacy opaque IDs stay in Earlier.
    val millis = id.substringBefore('-').toLongOrNull()?.takeIf { it >= 0 } ?: return SessionDay.EARLIER
    val date = runCatching { Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() }.getOrNull()
    return when (date) { today -> SessionDay.TODAY; today.minusDays(1) -> SessionDay.YESTERDAY; else -> SessionDay.EARLIER }
}
internal fun groupedSessions(sessions: List<SessionPreview>, query: String,
    today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): Map<SessionDay, List<SessionPreview>> {
    val filter = query.trim()
    val visible = sessions.filter { filter.isEmpty() || it.text.orEmpty().contains(filter, ignoreCase = true) }
    return SessionDay.entries.mapNotNull { day ->
        visible.filter { sessionDay(it.sessionId, today, zone) == day }.takeIf { it.isNotEmpty() }?.let { day to it }
    }.toMap()
}
