package io.github.bytejuno.thetacore.agent.run

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.agent.protocol.*
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class SessionPreviewTest {
    private fun database() = Files.createTempDirectory("session-preview").resolve(AgentDatabase.FILE_NAME)
    private fun user(text: String) = message(Role.USER, textPart(text))

    @Test fun firstUserTextIsBoundedPersistentAndIndependentOfHistoryLength() {
        val file = database()
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "session").use { h ->
                assertNull(db.sessionPreview("session").text)
                h.append(0, listOf(message(Role.ASSISTANT, textPart("not the preview"))))
                h.append(1, listOf(user(" \n第一条\t\t消息\u00a0" + "🐈".repeat(500_000))))
                repeat(120) { h.append(h.revision(), listOf(user("later $it"))) }
                val before = h.io()
                val preview = db.sessionPreviews().single().text!!
                assertTrue(preview.startsWith("第一条 消息 "))
                assertTrue(preview.endsWith("…"))
                assertEquals(60, preview.codePointCount(0, preview.length))
                assertEquals(before, h.io(), "Listing must not decode or open any history")
                assertTrue(HistoryCodec.isUtf8(preview))
            }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertTrue(db.sessionPreviews().single().text!!.startsWith("第一条 消息 "))
        }
    }

    @Test fun blankFirstTextRemainsFallbackAndAssistantTextDoesNotSupplyTitle() {
        AgentDatabase.open(database(), BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "session").use { h ->
                h.append(0, listOf(message(Role.ASSISTANT, textPart("answer"))))
                assertNull(db.sessionPreview("session").text)
                h.append(1, listOf(user("\n \t")))
                h.append(2, listOf(user("second question")))
                assertNull(db.sessionPreview("session").text)
                assertEquals("1", db.sessionPreviewValue("session"))
            }
        }
        assertEquals("a b", compactSessionPreview(sequenceOf(" a\n", "\t b ")))
        assertEquals("x".repeat(60), compactSessionPreview(sequenceOf("x".repeat(60))))
        assertEquals("x".repeat(59) + "…", compactSessionPreview(sequenceOf("x".repeat(61))))
    }

    @Test fun shapingCharactersPreserveEmojiAndWordsAcrossColdOpen() {
        val file = database()
        val examples = listOf(
            "👩\u200D💻",
            "👨\u200D👩\u200D👧\u200D👦",
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
            "می\u200Cروم",
        )
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            examples.forEachIndexed { index, text ->
                SqliteRunHistory.create(db, "shaping-$index").use { h ->
                    h.append(0, listOf(user("\n$text\t")))
                }
            }
            SqliteRunHistory.create(db, "invisible").use { h ->
                h.append(0, listOf(user("\u200C\u200D \uDB40\uDC67\uDB40\uDC7F")))
                h.append(1, listOf(user("later text")))
            }
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            examples.forEachIndexed { index, text -> assertEquals(text, db.sessionPreview("shaping-$index").text) }
            assertNull(db.sessionPreview("invisible").text)
            assertEquals("1", db.sessionPreviewValue("invisible"))
        }
        assertEquals("a b", compactSessionPreview(sequenceOf("a\u202E\t\u200D b")))
        val bounded = compactSessionPreview(sequenceOf("👩\u200D💻".repeat(100)))
        assertEquals(60, bounded.codePointCount(0, bounded.length))
        assertTrue(bounded.endsWith("…"))
        assertEquals(bounded, compactSessionPreview(sequenceOf(bounded)))
        assertTrue(HistoryCodec.isUtf8(bounded))
    }

    @Test fun legacyOversizedFirstBatchAndUnreadCorruptHistoryAreNeverDecodedByList() {
        val file = database()
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            SqliteRunHistory.create(db, "legacy").use { h ->
                db.connection.execute("DELETE FROM history_values WHERE name='session-preview'")
                h.append(0, listOf(user("x".repeat(MAX_RUNTIME_BATCH_BYTES.toInt() + 1))))
                h.append(1, listOf(user("later")))
                val before = h.io()
                assertNull(db.sessionPreviews().single().text)
                assertEquals(before, h.io())
            }
            // Listing cannot rely on opening HEAD or on decoding even a small history batch.
            db.connection.execute("UPDATE history_chunks SET bytes=x'00' WHERE name='head'")
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertEquals(listOf(SessionPreview("legacy", null)), db.sessionPreviews())
        }
    }

    @Test fun pagedMetadataIsDeletedWithSessionAndOtherSessionsSurvive() {
        val file = database()
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            repeat(55) { index ->
                val id = index.toString().padStart(3, '0')
                SqliteRunHistory.create(db, id).use { it.append(0, listOf(user("Question $index"))) }
            }
            val first = db.sessionPreviews()
            val second = db.sessionPreviews(first.last().sessionId)
            assertEquals(50, first.size); assertEquals(5, second.size)
            assertEquals(55, (first + second).map { it.sessionId }.distinct().size)
            assertEquals("Question 0", second.last().text)
            SqliteInvocationStore.open(db).use { store ->
                val ledger = InvocationLedger(store, DefinitionGate(), ArgumentsValidator { _, _ -> true })
                ledger.recoverColdStart("preview-test")
                ledger.deleteSession("054")
            }
            assertNull(db.sessionPreview("054").text)
            assertEquals(0, db.connection.longValue("SELECT count(*) FROM history_chunks WHERE session_id='054'"))
            assertFails { SqliteRunHistory.create(db, "054") }
            SqliteRunHistory.create(db, "new").use { assertNull(db.sessionPreview("new").text) }
            assertEquals("Question 53", db.sessionPreview("053").text)
        }
        AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
            assertFalse(db.sessionIds().contains("054"))
            assertEquals("Question 53", db.sessionPreview("053").text)
        }
    }

    @Test fun previewAndFirstInputCommitTogetherOrRollbackTogether() {
        for (stage in listOf(HistoryWriteStage.BEFORE_COMMIT, HistoryWriteStage.AFTER_COMMIT)) {
            val file = database()
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "s", fault = { point ->
                    if (point.file == "append" && point.stage == stage) error("injected")
                }).use { h -> assertFailsWith<HistoryStorageException> { h.append(0, listOf(user("first"))) } }
            }
            AgentDatabase.open(file, BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.open(db, "s").use { h ->
                    val committed = stage == HistoryWriteStage.AFTER_COMMIT
                    assertEquals(if (committed) 1L else 0L, h.revision())
                    assertEquals(if (committed) "first" else null, db.sessionPreview("s").text)
                    if (!committed) { h.append(0, listOf(user("retry"))); assertEquals("retry", db.sessionPreview("s").text) }
                }
            }
        }
    }

    @Test fun corruptMetadataFallsBackWithoutUnboundedProjectionOrHistoryReads() {
        for (damage in listOf("oversized", "ordinal", "truncated", "text", "extra")) {
            AgentDatabase.open(database(), BundledSQLiteDriver()).use { db ->
                SqliteRunHistory.create(db, "s").use { h ->
                    h.append(0, listOf(user("title")))
                    when (damage) {
                        "oversized" -> db.connection.execute("UPDATE history_chunks SET bytes=zeroblob(10000000) WHERE name='session-preview'")
                        "ordinal" -> db.connection.execute("UPDATE history_chunks SET ordinal=-1 WHERE name='session-preview'")
                        "truncated" -> db.connection.execute("UPDATE history_chunks SET bytes=x'54' WHERE name='session-preview'")
                        "text" -> db.connection.execute("UPDATE history_chunks SET bytes='invalid' WHERE name='session-preview'")
                        "extra" -> db.connection.execute("INSERT INTO history_chunks VALUES('s','session-preview',1,x'00')")
                    }
                    val before = h.io()
                    assertNull(db.sessionPreviews().single().text, damage)
                    assertEquals(before, h.io())
                    h.append(1, listOf(user("do not replace damaged first preview")))
                    assertNull(db.sessionPreview("s").text)
                }
            }
        }
    }
}
