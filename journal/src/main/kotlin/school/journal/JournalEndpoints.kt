package school.journal

import okhttp3.HttpUrl.Companion.toHttpUrl

data class JournalEndpoints(val portal: String, val api: String, val messages: String) {
    val allowedHosts: Set<String> = setOf(
        portal.toHttpUrl().host.lowercase(),
        api.toHttpUrl().host.lowercase(),
        messages.toHttpUrl().host.lowercase(),
    )

    fun portalUrl(path: String): String = portal.trimEnd('/') + path

    fun apiUrl(path: String): String = api.trimEnd('/') + path

    fun messagesUrl(path: String): String = messages.trimEnd('/') + path
}
