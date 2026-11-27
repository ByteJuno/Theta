package io.github.bytejuno.thetacore.chat

import androidx.compose.runtime.saveable.SaverScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.plugin.AndroidAgentStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatCompactLayoutTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun usesRemainingWindowHeightIncludingImeAndFontScaleInsteadOfOrientation() {
        assertFalse(compactChatWindow(800f, 1f))
        assertTrue(compactChatWindow(360f, 1f)) // phone landscape
        assertTrue(compactChatWindow(260f, 1f)) // split window or IME
        assertTrue(compactChatWindow(620f, 1.5f)) // large text needs the compact controls too
        assertFalse(compactChatWindow(480f, 1f))
        assertTrue(compactChatWindow(479f, 0.85f))
    }

    @Test fun sendAndAttachmentControlsPreserveLoadingSavingAndRunGuards() {
        val draft = ChatComposerDraft("hello")
        val ready = ChatState(loading = false, sessionId = "session")
        assertEquals(ChatComposerAvailability(true, true, true, false), chatComposerAvailability(ready, false, draft))
        assertEquals(ChatComposerAvailability(false, false, false, false), chatComposerAvailability(ready, true, draft))
        assertEquals(ChatComposerAvailability(false, false, false, false), chatComposerAvailability(ready.copy(loading = true), false, draft))
        for (busy in listOf(ready.copy(active = true), ready.copy(pending = true))) {
            assertEquals(ChatComposerAvailability(false, false, false, true), chatComposerAvailability(busy, false, draft))
        }
        assertFalse(chatComposerAvailability(ready.copy(sessionId = null), false, draft).send)
        draft.edit(" ")
        assertFalse(chatComposerAvailability(ready, false, draft).send)
        draft.images = (1..MAX_USER_IMAGES_PER_MESSAGE).map { PendingUserImage("$it", "image/png", byteArrayOf(1)) }
        assertTrue(chatComposerAvailability(ready, false, draft).send)
        assertFalse(chatComposerAvailability(ready, false, draft).attach)
    }

    @Test fun draftTextIdentityAndImageBytesSurviveRepeatedActivitySaveRestore() {
        val folder = temp.newFolder()
        AndroidAgentStore.open(folder, BundledSQLiteDriver()).use { store ->
            store.createHistory("session").close()
            val saver = ChatComposerDrafts.saver { store.composerDrafts }
            var drafts = ChatComposerDrafts { store.composerDrafts }
            drafts["session"].edit("未发送草稿\nsecond line")
            val version = drafts["session"].textVersion
            val bytes = byteArrayOf(1, 2, 3, 4)
            drafts["session"].images = listOf(PendingUserImage("image-id", "image/png", bytes))
            repeat(3) {
                val token = with(saver) { requireNotNull(SaverScope { true }.save(drafts)) }
                drafts = requireNotNull(saver.restore(token))
                val restored = drafts["session"]
                assertEquals("未发送草稿\nsecond line", restored.input)
                assertEquals(version, restored.textVersion)
                assertEquals("image-id", restored.images.single().localId)
                assertArrayEquals(bytes, restored.images.single().bytes)
                assertTrue(chatComposerAvailability(ChatState(loading = false, sessionId = "session"), false, restored).send)
            }
        }
    }
}
