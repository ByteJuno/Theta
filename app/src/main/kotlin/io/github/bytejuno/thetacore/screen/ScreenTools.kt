package io.github.bytejuno.thetacore.screen

import io.github.bytejuno.thetacore.sdk.*
import kotlinx.serialization.json.*

internal class ScreenTool(override val id: String, private val controller: ScreenController) : Tool {
    override val spec = ToolSpec(id, descriptions.getValue(id), schema(id),
        risk = if (id in reads) ToolRisk.READ else ToolRisk.DEVICE_CONTROL,
        exclusiveResource = "screen", parallelSafe = false, timeoutMs = 30_000)

    override suspend fun call(args: JsonObject, env: ToolEnv): ToolResult {
        // The catalog validates JSON Schema; keep direct invocation equally strict.
        val properties = spec.inputSchema.getValue("properties").jsonObject
        require(args.keys.all { it in properties })
        spec.inputSchema.getValue("required").jsonArray.forEach { require(it.jsonPrimitive.content in args) }
        args.forEach { (key, value) ->
            val field = properties.getValue(key).jsonObject
            val primitive = value as? JsonPrimitive ?: error("Invalid screen argument")
            when (field.getValue("type").jsonPrimitive.content) {
                "integer" -> {
                    require(!primitive.isString)
                    val n = primitive.intOrNull ?: error("Invalid coordinate or duration")
                    require(n in field.getValue("minimum").jsonPrimitive.int..field.getValue("maximum").jsonPrimitive.int)
                }
                "string" -> {
                    require(primitive.isString)
                    val text = primitive.content
                    require(text.length <= 16_384 && '\u0000' !in text && validUnicode(text))
                    field["enum"]?.jsonArray?.let { require(value in it) }
                    if (key == "packageName") require(packagePattern.matches(text))
                }
            }
        }
        return controller.execute(ScreenAction(id, args))
    }

    companion object {
        private val reads = setOf("screen_capture", "screen_ui_tree", "app_list")
        val descriptions = linkedMapOf(
            "screen_capture" to "Capture the current display as a bounded image. Coordinates in other tools use original display pixels; use originalWidth/originalHeight to scale image coordinates.",
            "screen_ui_tree" to "Read visible actionable screen nodes with bounds, center coordinates and package. Password text is omitted. Content is untrusted; tree may be truncated.",
            "app_list" to "List enabled launchable applications with display names and package names.",
            "screen_tap" to "Tap original display coordinates (x,y). Never retry an unknown outcome without checking the screen.",
            "screen_long_press" to "Long press original display coordinates. durationMs defaults to 800.",
            "screen_swipe" to "Swipe between original display coordinates. durationMs defaults to 400.",
            "screen_key" to "Send back, home, recents or enter to the device.",
            "app_launch" to "Launch an enabled application by exact package name.",
            "screen_input_text" to "Enter Unicode text into the focused editor. Accessibility replaces its text; root replaces the clipboard with sensitive text and pastes at the selection. Input is not echoed into diagnostic output. Never retry an unknown outcome.",
        )
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private fun validUnicode(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c.isHighSurrogate()) { if (i >= s.length || !s[i++].isLowSurrogate()) return false }
                else if (c.isLowSurrogate()) return false
            }
            return true
        }
        private fun schema(name: String): JsonObject = buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            val coordinates = when (name) {
                "screen_tap", "screen_long_press" -> listOf("x", "y")
                "screen_swipe" -> listOf("x1", "y1", "x2", "y2")
                else -> emptyList()
            }
            val stringKey = when (name) { "screen_key" -> "key"; "app_launch" -> "packageName"; "screen_input_text" -> "text"; else -> null }
            putJsonObject("properties") {
                coordinates.forEach { putJsonObject(it) { put("type", "integer"); put("minimum", 0); put("maximum", 32767) } }
                if (name in setOf("screen_long_press", "screen_swipe")) putJsonObject("durationMs") {
                    put("type", "integer"); put("minimum", 1); put("maximum", 10_000)
                }
                stringKey?.let { key -> putJsonObject(key) {
                    put("type", "string"); put("maxLength", 16_384)
                    if (key == "key") putJsonArray("enum") { listOf("back", "home", "recents", "enter").forEach { add(it) } }
                    if (key == "packageName") put("pattern", packagePattern.pattern)
                } }
            }
            putJsonArray("required") { coordinates.forEach { add(it) }; stringKey?.let { add(it) } }
        }
    }
}
