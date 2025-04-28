package at.rueckgr

import at.rueckgr.database.*
import at.rueckgr.util.Logging
import at.rueckgr.util.logger
import org.ktorm.database.Database
import org.ktorm.database.use
import org.ktorm.dsl.eq
import org.ktorm.entity.*
import org.ktorm.support.sqlite.SQLiteDialect
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
        val createTableQuery1 = """CREATE TABLE IF NOT EXISTS `notification` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `title` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `icon` TEXT NOT NULL,
                `date_time` DATETIME NOT NULL
            )"""
        val createTableQuery2 = """CREATE TABLE IF NOT EXISTS `notification_queue` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `notification_id` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL
            )"""
        connect().useConnection { conn ->
            conn.createStatement().use {
                it.executeUpdate(createTableQuery1)
                it.executeUpdate(createTableQuery2)
            }
        }

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

    private fun connect() = Database.connect(url = "jdbc:sqlite:data/database.db", dialect = SQLiteDialect())

    fun add(restNotification: RestNotification): Boolean {
        logger().info("Received notification {}", restNotification)

        try {
            val entity = Notification {
                title = restNotification.title
                text = restNotification.text
                url = restNotification.url
                icon = restNotification.icon
                dateTime = restNotification.dateTime ?: LocalDateTime.now()
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

    private fun mapNotification(entity: Notification) = RestNotification(entity.id, entity.title, entity.text, entity.url, entity.icon, entity.dateTime)

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
            val dateTime = connect().notifications.sortedBy { it.dateTime }.firstOrNull()?.dateTime
            if (dateTime == null) {
                logger().debug("No scheduled notifications found, not scheduling next run")
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
            for (notificationItem in connection.notifications) {
                if (!notificationsInQueue.contains(notificationItem.id)) {
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
            for(notificationId in handledNotificationIds) {
                if (!notificationsNowInQueue.contains(notificationId)) {
                    deleteNotification(notificationId, false)
                }
            }
        }
        catch (e: Exception) {
            logger().error(e)
        }
        finally {
            scheduleNextRun()
        }
    }
}

data class RestNotification(val id: Long?, val title: String, val text: String, val url: String, val icon: String, val dateTime: LocalDateTime?)
