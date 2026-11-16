package school.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoaderTest {
    private fun baseEnv(): Map<String, String> = mapOf(
        "SCHOOL_JOURNAL_URL" to "https://portal.example.test",
        "SCHOOL_JOURNAL_API_URL" to "https://api.example.test",
        "SCHOOL_JOURNAL_MESSAGES_URL" to "https://messages.example.test",
        "SCHOOL_LOGIN" to "parent@example.test",
        "SCHOOL_PASSWORD" to "super-secret-password",
    )

    @Test
    fun `loads defaults when only required variables are set`() {
        val config = ConfigLoader.load(baseEnv())

        assertEquals("https://portal.example.test", config.journalUrl)
        assertEquals("https://api.example.test", config.journalApiUrl)
        assertEquals("https://messages.example.test", config.journalMessagesUrl)
        assertNull(config.child)
        assertEquals("parent@example.test", config.login)
        assertEquals("super-secret-password", config.password.reveal())
        assertNull(config.apiKey)
        assertEquals(15, config.syncIntervalMinutes)
        assertEquals(TrustProxy.NONE, config.trustProxy)
        assertEquals("school.db", config.dbPath)
        assertEquals(5100, config.port)
        assertEquals(LogLevel.INFO, config.logLevel)
    }

    @Test
    fun `allows localhost http urls for tests`() {
        val env = baseEnv() + mapOf(
            "SCHOOL_JOURNAL_URL" to "http://localhost:8080/",
            "SCHOOL_JOURNAL_API_URL" to "http://127.0.0.1:8081",
            "SCHOOL_JOURNAL_MESSAGES_URL" to "http://localhost:8082",
        )

        val config = ConfigLoader.load(env)

        assertEquals("http://localhost:8080", config.journalUrl)
        assertEquals("http://127.0.0.1:8081", config.journalApiUrl)
        assertEquals("http://localhost:8082", config.journalMessagesUrl)
    }

    @Test
    fun `reports missing required variables`() {
        val required = listOf(
            "SCHOOL_JOURNAL_URL",
            "SCHOOL_JOURNAL_API_URL",
            "SCHOOL_JOURNAL_MESSAGES_URL",
            "SCHOOL_LOGIN",
            "SCHOOL_PASSWORD",
        )

        for (key in required) {
            val env = baseEnv() - key
            val error = assertFailsWith<ConfigError> { ConfigLoader.load(env) }
            assertTrue(
                error.problems.any {
                    it.contains(key)
                },
                "Expected problem mentioning $key, got ${error.problems}",
            )
        }
    }

    @Test
    fun `rejects non-https non-localhost urls`() {
        val env = baseEnv() + mapOf("SCHOOL_JOURNAL_URL" to "http://portal.example.test")
        val error = assertFailsWith<ConfigError> { ConfigLoader.load(env) }
        assertTrue(error.problems.any { it.contains("SCHOOL_JOURNAL_URL") })
    }

    @Test
    fun `rejects sync interval below 5`() {
        val env = baseEnv() + mapOf("SYNC_INTERVAL_MINUTES" to "4")
        val error = assertFailsWith<ConfigError> { ConfigLoader.load(env) }
        assertTrue(error.problems.any { it.contains("SYNC_INTERVAL_MINUTES") })
    }

    @Test
    fun `password file wins over SCHOOL_PASSWORD and strips trailing newline`() {
        val env = baseEnv() + mapOf("SCHOOL_PASSWORD_FILE" to "/run/secrets/school_password")
        val config = ConfigLoader.load(env) { path ->
            assertEquals("/run/secrets/school_password", path)
            "from-file-secret\n"
        }

        assertEquals("from-file-secret", config.password.reveal())
    }

    @Test
    fun `cloudflare trust proxy without api key fails`() {
        val env = baseEnv() + mapOf("TRUST_PROXY" to "cloudflare")
        val error = assertFailsWith<ConfigError> { ConfigLoader.load(env) }
        assertTrue(error.problems.any { it.contains("TRUST_PROXY=cloudflare") })
    }

    @Test
    fun `cloudflare trust proxy with api key succeeds`() {
        val env = baseEnv() + mapOf(
            "TRUST_PROXY" to "cloudflare",
            "SCHOOL_API_KEY" to "my-api-key-123",
        )
        val config = ConfigLoader.load(env)
        assertEquals(TrustProxy.CLOUDFLARE, config.trustProxy)
        assertEquals("my-api-key-123", config.apiKey?.reveal())
    }

    @Test
    fun `reports all configuration problems together`() {
        val env = mapOf(
            "SCHOOL_JOURNAL_URL" to "http://bad.example.test",
            "SYNC_INTERVAL_MINUTES" to "1",
            "LOG_LEVEL" to "TRACE",
        )
        val error = assertFailsWith<ConfigError> { ConfigLoader.load(env) }
        assertTrue(error.problems.size >= 5, "Expected multiple problems, got ${error.problems}")
    }

    @Test
    fun `config toString never leaks password or api key`() {
        val env = baseEnv() + mapOf("SCHOOL_API_KEY" to "my-api-key-123")
        val config = ConfigLoader.load(env)
        val rendered = config.toString()

        assertFalse(rendered.contains("super-secret-password"))
        assertFalse(rendered.contains("my-api-key-123"))
        assertTrue(rendered.contains("***"))
    }
}
