package io.github.bytejuno.thetacore.chat

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.agent.ledger.ComposerDocument
import io.github.bytejuno.thetacore.provider.ModelSelectionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "w640dp-h360dp-mdpi")
class DocumentComposerLayoutTest {
    private fun actions() = ChatUiActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        { _, _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, { _, _, _ -> }, { _, _ -> null }, { _, _ -> null })

    private fun semantics(view: View): SemanticsOwner? {
        val getter = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
        if (getter != null) return getter.invoke(view) as SemanticsOwner
        if (view is ViewGroup) for (index in 0 until view.childCount) semantics(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)

    private fun checkCompactComposer(documentCount: Int, images: List<PendingUserImage> = emptyList(), fontScale: Float = 1f) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val compose = ComposeView(activity)
            activity.setContentView(compose)
            val snapshot = ChatUiSnapshot(ChatState(loading = false, sessionId = "session"), ModelSelectionState(),
                ChatApprovalSettings(), ChatPersonaState(), "", ChatDraftSnapshot("hello", images, null,
                    (1..documentCount).map { ComposerDocument("$it", "file$it.txt", "text/plain", 5, "hello") }), 0)
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme { ChatComposerSurface(true, snapshot, actions()) }
                }
            }
            repeat(5) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                compose.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
                compose.layout(0, 0, 640, 260)
            }
            val all = nodes(requireNotNull(semantics(compose)).unmergedRootSemanticsNode)
            for (label in listOf(R.string.chat_send, R.string.chat_add_attachment)) {
                val control = all.first {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(activity.getString(label)) == true
                }
                assertTrue("Control must remain visible: ${control.boundsInRoot}", control.boundsInRoot.height >= 20f)
            }
            val input = all.first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
            assertTrue("Input must remain usable", input.boundsInRoot.height >= 48f)
            if (documentCount == 4) assertTrue("Overflowing attachments must remain scrollable", all.any {
                (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
            })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun emptyComposerKeepsInputAndSendVisible() = checkCompactComposer(0)
    @Test fun fourDocumentsScrollWithoutHidingInputOrSend() = checkCompactComposer(4)
    @Test fun mixedAttachmentsKeepControlsVisibleWithLargeText() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        checkCompactComposer(4, (1..4).map { PendingUserImage("image-$it", "image/png", bytes) }, 1.5f)
    }
}
