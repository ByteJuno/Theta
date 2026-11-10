package io.github.bytejuno.thetacore.agent.run

import io.github.bytejuno.thetacore.agent.ledger.Durability
import io.github.bytejuno.thetacore.agent.protocol.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.*

/** Reproducible local microbenchmark, not a cold-cache or Android latency claim.
 * 10,000 separate acknowledged transactions, alternating USER/ASSISTANT, 512 ASCII chars each.
 * Every append forces its batch and HEAD and atomically renames both. OS caches are NOT flushed.
 * Timings use monotonic nanoTime; counters are actual bytes passed through storage reads/writes,
 * not filesystem block traffic. create() initialization/locks/metadata I/O are excluded from counters.
 * No latency threshold: assert the bounded work and loaded-message counts, publish measured times.
 */
class HistoryPerformanceTest {
    @Test fun tenThousandMessagesIncrementalWritesAndBoundedPages() {
        val path = Files.createTempDirectory("history-10000")
        val durability = Durability.PROCESS_CRASH_ONLY
        lateinit var appendIo: HistoryIo
        lateinit var firstBatch: ByteArray
        var appendMs = 0.0
        var appendOneMs = 0.0
        var appendOneBytes = 0L
        FileRunHistory.create(path, "benchmark-session", durability).use { h ->
            val baseline = h.io()
            appendMs = measureNanoTime {
                repeat(10_000) { i ->
                    h.append(i.toLong(), listOf(message(if (i % 2 == 0) Role.USER else Role.ASSISTANT,
                        textPart(i.toString().padEnd(512, 'x')))))
                    if (i == 0) firstBatch = Files.readAllBytes(path.resolve("batches/1.json"))
                }
            } / 1e6
            appendIo = h.io()
            assertEquals(baseline.bytesRead, appendIo.bytesRead)
            assertEquals(0, appendIo.messagesDecoded)
            assertEquals(20_000, appendIo.filesWritten)
            val before = h.io()
            appendOneMs = measureNanoTime {
                h.append(10_000, listOf(message(Role.USER, textPart("extra".padEnd(512, 'x')))))
            } / 1e6
            appendOneBytes = h.io().bytesWritten - before.bytesWritten
            assertEquals(before.bytesRead, h.io().bytesRead)
            assertEquals(2, h.io().filesWritten - before.filesWritten)
            assertContentEquals(firstBatch, Files.readAllBytes(path.resolve("batches/1.json")))
        }
        lateinit var opened: FileRunHistory
        val openMs = measureNanoTime { opened = FileRunHistory.open(path, durability) } / 1e6
        opened.use { h ->
            val openIo = h.io()
            lateinit var recent: HistoryPage
            val recentMs = measureNanoTime { recent = h.recent(50) } / 1e6
            val recentIo = h.io()
            lateinit var older: HistoryPage
            val olderMs = measureNanoTime { older = h.page(recent.older, 50) } / 1e6
            val olderIo = h.io()
            assertEquals(1, openIo.messagesDecoded)
            assertEquals(50, recentIo.messagesDecoded - openIo.messagesDecoded)
            assertEquals(50, olderIo.messagesDecoded - recentIo.messagesDecoded)
            assertEquals(10_001, recent.totalMessages)
            assertEquals(50, recent.messages.size)
            assertEquals(50, older.messages.size)
            val newestFirstChunks = mutableListOf(recent.messages, older.messages)
            var cursor = older.older
            val allPagesMs = measureNanoTime {
                while (cursor != null) {
                    val page = h.page(cursor, 50)
                    newestFirstChunks += page.messages
                    cursor = page.older
                }
            } / 1e6
            val all = newestFirstChunks.asReversed().flatten()
            assertEquals(10_001, all.size)
            all.take(10_000).forEachIndexed { i, m ->
                assertEquals(i.toString().padEnd(512, 'x'), (m.parts.single() as ContentPart.Text).text)
            }
            val metrics = buildJsonObject {
                put("messages", 10_000); put("payloadAsciiChars", 512); put("transactions", 10_000)
                put("durability", durability.name); put("os", System.getProperty("os.name"))
                put("java", System.getProperty("java.version")); put("appendTotalMs", appendMs)
                put("appendMeanMs", appendMs / 10_000); put("appendBytesWritten", appendIo.bytesWritten)
                put("appendFilesWritten", appendIo.filesWritten); put("appendMessagesDecoded", appendIo.messagesDecoded)
                put("extraAppendMs", appendOneMs); put("extraAppendBytesWritten", appendOneBytes)
                put("reopenMs", openMs); put("reopenBytesRead", openIo.bytesRead)
                put("reopenFilesRead", openIo.filesRead); put("reopenMessagesDecoded", openIo.messagesDecoded)
                put("recent50Ms", recentMs); put("recent50BytesRead", recentIo.bytesRead - openIo.bytesRead)
                put("recent50FilesRead", recentIo.filesRead - openIo.filesRead)
                put("recent50MessagesDecoded", recentIo.messagesDecoded - openIo.messagesDecoded)
                put("older50Ms", olderMs); put("older50BytesRead", olderIo.bytesRead - recentIo.bytesRead)
                put("older50FilesRead", olderIo.filesRead - recentIo.filesRead)
                put("older50MessagesDecoded", olderIo.messagesDecoded - recentIo.messagesDecoded)
                put("remaining9901PagingMs", allPagesMs); put("allPagingMessages", all.size)
                put("allPagingBytesRead", h.io().bytesRead - openIo.bytesRead)
                put("diskBytes", Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() })
                put("osCacheFlushed", false); put("physicalPowerLossTested", false)
            }
            println("ISSUE19_PERFORMANCE=$metrics")
        }
    }
}
