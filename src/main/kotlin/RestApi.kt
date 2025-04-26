package at.rueckgr

import at.rueckgr.util.Logging
import at.rueckgr.util.logger
import com.fasterxml.jackson.databind.SerializationFeature
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
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
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
            routing {
                staticResources("/", "static")
                staticFiles("/icons", File("data/icons"))
                route("/services/publicSigningKey") {
                    get {
                        val key = ServerKeys.getInstance().publicKeyUncompressed
                        logger().debug("Returning public key: {}", key)
                        call.respond(key)
                    }
                }
                route("/services/isSubscribed") {
                    options { call.respond(HttpStatusCode.OK) }
                    post {
                        val subscription = call.receive<Subscription>()
                        val subscribed = SubscriptionService.getInstance().getSubscription(subscription.endpoint) != null
                        logger().debug("Checking whether {} is subscribed: {}", subscription.endpoint, subscribed)
                        call.respond(subscribed)
                    }
                }
                route("/services/subscribe") {
                    options { call.respond(HttpStatusCode.OK) }
                    post {
                        try {
                            val subscription = call.receive<Subscription>()
                            logger().debug("Received subscription request for {}", subscription)
                            SubscriptionService.getInstance().subscribe(subscription)
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
                        val subscription = call.receive<Subscription>()
                        logger().debug("Received unsubscription request for {}", subscription)
                        SubscriptionService.getInstance().unsubscribe(subscription)
                        call.respond(HttpStatusCode.OK)
                    }
                }
                authenticate("basic-auth") {
                    route("/services/subscribers") {
                        get {
                            val subscriberCount = SubscriptionService.getInstance().getSubscriberCount()
                            logger().debug("Current count of subscribers: {}", subscriberCount)
                            call.respond(mapOf("subscribers" to subscriberCount))
                        }
                    }
                    route("/services/notifications") {
                        post {
                            val notification = call.receive<Notification>()
                            logger().debug("Received new notification: {}", notification)
                            if(!NotificationService.getInstance().add(notification)) {
                                call.respond(HttpStatusCode.BadRequest)
                            }
                            else {
                                call.respond(HttpStatusCode.Accepted)
                            }
                        }
                        get {
                            val notifications = NotificationService.getInstance().getAll()
                            logger().debug("Returning list of {} notifications", notifications.size)
                            call.respond(notifications)
                        }
                    }
                    route("services/notifications/{id}") {
                        delete {
                            val id = call.parameters["id"]!!.toLong()
                            logger().debug("Deleting notification with id {}", id)
                            NotificationService.getInstance().delete(id)
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
            }
        }.start(wait = true)
    }
}
