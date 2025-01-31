package at.rueckgr

import at.rueckgr.database.subscriptions
import org.ktorm.database.Database
import org.ktorm.database.use
import org.ktorm.dsl.eq
import org.ktorm.entity.add
import org.ktorm.entity.find
import org.ktorm.entity.removeIf
import org.ktorm.support.sqlite.SQLiteDialect

class SubscriptionService private constructor() {
    companion object {
        private var instance: SubscriptionService? = null

        fun getInstance(): SubscriptionService {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = SubscriptionService()
                    }
                }
            }
            return instance!!
        }
    }

    private fun connect(): Database {
        val connection = Database.connect(url = "jdbc:sqlite:database.db", dialect = SQLiteDialect())

        // TODO only run this once during startup
        val createTableQuery = """CREATE TABLE IF NOT EXISTS `subscription` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `endpoint` TEXT NOT NULL,
                `p256dh` TEXT NOT NULL,
                `auth` TEXT NOT NULL
            )"""
        connection.useConnection { conn ->
            conn.createStatement().use {
                it.executeUpdate(createTableQuery)
            }
        }

        return connection
    }

    fun subscribe(subscription: Subscription) {
        synchronized(this) {
            unsubscribe(subscription)

            val databaseSubscription = at.rueckgr.database.Subscription {
                endpoint = subscription.endpoint
                p256dh = subscription.keys.p256dh
                auth = subscription.keys.auth
            }
            connect().subscriptions.add(databaseSubscription)
        }
    }

    fun unsubscribe(subscription: Subscription) = unsubscribe(subscription.endpoint)

    fun unsubscribe(endpoint: String) {
        synchronized(this) {
            connect().subscriptions.removeIf { it.endpoint eq endpoint }
        }
    }

    fun getSubscription(endpoint: String) = connect().subscriptions.find { it.endpoint eq endpoint }

    fun getSubscriptions() = connect().subscriptions.asKotlinSequence()
}

data class SubscriptionKeys(val p256dh: String, val auth: String)

data class Subscription(val endpoint: String, val expirationTime: Long, val keys: SubscriptionKeys)
