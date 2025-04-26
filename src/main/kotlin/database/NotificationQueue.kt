package at.rueckgr.database

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.*

val Database.notificationQueues get() = this.sequenceOf(NotificationQueues)

interface NotificationQueue : Entity<NotificationQueue> {
    companion object : Entity.Factory<NotificationQueue>()

    var id: Long
    var notification: Notification
    var subscription: Subscription
}

object NotificationQueues : Table<NotificationQueue>("notification_queue") {
    var id = long("id").primaryKey().bindTo { it.id }
    var notificationId = long("notification_id").references(Notifications) { it.notification }
    var subscriptionId = long("subscription_id").references(Subscriptions) { it.subscription }
}
