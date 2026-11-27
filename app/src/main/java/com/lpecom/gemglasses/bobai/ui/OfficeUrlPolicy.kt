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
 * A page on the admin origin (BobAI Admin, linked from the office sidebar as "Phones & manuals")
 * never opens in a browser from the phone: it switches the app to its own Library tab
 * ([Decision.OPEN_LIBRARY]). localhost and 127.0.0.1 on the same port count as one origin.
 * If the office and admin origins are the same, the office wins.
 *
 * Pure JVM logic so it can be unit-tested without Android.
 */
class OfficeUrlPolicy(officeUrl: String, adminUrl: String = "") {

    enum class Decision { IN_WEBVIEW, OPEN_LIBRARY, EXTERNAL, BLOCK }

    private data class Origin(val scheme: String, val host: String, val port: Int) {
        /** localhost and 127.0.0.1 are one origin for the admin link (office matching stays exact). */
        fun loopbackNormalized() = if (host == "127.0.0.1") copy(host = "localhost") else this
    }

    private val office: Origin? = originOf(officeUrl)
    private val admin: Origin? = originOf(adminUrl)?.loopbackNormalized()

    /** True when the configured office URL itself is a usable http(s) origin. */
    val isConfigured: Boolean get() = office != null

    fun decide(url: String?): Decision {
        if (url.isNullOrBlank()) return Decision.BLOCK
        val uri = parse(url.trim()) ?: return Decision.BLOCK
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Decision.BLOCK
        return when (scheme) {
            "http", "https" -> {
                val origin = originOf(uri) ?: return Decision.BLOCK
                when {
                    office != null && origin == office -> Decision.IN_WEBVIEW
                    admin != null && origin.loopbackNormalized() == admin -> Decision.OPEN_LIBRARY
                    else -> Decision.EXTERNAL
                }
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
