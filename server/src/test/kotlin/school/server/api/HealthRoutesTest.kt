package school.server.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import school.server.config.Config
import school.server.config.LogLevel
import school.server.config.Secret
import school.server.config.TrustProxy
import school.server.schoolModule

class HealthRoutesTest {
    private fun testConfig() = Config(
        journalUrl = "https://portal.example.test",
        journalApiUrl = "https://api.example.test",
        journalMessagesUrl = "https://messages.example.test",
        child = null,
        login = "parent@example.test",
        password = Secret("secret"),
        apiKey = Secret("api-key"),
        syncIntervalMinutes = 15,
        trustProxy = TrustProxy.NONE,
        dbPath = "school.db",
        port = 5100,
        logLevel = LogLevel.INFO,
    )

    @Test
    fun `health returns 200 ok json without api key`() = testApplication {
        application {
            schoolModule(testConfig())
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }
}
