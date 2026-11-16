package school.server.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import school.server.config.TrustProxy

fun clientAddress(call: ApplicationCall, trustProxy: TrustProxy): String {
    if (trustProxy == TrustProxy.CLOUDFLARE) {
        val cfIp = call.request.headers["CF-Connecting-IP"]?.trim()?.ifEmpty { null }
        if (cfIp != null) {
            return cfIp
        }
    }
    return call.request.origin.remoteAddress
}
