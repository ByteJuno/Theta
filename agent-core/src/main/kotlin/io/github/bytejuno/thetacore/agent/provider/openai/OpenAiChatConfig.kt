package io.github.bytejuno.thetacore.agent.provider.openai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress

/** Cleartext HTTP is only permitted for localhost, private LAN (RFC 1918), link-local,
 * Tailscale/CGNAT (100.64.0.0/10), and IPv6 loopback/ULA/link-local literals. Never resolves DNS. */
internal fun isPrivateCleartextHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    val ipLiteral = host.contains(':') || (host.isNotEmpty() && host.all { it.isDigit() || it == '.' })
    if (!ipLiteral) return false
    val address = try { InetAddress.getByName(host) } catch (_: Exception) { return false }
    if (address.isAnyLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val first = bytes[0]; val second = bytes[1]
        return first == 127 ||
            first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254)
    }
    if (bytes.size == 16) {
        return address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            (bytes[0] and 0xfe) == 0xfc // IPv6 ULA fc00::/7 (includes Tailscale fd7a:115c:a1e0::/48)
    }
    return false
}

/** Schema is already mapped by the run catalog; this adapter never silently drops constraints. */
enum class SchemaProfile { FULL, NO_TOP_LEVEL_COMBINATORS, BASIC }
class OpenAiChatConfig(
    val providerId: String,
    baseUrl: String,
    models: Set<String> = emptySet(),
    val compat: Compat = Compat(),
) {
    val models: Set<String> = java.util.Collections.unmodifiableSet(HashSet(models))
    val endpoint: HttpUrl
    init {
        require(providerId.isNotBlank())
        // URL parser exceptions may embed userinfo/query credentials: never preserve their cause.
        val base = try { baseUrl.toHttpUrl() } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid provider URL")
        }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        require(base.scheme == "https" || isPrivateCleartextHost(base.host)) {
            "Cleartext HTTP requires localhost, private LAN, or Tailscale address"
        }
        endpoint = base.newBuilder().apply {
            if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
            addPathSegments("chat/completions")
        }.build()
    }
    class Compat(
        val maxTokensField: String = "max_completion_tokens",
        val reasoningField: String? = null,
        val replayReasoning: Boolean = false,
        val streamUsage: Boolean = true,
        val schemaProfile: SchemaProfile = SchemaProfile.FULL,
        /** Explicit opt-in for compatible endpoints accepting content-block cache_control.
         * Standard OpenAI Chat has automatic caching and MUST leave this false. */
        val explicitCacheControl: Boolean = false,
        /** Whether this model profile accepts user image attachments (PROTOCOL 2.4). */
        val supportsImageInput: Boolean = true,
    ) {
        init {
            require(maxTokensField in setOf("max_tokens", "max_completion_tokens"))
            require(reasoningField == null || reasoningField.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            require(reasoningField !in setOf("role", "content", "tool_calls", "refusal", "function_call"))
            require(!replayReasoning || reasoningField != null)
        }
    }
}
