package com.lpecom.gemglasses.bobai.ui

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Decides where a navigation from the Office WebView may go.
 *
 * Only pages on the office origin (same scheme, host and effective port as the
 * configured office URL) load inside the WebView. Ordinary web links and mail
 * links leave the app through an ACTION_VIEW intent. Everything else
 * (javascript:, file:, data:, intent:, content:, malformed URLs) is blocked.
 *
 * Pure JVM logic so it can be unit-tested without Android.
 */
class OfficeUrlPolicy(officeUrl: String) {

    enum class Decision { IN_WEBVIEW, EXTERNAL, BLOCK }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private val office: Origin? = originOf(officeUrl)

    /** True when the configured office URL itself is a usable http(s) origin. */
    val isConfigured: Boolean get() = office != null

    fun decide(url: String?): Decision {
        if (url.isNullOrBlank()) return Decision.BLOCK
        val uri = parse(url.trim()) ?: return Decision.BLOCK
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.BLOCK
        return when (scheme) {
            "http", "https" -> {
                val origin = originOf(uri) ?: return Decision.BLOCK
                if (office != null && origin == office) Decision.IN_WEBVIEW else Decision.EXTERNAL
            }
            "mailto" -> Decision.EXTERNAL
            else -> Decision.BLOCK
        }
    }

    private companion object {
        fun parse(url: String): URI? = try {
            URI(url)
        } catch (_: URISyntaxException) {
            null
        }

        fun originOf(url: String): Origin? = parse(url.trim())?.let(::originOf)

        fun originOf(uri: URI): Origin? {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            // URI.host is null for authorities it can't parse as a server (e.g. "_bad_host"),
            // so such URLs never count as the office origin.
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            if (host.isNullOrEmpty()) return null
            val port = when {
                uri.port != -1 -> uri.port
                scheme == "https" -> 443
                else -> 80
            }
            return Origin(scheme, host, port)
        }
    }
}
