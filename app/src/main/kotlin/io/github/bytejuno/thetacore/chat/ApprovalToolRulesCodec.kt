package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.Risk
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlinx.serialization.json.*

/** A single escaped property keeps arbitrary tool keys out of the properties syntax. */
internal object ApprovalToolRulesCodec {
    fun encode(settings: ChatApprovalSettings): String {
        val value = buildJsonObject {
            put("version", 1)
            put("revoked", JsonArray(settings.revokedReceipts.sorted().map(::JsonPrimitive)))
            put("denied", JsonArray(settings.deniedTools.sorted().map(::JsonPrimitive)))
            put("risks", buildJsonObject { settings.riskOverrides.toSortedMap().forEach { (key, risk) -> put(key, risk.name) } })
        }
        // The byte-stream overload escapes UTF-16 code units, including unpaired surrogates
        // accepted in MCP keys. A Writer followed by UTF-8 would replace those units with '?'.
        return ByteArrayOutputStream().also { output ->
            Properties().apply { setProperty("toolRules", value.toString()) }.store(output, null)
        }.toString(Charsets.ISO_8859_1.name())
    }

    fun decode(props: Properties, base: ChatApprovalSettings): ChatApprovalSettings {
        val text = props.getProperty("toolRules")
        if (text == null) {
            require(props.getProperty("toolRulesVersion") == null) { "Truncated tool rules" }
            return base // legacy mode-only settings
        }
        require(props.getProperty("toolRulesVersion") == "1")
        val rules = Json.parseToJsonElement(text).jsonObject
        require(rules.getValue("version").jsonPrimitive.int == 1)
        fun strings(key: String) = rules.getValue(key).jsonArray.map {
            it.jsonPrimitive.also { value -> require(value.isString) }.content.also { value ->
                require(value.isNotBlank() && value.length <= 4096)
            }
        }.toSet()
        return base.copy(
            revokedReceipts = strings("revoked"),
            deniedTools = strings("denied").also { require(it.all { key -> '/' in key }) },
            riskOverrides = rules.getValue("risks").jsonObject.mapValues { (key, value) ->
                require('/' in key && key.length <= 4096)
                Risk.valueOf(value.jsonPrimitive.content)
            },
        )
    }
}
