package at.rueckgr.database

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.*

val Database.subscriptions get() = this.sequenceOf(Subscriptions)

interface Subscription : Entity<Subscription> {
    companion object : Entity.Factory<Subscription>()

    var id: Long?
    var endpoint: String
    var p256dh: String
    var auth: String
}

object Subscriptions : Table<Subscription>("subscription") {
    var id = long("id").primaryKey().bindTo { it.id }
    var endpoint = varchar("endpoint").bindTo { it.endpoint }
    var p256dh = varchar("p256dh").bindTo { it.p256dh }
    var auth = varchar("auth").bindTo { it.auth }
}
