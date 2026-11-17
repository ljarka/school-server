package school.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.BindException
import kotlin.system.exitProcess
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import school.journal.HttpJournalClient
import school.journal.JournalEndpoints
import school.server.api.ApiErrorResponse
import school.server.api.ErrorBody
import school.server.api.changesRoutes
import school.server.api.childrenRoutes
import school.server.api.configureStatusPages
import school.server.api.docsRoutes
import school.server.api.gradesRoutes
import school.server.api.healthRoutes
import school.server.api.messagesRoutes
import school.server.api.statusRoutes
import school.server.api.syncRoutes
import school.server.auth.ApiKeyPlugin
import school.server.auth.WrongKeyLimiter
import school.server.config.Config
import school.server.config.ConfigError
import school.server.config.ConfigLoader
import school.server.store.DatabaseFactory
import school.server.store.StorageUnavailableException
import school.server.store.Store
import school.server.store.db.SchoolDatabase
import school.server.sync.SyncConfig
import school.server.sync.SyncService

fun main() {
    val config = try {
        ConfigLoader.load(System.getenv())
    } catch (e: ConfigError) {
        System.err.println("Configuration error:")
        System.err.println(e.message)
        exitProcess(2)
    }

    configureLogging(config)

    val driver = try {
        DatabaseFactory.open(config.dbPath)
    } catch (e: StorageUnavailableException) {
        LoggerFactory.getLogger("school.server.Main").error("Database unavailable: {}", e.message)
        null
    }

    val store = driver?.let { Store(SchoolDatabase(it)) }
    store?.failInterruptedSyncRuns(Clock.System.now())
    val syncService = if (store != null) {
        val journal = HttpJournalClient(
            endpoints = journalEndpoints(config),
            login = config.login,
            passwordProvider = { config.password.reveal() },
            configuredChild = config.child,
        )
        SyncService(
            journal = journal,
            store = store,
            config = SyncConfig(intervalMinutes = config.syncIntervalMinutes),
        )
    } else {
        null
    }

    try {
        embeddedServer(Netty, port = config.port) {
            if (syncService != null) {
                syncService.start(this)
            }
            monitor.subscribe(ApplicationStopping) {
                syncService?.stop()
                runCatching { driver?.close() }
            }
            schoolModule(config = config, store = store, syncService = syncService)
        }.start(wait = true)
    } catch (e: BindException) {
        syncService?.stop()
        runCatching { driver?.close() }
        System.err.println(
            "Port ${config.port} is already in use. Set SCHOOL_PORT (Docker) or PORT to a free port.",
        )
        exitProcess(1)
    }
}

fun Application.schoolModule(
    config: Config,
    clock: Clock = Clock.System,
    store: Store? = null,
    syncService: SyncService? = null,
    apiRoutes: Route.() -> Unit = {},
) {
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
            },
        )
    }
    install(DefaultHeaders) {
        header(HttpHeaders.CacheControl, "no-store")
    }
    configureStatusPages()
    install(CallLogging) {
        format { call ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val status = call.response.status()?.value ?: "-"
            val ms = call.processingTimeMillis()
            "$method $path -> $status (${ms}ms)"
        }
    }

    val limiter = WrongKeyLimiter(clock = clock)

    routing {
        healthRoutes()
        docsRoutes()
        route("/api/v1") {
            install(ApiKeyPlugin) {
                this.apiKey = config.apiKey
                this.trustProxy = config.trustProxy
                this.limiter = limiter
            }
            if (store != null) {
                childrenRoutes(store)
                gradesRoutes(store)
                messagesRoutes(store, clock)
                changesRoutes(store)
                statusRoutes(store, syncService)
                if (syncService != null) {
                    syncRoutes(syncService, this@schoolModule)
                }
            } else {
                get("/{...}") {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiErrorResponse(ErrorBody("STORAGE_UNAVAILABLE", "Database is unavailable")),
                    )
                }
            }
            apiRoutes()
        }
    }
}

/**
 * Explicit URLs win over the ones derived from SCHOOL_JOURNAL_DOMAIN. ConfigLoader guarantees that each
 * of portal/API/messages is set either way.
 */
private fun journalEndpoints(config: Config): JournalEndpoints {
    val derived = config.journalDomain?.let { JournalEndpoints.fromDomain(it) }
    fun pick(explicit: String, fromDomain: String?, name: String): String =
        explicit.ifEmpty { fromDomain ?: error("$name is not configured (checked by ConfigLoader)") }
    val portalUrl = pick(config.journalUrl, derived?.portal, "SCHOOL_JOURNAL_URL")
    return JournalEndpoints(
        portal = portalUrl,
        api = pick(config.journalApiUrl, derived?.api, "SCHOOL_JOURNAL_API_URL"),
        messages = pick(config.journalMessagesUrl, derived?.messages, "SCHOOL_JOURNAL_MESSAGES_URL"),
        gateway = config.journalGatewayUrl ?: derived?.gateway ?: JournalEndpoints.deriveGatewayUrl(portalUrl),
    )
}

private fun configureLogging(config: Config) {
    val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as? Logger
    rootLogger?.level = Level.valueOf(config.logLevel.name)
}
