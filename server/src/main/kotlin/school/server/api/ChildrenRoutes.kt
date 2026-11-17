package school.server.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import school.model.ChildAccountDto
import school.model.ChildrenListDto
import school.server.store.Store

fun Route.childrenRoutes(store: Store) {
    get("/children") {
        val children = store.listChildAccounts().map {
            ChildAccountDto(id = it.id, login = it.login, name = it.name)
        }
        call.respond(ChildrenListDto(children = children))
    }
}
