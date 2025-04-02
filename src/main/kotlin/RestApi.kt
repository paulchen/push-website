package at.rueckgr

import at.rueckgr.util.Logging
import at.rueckgr.util.logger
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

class RestApi : Logging {
    fun start() {
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
            install(DefaultHeaders) {
                // TODO
                header(HttpHeaders.AccessControlAllowOrigin, "*")
                header(HttpHeaders.AccessControlAllowHeaders, "content-type")
            }
            // TODO logging
            routing {
                staticResources("/", "static")
                staticFiles("/icons", File("data/icons"))
                route("/services/publicSigningKey") {
                    get {
                        call.respond(ServerKeys.getInstance().publicKeyUncompressed)
                    }
                }
                route("/services/isSubscribed") {
                    options { call.respond(HttpStatusCode.OK) }
                    post {
                        val subscription = call.receive<Subscription>()
                        call.respond(SubscriptionService.getInstance().getSubscription(subscription.endpoint) != null)
                    }
                }
                route("/services/subscribe") {
                    options { call.respond(HttpStatusCode.OK) }
                    post {
                        try {
                            SubscriptionService.getInstance().subscribe(call.receive<Subscription>())
                            call.respond(HttpStatusCode.Created)
                        }
                        catch (e: Exception) {
                            logger().error(e)
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                }
                route("/services/unsubscribe") {
                    options { call.respond(HttpStatusCode.OK) }
                    post {
                        SubscriptionService.getInstance().unsubscribe(call.receive<Subscription>())
                        call.respond(HttpStatusCode.OK)
                    }
                }
                authenticate("basic-auth") {
                    route("/services/subscribers") {
                        get {
                            call.respond(mapOf("subscribers" to SubscriptionService.getInstance().getSubscriberCount()))
                        }
                    }
                    route("/services/notifications") {
                        post {
                            if(!NotificationService.getInstance().add(call.receive<Notification>())) {
                                call.respond(HttpStatusCode.BadRequest)
                            }
                            else {
                                call.respond(HttpStatusCode.Accepted)
                            }
                        }
                        get {
                            call.respond(NotificationService.getInstance().getAll())
                        }
                    }
                    route("services/notifications/{id}") {
                        delete {
                            NotificationService.getInstance().delete(call.parameters["id"]!!.toLong())
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
            }
        }.start(wait = true)
    }
}
