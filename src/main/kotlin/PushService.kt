package at.rueckgr

import at.rueckgr.database.Subscription
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId


class PushService {
    private val jwtAlgorithm: Algorithm

    init {
        val serverKeys = ServerKeys.getInstance()
        jwtAlgorithm = Algorithm.ECDSA256(serverKeys.publicKey, serverKeys.privateKey)
    }

    fun sendMessageToAllSubscribers(notification: Notification) {
        val message = ObjectMapper().writeValueAsString(notification)
        SubscriptionService.getInstance().getSubscriptions().forEach {
            // TODO use a queue here
            if (!this.sendMessage(it, message)) {
                SubscriptionService.getInstance().unsubscribe(it.endpoint)
            }
        }
    }

    private fun sendMessage(subscription: Subscription, message: String): Boolean {
        val bytes: ByteArray = CryptoService.getInstance().encrypt(
            ObjectMapper().writeValueAsString(message),
            subscription.p256dh, subscription.auth, 0
        )

        val client = HttpClient(CIO) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }

        val uri = URI(subscription.endpoint)
        val origin = uri.scheme + "://" + uri.host

        val expires = LocalDateTime.now().plusHours(12).atZone(ZoneId.systemDefault()).toInstant()
        val email = "paulchen@rueckgr.at" // TODO configurable
        val token = JWT.create()
            .withAudience(origin)
            .withExpiresAt(expires)
            .withSubject("mailto:$email")
            .sign(this.jwtAlgorithm)
        val key = ServerKeys.getInstance().publicKeyBase64

        runBlocking {
            val response = client.post(subscription.endpoint) {
                headers {
                    append("TTL", "180")
                    append(HttpHeaders.Authorization, "vapid t=$token, k=$key")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentEncoding, "aes128gcm")
                }
                setBody(bytes)
            }
            println(response.status) // TODO error handling
        }

        return true
    }
}

