package io.github.bytejuno.thetacore.screen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScreenTreeTest {
    @Test fun xmlDropsNoiseRedactsPasswordsAndKeepsCoordinatesAndUnicode() {
        val tree = parseScreenXml("""<hierarchy>
            <node package="test.app" class="android.widget.Button" text="确定😀" bounds="[10,20][50,80]" clickable="true" />
            <node text="invisible" bounds="[0,0][20,20]" visible-to-user="false" />
            <node text="empty bounds" bounds="[0,0][0,20]" />
            <node class="android.view.View" bounds="[0,0][20,20]" />
            <node class="android.widget.EditText" text="secret" content-desc="password secret" password="true" bounds="[20,30][80,90]" />
        </hierarchy>""")
        assertEquals("test.app", tree["package"]!!.jsonPrimitive.content)
        val nodes = tree["nodes"]!!.jsonArray
        assertEquals(2, nodes.size)
        assertEquals("确定😀", nodes[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("[30,50]", nodes[0].jsonObject["center"].toString())
        assertEquals("Button", nodes[0].jsonObject["class"]!!.jsonPrimitive.content)
        assertFalse(tree.toString().contains("secret"))
        assertTrue(nodes[1].jsonObject["editable"]!!.jsonPrimitive.boolean)
    }
    @Test fun outputIsBoundedAndUnicodeTruncationDoesNotSplitSurrogates() {
        val nodes = (1..1000).map { ScreenNode("app", "View", "😀中文".repeat(500), "\n".repeat(200), 0, 0, 100, 100, true, true, false, false) }
        val result = compactScreenTree(nodes)
        assertTrue(result.toString().toByteArray().size <= SCREEN_TREE_BYTES)
        assertTrue(result["nodes"]!!.jsonArray.size <= SCREEN_NODE_LIMIT)
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("😀", shortScreenText("😀x", 1))
        assertThrows(IllegalArgumentException::class.java) { parseScreenXml("<!DOCTYPE a [<!ENTITY b SYSTEM 'file:///secret'>]><a/>") }
    }
}
