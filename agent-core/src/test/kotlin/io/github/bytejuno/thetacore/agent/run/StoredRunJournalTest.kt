package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import io.github.bytejuno.thetacore.agent.protocol.*
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class StoredRunJournalTest {
    private val durability = Durability.PROCESS_CRASH_ONLY

    @Test fun uncertainJournalWriteRetainsOldOrCommittedReplyAndNeverChangesHistory() {
        for (stage in listOf(HistoryWriteStage.BEFORE_COMMIT, HistoryWriteStage.AFTER_COMMIT)) {
            val root = Files.createTempDirectory("journal-fault")
            TestRunHistory.create(root, "session", durability).use { disk ->
                disk.append(0, listOf(message(Role.USER, textPart("question"))))
                StoredRunJournal(disk).submitted("request", "target", ModelExecutionMode.GENERATION_ONLY)
            }
            var injected = false
            TestRunHistory.open(root, durability) { point ->
                if (point.file.startsWith("journal/") && point.stage == stage) {
                    injected = true; error("journal write fault")
                }
            }.use { disk ->
                assertFailsWith<HistoryStorageException> {
                    StoredRunJournal(disk).finished(ModelAttempt("request", "target", ModelExecutionMode.GENERATION_ONLY,
                        message(Role.ASSISTANT, textPart("拒答 \uD800")), Events.finished(FinishReason.REFUSAL), null, false))
                }
                assertTrue(injected)
                assertFails { disk.journalPage() }
            }
            TestRunHistory.open(root, durability).use { disk ->
                assertEquals(1, disk.snapshot().messages.size)
                val reply = disk.journalPage().replies.single()
                assertEquals(if (stage == HistoryWriteStage.BEFORE_COMMIT) "INTERRUPTED" else "REFUSAL", reply.reason)
                if (stage == HistoryWriteStage.AFTER_COMMIT)
                    assertEquals("拒答 \uD800", (reply.partial.parts.single() as ContentPart.Text).text)
            }
        }
    }

    @Test fun journalRejectsUnmatchedFinishesAndIsBoundedIndependentlyOfReplayHistory() {
        val root = Files.createTempDirectory("journal-history")
        TestRunHistory.create(root, "session", durability).use { disk ->
            val journal = StoredRunJournal(disk)
            journal.submitted("request", "target", ModelExecutionMode.GENERATION_ONLY)
            assertTrue(disk.journalPage(includeUnfinished = false).replies.isEmpty())
            assertEquals("INTERRUPTED", disk.journalPage().replies.single().reason)
            assertFails { journal.submitted("request", "target", ModelExecutionMode.GENERATION_ONLY) }
            assertFails { journal.finished(ModelAttempt("request", "wrong", ModelExecutionMode.GENERATION_ONLY,
                message(Role.ASSISTANT), Events.finished(FinishReason.REFUSAL), null, false)) }
            journal.finished(ModelAttempt("request", "target", ModelExecutionMode.GENERATION_ONLY,
                message(Role.ASSISTANT, textPart("blocked")), Events.finished(FinishReason.CONTENT_FILTER), tokenUsage(outputTokens = 2), false))
            assertEquals("CONTENT_FILTER", disk.journalPage(limit = 1).replies.single().reason)
            assertTrue(disk.runSnapshot().messages.isEmpty())
            assertEquals(0L, disk.revision())
        }
    }
}
