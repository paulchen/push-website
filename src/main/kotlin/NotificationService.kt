package at.rueckgr

import at.rueckgr.database.notifications
import at.rueckgr.util.Logging
import at.rueckgr.util.logger
import org.ktorm.database.Database
import org.ktorm.database.use
import org.ktorm.dsl.eq
import org.ktorm.dsl.lte
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
        val createTableQuery = """CREATE TABLE IF NOT EXISTS `notification` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `title` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `icon` TEXT NOT NULL,
                `date_time` DATETIME NOT NULL
            )"""
        connect().useConnection { conn ->
            conn.createStatement().use {
                it.executeUpdate(createTableQuery)
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

    fun add(notification: Notification): Boolean {
        logger().info("Received notification {}", notification)

        if (notification.dateTime == null) {
            PushService().sendMessageToAllSubscribers(notification)
        }
        else if (notification.dateTime.isBefore(LocalDateTime.now())) {
            return false
        }
        else {
            val entity = at.rueckgr.database.Notification {
                title = notification.title
                text = notification.text
                url = notification.url
                icon = notification.icon
                dateTime = notification.dateTime
            }
            connect().notifications.add(entity)
            entity.flushChanges()

            logger().info("Created notification with id {}", entity.id)

            scheduleNextRun()
        }

        return true
    }

    fun getAll() = connect().notifications.map { mapNotification(it) }.toList()

    private fun mapNotification(entity: at.rueckgr.database.Notification) = Notification(entity.id, entity.title, entity.text, entity.url, entity.icon, entity.dateTime)

    fun delete(id: Long) {
        logger().info("Deleting notification {}", id)
        connect().notifications.removeIf { it.id eq id }
        scheduleNextRun()
    }

    private fun scheduleNextRun() {
        synchronized(this) {
            if (this.future != null) {
                this.future!!.cancel(false)
            }
            val dateTime = connect().notifications.sortedByDescending { it.dateTime }.firstOrNull()?.dateTime
            if (dateTime == null) {
                logger().debug("No scheduled notifications found, not scheduling next run")
                return
            }
            // add 1 because otherwise the scheduler might run fractions of a second too early
            val seconds = max(30, ChronoUnit.SECONDS.between(LocalDateTime.now(), dateTime) + 1)
            this.future = executorService.schedule({ sendNotifications() }, seconds, TimeUnit.SECONDS)

            logger().info("Scheduled next run for {} (in {} seconds)", LocalDateTime.now().plusSeconds(seconds), seconds)
        }
    }

    private fun sendNotifications() {
        try {
            val connection = connect()
            connection.notifications
                .filter { it.dateTime lte LocalDateTime.now() }
                .forEach {
                    logger().info("Sending notification {}", it.id)
                    PushService().sendMessageToAllSubscribers(mapNotification(it))
                    delete(it.id)
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

data class Notification(val id: Long?, val title: String, val text: String, val url: String, val icon: String, val dateTime: LocalDateTime?)
