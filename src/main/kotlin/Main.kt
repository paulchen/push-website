package at.rueckgr

import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, 8080) {
        routing {
            staticResources("/", "static")
            route("/services/helloworld") {
                get {
                    call.respondText("hello world")
                }
            }
        }
    }.start(wait = true)
}
