package at.rueckgr

import at.rueckgr.database.NotificationQueue
import at.rueckgr.util.Logging
import at.rueckgr.util.logger
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


class PushService : Logging {
    private val jwtAlgorithm: Algorithm

    init {
        val serverKeys = ServerKeys.getInstance()
        jwtAlgorithm = Algorithm.ECDSA256(serverKeys.publicKey, serverKeys.privateKey)
    }

    fun sendMessage(notificationQueue: NotificationQueue): PushResult {
        logger().info("Sending notification queue: {} for notification {} and subscription {}",
            notificationQueue.id, notificationQueue.notification.id, notificationQueue.subscription.id)

        val message = ObjectMapper()
            .findAndRegisterModules()
            .writeValueAsString(notificationQueue.notification)
        val subscription = notificationQueue.subscription

        val bytes: ByteArray = CryptoService.getInstance().encrypt(
            ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(message),
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
        val email = System.getenv("EMAIL")
        val token = JWT.create()
            .withAudience(origin)
            .withExpiresAt(expires)
            .withSubject("mailto:$email")
            .sign(this.jwtAlgorithm)
        val key = ServerKeys.getInstance().publicKeyBase64

        return runBlocking {
            val response = client.post(subscription.endpoint) {
                headers {
                    append("TTL", System.getenv("TTL"))
                    append(HttpHeaders.Authorization, "vapid t=$token, k=$key")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentEncoding, "aes128gcm")
                }
                setBody(bytes)
            }
            logger().info("Status code received from service: {}", response.status)

            return@runBlocking if (response.status.value < 300) {
                PushResult.SUCCESS
            }
            else if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone) {
                PushResult.FAIL
            }
            else {
                PushResult.RETRY
            }
        }
    }
}

enum class PushResult { SUCCESS, RETRY, FAIL }
