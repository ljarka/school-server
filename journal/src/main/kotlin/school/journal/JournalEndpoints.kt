package school.journal

import okhttp3.HttpUrl.Companion.toHttpUrl

data class JournalEndpoints(val portal: String, val api: String, val messages: String, val gateway: String? = null) {
    val allowedHosts: Set<String> = buildSet {
        add(portal.toHttpUrl().host.lowercase())
        add(api.toHttpUrl().host.lowercase())
        add(messages.toHttpUrl().host.lowercase())
        gateway?.let { add(it.toHttpUrl().host.lowercase()) }
    }

    fun portalUrl(path: String): String = portal.trimEnd('/') + path

    fun apiUrl(path: String): String = api.trimEnd('/') + path

    fun apiOriginUrl(path: String): String {
        val parsed = api.toHttpUrl()
        val portSuffix = if ((parsed.scheme == "https" && parsed.port == 443) ||
            (parsed.scheme == "http" && parsed.port == 80)
        ) {
            ""
        } else {
            ":${parsed.port}"
        }
        return "${parsed.scheme}://${parsed.host}$portSuffix$path"
    }

    fun messagesUrl(path: String): String = messages.trimEnd('/') + path

    fun gatewayUrl(path: String): String {
        val base = gateway ?: error("SCHOOL_JOURNAL_GATEWAY_URL is not configured")
        return base.trimEnd('/') + path
    }
}
