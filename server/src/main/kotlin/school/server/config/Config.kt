package school.server.config

enum class TrustProxy {
    NONE,
    CLOUDFLARE,
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class Config(
    val journalUrl: String,
    val journalApiUrl: String,
    val journalMessagesUrl: String,
    val child: String?,
    val login: String,
    val password: Secret,
    val apiKey: Secret?,
    val syncIntervalMinutes: Int,
    val trustProxy: TrustProxy,
    val dbPath: String,
    val port: Int,
    val logLevel: LogLevel,
)
