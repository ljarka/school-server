package school.server.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.slf4j.LoggerFactory
import school.server.config.Config
import school.server.config.LogLevel
import school.server.config.Secret
import school.server.config.TrustProxy
import school.server.schoolModule

class ApiKeyPluginTest {
    private fun testConfig(apiKey: Secret? = Secret("right-secret-key"), trustProxy: TrustProxy = TrustProxy.NONE) =
        Config(
            journalUrl = "https://portal.example.test",
            journalApiUrl = "https://api.example.test",
            journalMessagesUrl = "https://messages.example.test",
            child = null,
            login = "parent@example.test",
            password = Secret("journal-password"),
            apiKey = apiKey,
            syncIntervalMinutes = 15,
            trustProxy = trustProxy,
            dbPath = "school.db",
            port = 5100,
            logLevel = LogLevel.INFO,
        )

    @Test
    fun `missing or wrong key returns 401 with WWW-Authenticate and right key returns 200`() = testApplication {
        application {
            schoolModule(testConfig()) {
                get("/ping") { call.respondText("pong") }
            }
        }

        val missing = client.get("/api/v1/ping")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("School-Key", missing.headers[HttpHeaders.WWWAuthenticate])
        assertEquals("no-store", missing.headers[HttpHeaders.CacheControl])

        val wrong = client.get("/api/v1/ping") {
            header("X-School-Key", "wrong-key")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("School-Key", wrong.headers[HttpHeaders.WWWAuthenticate])

        val right = client.get("/api/v1/ping") {
            header("X-School-Key", "right-secret-key")
        }
        assertEquals(HttpStatusCode.OK, right.status)
        assertEquals("pong", right.bodyAsText())
        assertEquals("no-store", right.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `blocks after 10 wrong attempts and unblocks after 15 minutes`() = testApplication {
        val clock = FakeClock()
        application {
            schoolModule(testConfig(trustProxy = TrustProxy.CLOUDFLARE), clock = clock) {
                get("/ping") { call.respondText("pong") }
            }
        }

        repeat(10) {
            val res = client.get("/api/v1/ping") {
                header("CF-Connecting-IP", "203.0.113.10")
                header("X-School-Key", "wrong")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status)
        }

        // 11th request from A even with right key -> 429
        val blocked = client.get("/api/v1/ping") {
            header("CF-Connecting-IP", "203.0.113.10")
            header("X-School-Key", "right-secret-key")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertNotNull(blocked.headers[HttpHeaders.RetryAfter])

        // Different CF-Connecting-IP still works
        val otherClient = client.get("/api/v1/ping") {
            header("CF-Connecting-IP", "203.0.113.11")
            header("X-School-Key", "right-secret-key")
        }
        assertEquals(HttpStatusCode.OK, otherClient.status)

        // After 15 minutes A works again
        clock.advance(15.minutes)
        val unblocked = client.get("/api/v1/ping") {
            header("CF-Connecting-IP", "203.0.113.10")
            header("X-School-Key", "right-secret-key")
        }
        assertEquals(HttpStatusCode.OK, unblocked.status)
    }

    @Test
    fun `without cloudflare trust proxy CF-Connecting-IP headers share the socket bucket`() = testApplication {
        application {
            schoolModule(testConfig(trustProxy = TrustProxy.NONE)) {
                get("/ping") { call.respondText("pong") }
            }
        }

        repeat(10) {
            client.get("/api/v1/ping") {
                header("CF-Connecting-IP", "203.0.113.10")
                header("X-School-Key", "wrong")
            }
        }

        val blockedDifferentCfIp = client.get("/api/v1/ping") {
            header("CF-Connecting-IP", "203.0.113.99")
            header("X-School-Key", "right-secret-key")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blockedDifferentCfIp.status)
    }

    @Test
    fun `passes when no api key is configured`() = testApplication {
        application {
            schoolModule(testConfig(apiKey = null)) {
                get("/ping") { call.respondText("pong") }
            }
        }

        val res = client.get("/api/v1/ping")
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `logs never contain wrong or right key`() = testApplication {
        val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        rootLogger.addAppender(appender)

        try {
            application {
                schoolModule(testConfig()) {
                    get("/ping") { call.respondText("pong") }
                }
            }

            client.get("/api/v1/ping") {
                header("X-School-Key", "attacker-wrong-key-xyz")
            }
            client.get("/api/v1/ping") {
                header("X-School-Key", "right-secret-key")
            }

            val messages = appender.list.joinToString("\n") { it.formattedMessage }
            assertTrue(messages.contains("Rejected request"))
            assertFalse(messages.contains("attacker-wrong-key-xyz"))
            assertFalse(messages.contains("right-secret-key"))
        } finally {
            rootLogger.detachAppender(appender)
        }
    }
}
