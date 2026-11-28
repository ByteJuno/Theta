package io.github.bytejuno.thetacore.chat

import org.junit.Assert.*
import org.junit.Test

class ToolInputDraftsTest {
    @Test fun recreatedFormKeepsPendingAnswerAndSubmissionState() {
        val drafts = ToolInputDrafts()
        drafts["request"].values = mapOf("message" to "unfinished answer", "confirmed" to "false")
        drafts.retain(setOf("request"))
        val returnedForm = drafts["request"]
        assertEquals("unfinished answer", returnedForm.values["message"])
        assertEquals("false", returnedForm.values["confirmed"])
        returnedForm.submitted = true
        assertTrue(drafts["request"].submitted)
    }

    @Test fun completedRequestsAreForgottenWhileOtherRequestsKeepTheirAnswers() {
        val drafts = ToolInputDrafts()
        drafts["finished"].values = mapOf("message" to "private answer")
        drafts["finished"].submitted = true
        drafts["pending"].values = mapOf("message" to "keep")
        drafts.retain(setOf("pending"))
        assertEquals("keep", drafts["pending"].values["message"])
        assertTrue(drafts["finished"].values.isEmpty())
        assertFalse(drafts["finished"].submitted)
        assertTrue(drafts["new-request"].values.isEmpty())
    }

    @Test fun inputDoesNotLeakIntoAnotherRouteOwner() {
        val first = ToolInputDrafts()
        first["request"].values = mapOf("message" to "private answer")
        val second = ToolInputDrafts()
        assertTrue(second["request"].values.isEmpty())
        first.retain(emptySet())
        assertTrue(first["request"].values.isEmpty())
    }
}
