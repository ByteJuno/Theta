package io.github.bytejuno.thetacore.agent.ledger

import kotlinx.serialization.json.*

/** Storage encoding v1 interns only result/evidence text within one atomic record file.
 * Aggregate formatVersion remains 2. Legacy inline-text files are read without rewriting them.
 * No hashes, sidecar blobs or multi-file commit protocol are needed for text equality.
 */
internal class InvocationRecordCodec(private val json: Json) {
    fun encode(record: InvocationRecord): String {
        val texts = linkedMapOf<String, Int>()
        val body = transform(json.encodeToJsonElement(record).jsonObject) { value ->
            val text = value.jsonPrimitive.also { require(it.isString) }.content
            // Intern what UTF-8 will store: unpaired surrogates become '?', so distinct in-memory
            // strings can collapse and would otherwise fail the duplicate check on the next open.
            JsonPrimitive(texts.getOrPut(String(text.toByteArray(Charsets.UTF_8), Charsets.UTF_8)) { texts.size })
        }
        return JsonObject(body + mapOf(
            "storageVersion" to JsonPrimitive(1),
            "texts" to JsonArray(texts.keys.map(::JsonPrimitive)),
        )).toString()
    }

    fun decode(encoded: String): InvocationRecord {
        val stored = json.parseToJsonElement(encoded).jsonObject
        if ("storageVersion" !in stored) return json.decodeFromJsonElement(stored)
        require(stored.getValue("storageVersion") == JsonPrimitive(1)) { "Unsupported storage encoding" }
        val texts = stored.getValue("texts").jsonArray.map {
            it.jsonPrimitive.also { text -> require(text.isString) }.content
        }
        require(texts.distinct().size == texts.size) { "Duplicate interned text" }
        val body = JsonObject(stored - "storageVersion" - "texts")
        val expanded = transform(body) { value ->
            val reference = value.jsonPrimitive
            require(!reference.isString) { "Expected text reference" }
            val index = requireNotNull(reference.intOrNull) { "Invalid text reference" }
            require(index in texts.indices) { "Missing text reference" }
            JsonPrimitive(texts[index])
        }
        return json.decodeFromJsonElement(expanded)
    }

    private fun transform(body: JsonObject, text: (JsonElement) -> JsonElement): JsonObject {
        fun result(value: JsonElement): JsonElement = if (value == JsonNull) value else {
            val fields = value.jsonObject
            JsonObject(fields + ("text" to text(fields.getValue("text"))))
        }
        return JsonObject(body + mapOf(
            "result" to result(body.getValue("result")),
            "evidence" to JsonArray(body.getValue("evidence").jsonArray.map { item ->
                val fields = item.jsonObject
                JsonObject(fields + mapOf(
                    "text" to text(fields.getValue("text")),
                    "outcome" to result(fields.getValue("outcome")),
                ))
            }),
        ))
    }
}

/** Detach the only collection-valued fields before publishing or retaining a snapshot. */
internal fun InvocationRecord.detached(): InvocationRecord = copy(
    pending = pending?.copy(unknownCalls = pending.unknownCalls.toList()),
    receipts = receipts.map { it.copy(request = it.request.copy(unknownCalls = it.request.unknownCalls.toList())) },
    evidence = evidence.toList(),
)
