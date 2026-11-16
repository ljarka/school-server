package school.journal

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import school.model.JournalUnavailable

internal data class HttpResponseData(val code: Int, val contentType: String?, val body: String, val finalUrl: HttpUrl)

internal class InMemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > now) {
                this.cookies.add(cookie)
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }
}

internal class PortalSession(
    private val endpoints: JournalEndpoints,
    private val minDelayMs: Long = 500L,
    timeoutSeconds: Long = 30L,
) {
    private val logger = LoggerFactory.getLogger(PortalSession::class.java)
    private var lastRequestAtMs: Long = 0L

    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponseData =
        executeWithRedirects(method = "GET", url = url, headers = headers, body = null)

    fun postForm(url: String, body: RequestBody, headers: Map<String, String> = emptyMap()): HttpResponseData =
        executeWithRedirects(method = "POST", url = url, headers = headers, body = body)

    private fun executeWithRedirects(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: RequestBody?,
    ): HttpResponseData {
        var currentUrl = url.toHttpUrl()
        var currentMethod = method
        var currentBody = body

        for (hop in 0..MAX_REDIRECTS) {
            paceRequest()

            val builder = Request.Builder().url(currentUrl)
            headers.forEach { (name, value) -> builder.header(name, value) }
            when (currentMethod) {
                "GET" -> builder.get()
                "POST" -> builder.post(currentBody ?: error("POST requires body"))
                else -> builder.method(currentMethod, currentBody)
            }

            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                logger.debug("e-journal network error: {}", e.javaClass.simpleName)
                throw JournalUnavailable("network_error", e)
            }

            response.use { res ->
                logger.debug("e-journal response: status={}", res.code)
                if (res.code in REDIRECT_CODES) {
                    val location = res.header("Location")
                        ?: throw JournalUnavailable("unexpected_redirect")
                    val nextUrl = currentUrl.resolve(location)
                        ?: throw JournalUnavailable("unexpected_redirect")
                    if (nextUrl.host.lowercase() !in endpoints.allowedHosts) {
                        logger.debug("e-journal redirect to unconfigured host rejected")
                        throw JournalUnavailable("unexpected_redirect")
                    }
                    currentUrl = nextUrl
                    if (res.code == 301 || res.code == 302 || res.code == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    return@use
                }

                val responseBody = try {
                    res.body.string()
                } catch (e: IOException) {
                    throw JournalUnavailable("network_error", e)
                }

                return HttpResponseData(
                    code = res.code,
                    contentType = res.header("Content-Type"),
                    body = responseBody,
                    finalUrl = res.request.url,
                )
            }
        }

        throw JournalUnavailable("too_many_redirects")
    }

    @Synchronized
    private fun paceRequest() {
        if (minDelayMs <= 0L) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestAtMs
        if (lastRequestAtMs > 0L && elapsed < minDelayMs) {
            Thread.sleep(minDelayMs - elapsed)
        }
        lastRequestAtMs = System.currentTimeMillis()
    }

    private companion object {
        const val MAX_REDIRECTS = 10
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
