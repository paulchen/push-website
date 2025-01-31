package at.rueckgr

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, 8080) {
        install(ContentNegotiation) {
            jackson {
                findAndRegisterModules()
            }
        }
        install(Authentication) {
            basic(name = "basic-auth") {
                realm = "push-notifications"
                validate { credentials -> AuthenticationService.getInstance().authenticate(credentials) }
            }
        }
        // TODO logging
        routing {
            staticResources("/", "static")
            route("/services/publicSigningKey") {
                get {
                    call.respond(ServerKeys.getInstance().publicKeyUncompressed)
                }
            }
            route("/services/isSubscribed") {
                post {
                    val subscription = call.receive<Subscription>()
                    call.respond(SubscriptionService.getInstance().getSubscription(subscription.endpoint) != null)
                }
            }
            route("/services/subscribe") {
                post {
                    // TODO improve error handling
                    try {
                        SubscriptionService.getInstance().subscribe(call.receive<Subscription>())
                    }
                    catch (e: Exception) {
                        e.printStackTrace()
                    }
                    call.respond(HttpStatusCode.Created)
                }
            }
            route("/services/unsubscribe") {
                post {
                    SubscriptionService.getInstance().unsubscribe(call.receive<Subscription>())
                    call.respond(HttpStatusCode.OK)
                }
            }
            authenticate("basic-auth") {
                route("/services/notify") {
                    post {
                        PushService().sendMessageToAllSubscribers(call.receiveText())
                    }
                }
            }
        }
    }.start(wait = true)
}
