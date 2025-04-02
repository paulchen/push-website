package at.rueckgr.database

import org.ktorm.database.Database
import org.ktorm.entity.Entity
import org.ktorm.entity.sequenceOf
import org.ktorm.schema.*
import java.time.LocalDateTime

val Database.notifications get() = this.sequenceOf(Notifications)

interface Notification : Entity<Notification> {
    companion object : Entity.Factory<Notification>()

    var id: Long
    var title: String
    var text: String
    var url: String
    var icon: String
    var dateTime: LocalDateTime
}

object Notifications : Table<Notification>("notification") {
    var id = long("id").primaryKey().bindTo { it.id }
    var title = varchar("title").bindTo { it.title }
    var text = varchar("text").bindTo { it.text }
    var url = varchar("url").bindTo { it.url }
    var icon = varchar("icon").bindTo { it.icon }
    var dateTime = datetime("date_time").bindTo { it.dateTime }
}
