package at.rueckgr

import at.rueckgr.database.*
import at.rueckgr.util.Logging
import at.rueckgr.util.logger
import org.ktorm.database.Database
import org.ktorm.dsl.eq
import org.ktorm.dsl.lte
import org.ktorm.dsl.neq
import org.ktorm.dsl.notInList
import org.ktorm.entity.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

class NotificationService private constructor() : Logging {
    private var future: ScheduledFuture<*>? = null
    private val executorService = Executors.newScheduledThreadPool(1)

    init {
        Database.migrate()

        scheduleNextRun()
    }

    companion object {
        private var instance: NotificationService? = null

        fun getInstance(): NotificationService {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = NotificationService()
                    }
                }
            }
            return instance!!
        }
    }

    private fun connect() = Database.connect()

    fun add(restNotification: RestNotification): Boolean {
        logger().info("Received notification {}", restNotification)

        try {
            val entity = Notification {
                title = restNotification.title
                text = restNotification.text
                url = restNotification.url
                icon = restNotification.icon
                dateTime = restNotification.dateTime ?: LocalDateTime.now()
                status = NotificationStatus.SCHEDULED
            }
            connect().notifications.add(entity)
            entity.flushChanges()

            logger().info("Created notification with id {}", entity.id)

            scheduleNextRun()

            return true
        }
        catch (e: Exception) {
            logger().error("Error creating notification", e)
            return false
        }
    }

    fun getAll() = connect().notifications.map { mapNotification(it) }.toList()

    private fun mapNotification(entity: Notification) = RestNotification(entity.id, entity.title, entity.text, entity.url, entity.icon, entity.dateTime, entity.status)

    fun updateNotificationStatus(id: Long, status: NotificationStatus) {
        val notification = connect().notifications.find { it.id eq id }
        if (notification != null) {
            notification.status = status
            notification.flushChanges()
        }
    }

    fun deleteNotification(id: Long, reschedule: Boolean = true) {
        logger().info("Deleting notification {}", id)
        connect().notifications.removeIf { it.id eq id }
        if (reschedule) {
            scheduleNextRun()
        }
    }

    private fun deleteQueueItem(id: Long) {
        logger().info("Deleting notification queue item {}", id)
        connect().notificationQueues.removeIf { it.id eq id }
    }

    private fun deleteSubscription(id: Long) {
        logger().info("Deleting subscription {}", id)
        val connection = connect()
        connection.notificationQueues.removeIf { it.subscriptionId eq id }
        connection.subscriptions.removeIf { it.id eq id }
    }

    private fun scheduleNextRun() {
        synchronized(this) {
            if (this.future != null) {
                this.future!!.cancel(false)
            }
            val dateTime = connect().notifications
                .filter { it.status neq NotificationStatus.PROCESSED }
                .sortedBy { it.dateTime }
                .firstOrNull()
                ?.dateTime
            if (dateTime == null) {
                logger().debug("No notifications in state SCHEDULED or SENDING found, not scheduling next run")
                return
            }
            // add 1 because otherwise the scheduler might run fractions of a second too early
            val seconds = max(System.getenv("RUN_DELAY").toLong(), ChronoUnit.SECONDS.between(LocalDateTime.now(), dateTime) + 1)
            this.future = executorService.schedule({ sendNotifications() }, seconds, TimeUnit.SECONDS)

            logger().info("Scheduled next run for {} (in {} seconds)", LocalDateTime.now().plusSeconds(seconds), seconds)
        }
    }

    private fun sendNotifications() {
        try {
            val connection = connect()

            val notificationsInQueue = connection
                .notificationQueues
                .map { it.notification.id }
                .toSet()
            logger().debug("Notifications that already have queue entries: {}", notificationsInQueue)

            val subscriptions = connection.subscriptions.toList()
            logger().debug("Subscription count: {}", subscriptions.size)

            // add queue entries for notifications that do not have queue entries yet
            connection.notifications
                    .filter { it.status eq NotificationStatus.SCHEDULED }
                    .filter { it.dateTime lte LocalDateTime.now() }
                    .filter { it.id notInList notificationsInQueue }
                    .forEach { notificationItem ->
                logger().debug("Updating notification {} to status SENDING", notificationItem.id)
                notificationItem.status = NotificationStatus.SENDING
                notificationItem.flushChanges()

                logger().debug("Creating notification queue entries for notification {}", notificationItem.id)
                for (subscriptionItem in subscriptions) {
                    logger().debug("Creating notification queue entry for notification {} and subscription {}",
                        notificationItem.id, subscriptionItem.id)
                    val entity = NotificationQueue {
                        notification = notificationItem
                        subscription = subscriptionItem
                    }
                    connect().notificationQueues.add(entity)
                    entity.flushChanges()

                    logger().info("Created notification queue entry with id {} for notification {} and subscription {}",
                        entity.id, notificationItem.id, subscriptionItem.id)
                }
            }

            val handledNotificationIds = HashSet<Long>()
            val failedSubscriptions = HashSet<Long>()
            connection.notificationQueues
                .sortedBy { it.id }
                .take(System.getenv("BATCH_SIZE").toInt())
                .forEach {
                    if (failedSubscriptions.contains(it.subscription.id)) {
                        logger().info("Skipping notification queue item {} as the subscription has already failed with another item", it.id)
                        return@forEach
                    }
                    logger().info("Sending notification queue item {}", it.id)
                    val result = PushService().sendMessage(it)
                    if (result == PushResult.SUCCESS) {
                        deleteQueueItem(it.id)
                        handledNotificationIds.add(it.notification.id)
                    }
                    else if (result == PushResult.FAIL) {
                        failedSubscriptions.add(it.subscription.id!!)
                    }
                }

            failedSubscriptions.forEach { deleteSubscription(it) }

            val notificationsNowInQueue = connection
                .notificationQueues
                .map { it.notification.id }
                .toSet()
            logger().debug("Notifications that still have queue entries: {}", notificationsNowInQueue)

            // remove all notifications that do not have queue entries anymore
            handledNotificationIds
                .filter { !notificationsNowInQueue.contains(it) }
                .forEach {
                    updateNotificationStatus(it, NotificationStatus.PROCESSED)
                }
        }
        catch (e: Exception) {
            e.printStackTrace()
            logger().error(e)
        }
        finally {
            scheduleNextRun()
        }
    }
}

data class RestNotification(val id: Long?, val title: String, val text: String, val url: String, val icon: String, val dateTime: LocalDateTime?, val status: NotificationStatus?)
