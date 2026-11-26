package io.github.bytejuno.thetacore.agent.execution

import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.bytejuno.thetacore.agent.ledger.ArgumentsValidator
import kotlinx.serialization.json.JsonElement

/** Offline, bounded 2020-12 profile. Only bounded local definition references are admitted.
 * No default insertion or type coercion. Unsupported schemas fail closed at catalog admission.
 * Networknt 1.5.3 targets Java 8; Android packaging is checked by the host build, not device execution.
 */
class Schema202012Validator : ArgumentsValidator {
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val mapper = ObjectMapper()
    fun supported(schema: String): Boolean = runCatching {
        LocalSchemaReferences.expand(schema)
        factory.getSchema(schema)
        true
    }.getOrDefault(false)

    /** Model-only projection. Validation below deliberately compiles the original document. */
    fun expanded(schema: String): JsonElement {
        val result = LocalSchemaReferences.expand(schema)
        factory.getSchema(schema)
        return result
    }
    override fun valid(originalSchema: String, objectJson: String): Boolean =
        objectJson.length <= 65_536 && validOutput(originalSchema, objectJson)

    /** Output is already returned in memory; the argument admission cap must not reject a valid large result. */
    fun validOutput(originalSchema: String, objectJson: String): Boolean = runCatching {
        require(supported(originalSchema))
        factory.getSchema(originalSchema).validate(mapper.readTree(objectJson)).isEmpty()
    }.getOrDefault(false)
}
