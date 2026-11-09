package io.github.bytejuno.thetacore.agent.execution

import java.security.MessageDigest

/** Model aliases are scoped to a frozen directory; never decode an alias back into a tool key. */
object ModelToolNames {
    private const val MAX_LENGTH = 64
    private val invalidCharacter = Regex("[^A-Za-z0-9_-]")

    /** Stable for the same set of keys, regardless of registration order. */
    fun allocate(toolKeys: Collection<String>): Map<String, String> {
        require(toolKeys.size == toolKeys.toSet().size) { "Duplicate tool key" }
        val bases = toolKeys.sorted().associateWith { it.replace(invalidCharacter, "_").ifEmpty { "tool" } }
        val counts = bases.values.groupingBy { it }.eachCount()
        val names = linkedMapOf<String, String>()
        // Reserve every unambiguous simple name before assigning hashes: a literal tool name
        // may itself look like a generated alias, including a collision-resolution suffix.
        bases.forEach { (key, base) ->
            if (base.length <= MAX_LENGTH && counts.getValue(base) == 1) names[key] = base
        }
        val used = names.values.toMutableSet()
        bases.forEach { (key, base) ->
            if (key !in names) {
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                    .take(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                var attempt = 0
                var candidate: String
                do {
                    val suffix = "_$hash" + if (attempt == 0) "" else "_$attempt"
                    candidate = base.take(MAX_LENGTH - suffix.length) + suffix
                    attempt++
                } while (!used.add(candidate))
                names[key] = candidate
            }
        }
        return names.toSortedMap()
    }
}
