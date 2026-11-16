package school.server.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.docsRoutes() {
    get("/api/v1/openapi.yaml") {
        val spec = object {}.javaClass.getResource("/openapi.yaml")?.readText()
        if (spec == null) {
            call.respondText("OpenAPI spec not found", status = HttpStatusCode.NotFound)
        } else {
            call.respondText(spec, ContentType.parse("application/yaml"))
        }
    }
    swaggerUI(path = "docs", swaggerFile = "openapi.yaml")
}
