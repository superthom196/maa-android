package io.github.superthom196.maa.ui

import io.github.superthom196.maa.data.ServerConfig
import java.net.URI

/** The kind of network path a server URL goes over. */
enum class UrlKind { LAN, TAILSCALE, REMOTE }

/** What the Home screen says about the connection in use. */
enum class ConnectionKind { LAN, TAILSCALE, REMOTE, UNREACHABLE }

/**
 * Pure URL helpers for the sign-in and settings screens. They decide which of the two
 * [ServerConfig] slots an address belongs in: the LAN slot is tried first at home, the remote
 * slot (usually Tailscale) is what keeps the car working over mobile data.
 */
object ServerUrls {
    const val DEFAULT_PORT = 8095

    /**
     * Turns what a person types into a base URL without trailing slash:
     * "host" → `http://host:8095`, "host:port" → `http://host:port`. A full URL is kept as typed:
     * writing the scheme means the exact address is known (e.g. an https reverse proxy on 443).
     * Returns null for blank or unparsable input.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val lower = trimmed.lowercase()
        val t = trimmed.trimEnd('/')
        val url = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> t
            "://" in t -> return null
            else -> {
                val authority = t.substringBefore('/')
                val path = t.substring(authority.length)
                val colons = authority.count { it == ':' }
                val host: String
                val hasPort: Boolean
                when {
                    authority.startsWith("[") -> { host = authority; hasPort = authority.substringAfter(']', "").startsWith(":") }
                    colons > 1 -> { host = "[$authority]"; hasPort = false } // bare IPv6 literal
                    else -> { host = authority; hasPort = colons == 1 }
                }
                if (hasPort) "http://$host$path" else "http://$host:$DEFAULT_PORT$path"
            }
        }
        val h = hostOf(url) ?: return null
        // "host:" or "host:abc" would otherwise pass as a port.
        val port = runCatching { URI(url).rawAuthority }.getOrNull()?.substringAfterLast(']')?.substringAfter(':', "0")
        if (h.isEmpty() || port.isNullOrEmpty() || !port.all(Char::isDigit)) return null
        return url.trimEnd('/')
    }

    /** Lower-case host without IPv6 brackets, or null when [url] has none. */
    fun hostOf(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        // URI.host is null for names java.net dislikes (e.g. underscores); fall back to the authority.
        val raw = uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.let { a ->
            if (a.startsWith("[")) a.substringBefore(']') + "]" else a.substringBefore(':')
        } ?: return null
        return raw.removePrefix("[").removeSuffix("]").lowercase()
    }

    fun kind(url: String): UrlKind {
        val host = hostOf(url) ?: return UrlKind.REMOTE
        return when {
            isTailscaleHost(host) -> UrlKind.TAILSCALE
            isLanHost(host) -> UrlKind.LAN
            else -> UrlKind.REMOTE
        }
    }

    /** Private IPv4 ranges, link-local, mDNS `.local` names and IPv6 ULA/link-local (minus Tailscale's). */
    fun isLanHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith(".local")) return true
        ipv4(h)?.let { (a, b) ->
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h && !isTailscaleHost(h)) return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }

    /** Tailscale CGNAT range 100.64.0.0/10, MagicDNS `*.ts.net`, and Tailscale's IPv6 prefix. */
    fun isTailscaleHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.endsWith(".ts.net")) return true
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        return ipv4(h)?.let { (a, b) -> a == 100 && b in 64..127 } ?: false
    }

    /**
     * Which slots a freshly signed-in address fills: a LAN address is the LAN URL; anything else is
     * the remote URL, and then the server's own `/info` base_url (always its LAN address) becomes
     * the LAN URL so the phone still prefers the direct path at home.
     */
    fun slotsForLogin(typed: String, infoBaseUrl: String?): Pair<String?, String?> {
        if (kind(typed) == UrlKind.LAN) return typed to null
        val lan = infoBaseUrl?.let(::normalize)?.takeIf { it != typed && kind(it) == UrlKind.LAN }
        return lan to typed
    }

    /** Describes [current] (the URL [io.github.superthom196.maa.data.BaseUrlProvider] picked). */
    fun connectionKind(current: String?, cfg: ServerConfig?): ConnectionKind {
        if (current == null) return ConnectionKind.UNREACHABLE
        if (cfg != null && current == cfg.lanUrl) return ConnectionKind.LAN
        return when (kind(current)) {
            UrlKind.LAN -> ConnectionKind.LAN
            UrlKind.TAILSCALE -> ConnectionKind.TAILSCALE
            UrlKind.REMOTE -> ConnectionKind.REMOTE
        }
    }

    /** First two octets of a dotted IPv4 literal, or null when [host] is not one. */
    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
