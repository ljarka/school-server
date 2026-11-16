package school.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import school.server.api.healthRoutes
import school.server.config.Config
import school.server.config.ConfigError
import school.server.config.ConfigLoader

fun main() {
    val config = try {
        ConfigLoader.load(System.getenv())
    } catch (e: ConfigError) {
        System.err.println("Configuration error:")
        System.err.println(e.message)
        exitProcess(2)
    }

    configureLogging(config)

    embeddedServer(Netty, port = config.port) {
        schoolModule()
    }.start(wait = true)
}

fun Application.schoolModule() {
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
            },
        )
    }
    install(DefaultHeaders)
    install(CallLogging) {
        format { call ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val status = call.response.status()?.value ?: "-"
            val ms = call.processingTimeMillis()
            "$method $path -> $status (${ms}ms)"
        }
    }
    routing {
        healthRoutes()
    }
}

private fun configureLogging(config: Config) {
    val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as? Logger
    rootLogger?.level = Level.valueOf(config.logLevel.name)
}
