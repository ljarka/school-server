package school.server.config

import java.io.File
import java.net.URI
import org.slf4j.LoggerFactory

class ConfigError(val problems: List<String>) : RuntimeException(problems.joinToString("\n"))

object ConfigLoader {
    private val logger = LoggerFactory.getLogger(ConfigLoader::class.java)

    fun load(env: Map<String, String>, readFile: (String) -> String = { File(it).readText() }): Config {
        val problems = mutableListOf<String>()

        val journalUrl = parseUrl(env, "SCHOOL_JOURNAL_URL", problems)
        val journalApiUrl = parseUrl(env, "SCHOOL_JOURNAL_API_URL", problems)
        val journalMessagesUrl = parseUrl(env, "SCHOOL_JOURNAL_MESSAGES_URL", problems)
        val child = env["SCHOOL_CHILD"]?.trim()?.ifEmpty { null }

        val login = env["SCHOOL_LOGIN"]?.trim()?.ifEmpty { null }
        if (login == null) {
            problems += "SCHOOL_LOGIN is required"
        }

        val passwordResult = loadPassword(env, readFile, problems)

        val apiKeyRaw = env["SCHOOL_API_KEY"]?.trim()?.ifEmpty { null }
        val apiKey = apiKeyRaw?.let { Secret(it) }

        val trustProxy = parseTrustProxy(env["TRUST_PROXY"], problems)
        if (trustProxy == TrustProxy.CLOUDFLARE && apiKey == null) {
            problems += "TRUST_PROXY=cloudflare requires SCHOOL_API_KEY to be set"
        }

        val syncIntervalMinutes = parseSyncInterval(env["SYNC_INTERVAL_MINUTES"], problems)
        val dbPath = env["SCHOOL_DB_PATH"]?.trim()?.ifEmpty { null } ?: "school.db"
        val port = parsePort(env["PORT"], problems)
        val logLevel = parseLogLevel(env["LOG_LEVEL"], problems)

        if (problems.isNotEmpty()) {
            throw ConfigError(problems)
        }

        if (passwordResult.fromFile) {
            logger.info("Password read from SCHOOL_PASSWORD_FILE")
        }
        if (apiKey == null) {
            logger.warn("no API key: anyone who can reach this server can read the data")
        }

        return Config(
            journalUrl = journalUrl!!,
            journalApiUrl = journalApiUrl!!,
            journalMessagesUrl = journalMessagesUrl!!,
            child = child,
            login = login!!,
            password = passwordResult.secret!!,
            apiKey = apiKey,
            syncIntervalMinutes = syncIntervalMinutes,
            trustProxy = trustProxy,
            dbPath = dbPath,
            port = port,
            logLevel = logLevel,
        )
    }

    private fun parseUrl(env: Map<String, String>, name: String, problems: MutableList<String>): String? {
        val raw = env[name]?.trim()?.ifEmpty { null }
        if (raw == null) {
            problems += "$name is required"
            return null
        }
        val uri = runCatching { URI.create(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        val valid = when (scheme) {
            "https" -> !host.isNullOrBlank()
            "http" -> host == "localhost" || host == "127.0.0.1"
            else -> false
        }
        if (!valid) {
            problems += "$name must be an https:// URL (or http://localhost / http://127.0.0.1)"
            return null
        }
        return raw.trimEnd('/')
    }

    private data class PasswordResult(val secret: Secret?, val fromFile: Boolean)

    private fun loadPassword(
        env: Map<String, String>,
        readFile: (String) -> String,
        problems: MutableList<String>,
    ): PasswordResult {
        val passwordFile = env["SCHOOL_PASSWORD_FILE"]?.trim()?.ifEmpty { null }
        if (passwordFile != null) {
            val content = runCatching { readFile(passwordFile) }.getOrElse {
                problems += "SCHOOL_PASSWORD_FILE could not be read ($passwordFile)"
                return PasswordResult(secret = null, fromFile = false)
            }
            val stripped = content.trimEnd('\r', '\n')
            if (stripped.isEmpty()) {
                problems += "SCHOOL_PASSWORD_FILE is empty"
                return PasswordResult(secret = null, fromFile = false)
            }
            return PasswordResult(secret = Secret(stripped), fromFile = true)
        }

        val rawPassword = env["SCHOOL_PASSWORD"]?.trimEnd('\r', '\n')?.ifEmpty { null }
        if (rawPassword == null) {
            problems += "SCHOOL_PASSWORD (or SCHOOL_PASSWORD_FILE) is required"
            return PasswordResult(secret = null, fromFile = false)
        }
        return PasswordResult(secret = Secret(rawPassword), fromFile = false)
    }

    private fun parseTrustProxy(raw: String?, problems: MutableList<String>): TrustProxy {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return TrustProxy.NONE
        return when (trimmed.lowercase()) {
            "cloudflare" -> TrustProxy.CLOUDFLARE
            else -> {
                problems += "TRUST_PROXY must be empty or 'cloudflare'"
                TrustProxy.NONE
            }
        }
    }

    private fun parseSyncInterval(raw: String?, problems: MutableList<String>): Int {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return 15
        val parsed = trimmed.toIntOrNull()
        if (parsed == null || parsed < 5) {
            problems += "SYNC_INTERVAL_MINUTES must be an integer >= 5"
            return 15
        }
        return parsed
    }

    private fun parsePort(raw: String?, problems: MutableList<String>): Int {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return 5100
        val parsed = trimmed.toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            problems += "PORT must be an integer between 1 and 65535"
            return 5100
        }
        return parsed
    }

    private fun parseLogLevel(raw: String?, problems: MutableList<String>): LogLevel {
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return LogLevel.INFO
        return LogLevel.entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) } ?: run {
            problems += "LOG_LEVEL must be one of DEBUG, INFO, WARN, ERROR"
            LogLevel.INFO
        }
    }
}
