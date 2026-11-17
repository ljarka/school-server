package school.journal

import okhttp3.HttpUrl.Companion.toHttpUrl

data class JournalEndpoints(
    val portal: String,
    val api: String,
    val messages: String,
    val gateway: String? = deriveGatewayUrl(portal),
) {
    val allowedHosts: Set<String> = buildSet {
        add(portal.toHttpUrl().host.lowercase())
        add(api.toHttpUrl().host.lowercase())
        add(messages.toHttpUrl().host.lowercase())
        gateway?.let { add(it.toHttpUrl().host.lowercase()) }
    }

    fun portalUrl(path: String): String = portal.trimEnd('/') + path

    fun apiUrl(path: String): String = api.trimEnd('/') + path

    fun apiV2Url(path: String): String {
        val base = api.trimEnd('/')
        val v2Base = if (base.endsWith(ProtocolPaths.API_VERSION_PATH)) {
            base.removeSuffix(ProtocolPaths.API_VERSION_PATH) + ProtocolPaths.API_V2_VERSION_PATH
        } else {
            base
        }
        return v2Base + path
    }

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

    companion object {
        fun fromDomain(domain: String): JournalEndpoints {
            val clean = domain.trim().trimEnd('/').removePrefix("https://").removePrefix("http://")
            return JournalEndpoints(
                portal = "https://${ProtocolPaths.SUBDOMAIN_PORTAL}.$clean",
                api = "https://${ProtocolPaths.SUBDOMAIN_API}.$clean${ProtocolPaths.API_VERSION_PATH}",
                messages = "https://${ProtocolPaths.SUBDOMAIN_MESSAGES}.$clean",
                gateway = "https://${ProtocolPaths.SUBDOMAIN_GATEWAY}.$clean",
            )
        }

        fun deriveGatewayUrl(portalUrl: String): String? {
            val parsed = runCatching { portalUrl.toHttpUrl() }.getOrNull() ?: return null
            val prefix = "${ProtocolPaths.SUBDOMAIN_PORTAL}."
            if (!parsed.host.lowercase().startsWith(prefix)) return null
            val domain = parsed.host.substring(prefix.length)
            if (domain.isBlank()) return null
            val portSuffix = if ((parsed.scheme == "https" && parsed.port == 443) ||
                (parsed.scheme == "http" && parsed.port == 80)
            ) {
                ""
            } else {
                ":${parsed.port}"
            }
            return "${parsed.scheme}://${ProtocolPaths.SUBDOMAIN_GATEWAY}.$domain$portSuffix"
        }
    }
}
