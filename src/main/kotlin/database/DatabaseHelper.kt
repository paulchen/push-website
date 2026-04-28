package at.rueckgr.database

import org.apache.logging.log4j.LogManager.getLogger
import org.ktorm.database.Database
import org.ktorm.support.sqlite.SQLiteDialect

fun Database.Companion.connect() = Database.connect(url = "jdbc:sqlite:data/database.db", dialect = SQLiteDialect())

fun Database.Companion.migrate() {
    val logger = getLogger(Database.Companion::class.java)

    val migrations = listOf(
        listOf(
            """CREATE TABLE IF NOT EXISTS `notification` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `title` TEXT NOT NULL,
                `text` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `icon` TEXT NOT NULL,
                `date_time` DATETIME NOT NULL
            )""",
            """CREATE TABLE IF NOT EXISTS `notification_queue` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT,
                `notification_id` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL
            )"""
        ),
        listOf(
            """ALTER TABLE `notification` ADD COLUMN `status` TEXT NOT NULL"""
        )
    )

    val db = Database.connect()
    var version = getVersion(db)

    while (version < migrations.size) {
        logger.info("Current database version: {}", version)
        db.useTransaction {
            val statements = migrations[version]
            logger.info("Migrating database version to version {} using {} statements", version + 1, statements.size)

            statements.forEach { sql ->
                db.useConnection {
                    logger.info("Running SQL statement for migration: {}", sql.replace(Regex("\\s+"), " ").trim())

                    @Suppress("SqlSourceToSinkFlow")
                    it.createStatement().use { statement -> statement.executeUpdate(sql) }
                }
            }

            version++
            setVersion(db, version)
            logger.info("Successfully updated database to version {}", version)
        }
    }
}

fun getVersion(db: Database) = db.useConnection { conn ->
    conn.createStatement().executeQuery("PRAGMA user_version").use {
        if (it.next()) {
            it.getInt(1)
        }
        else {
            0
        }
    }
}

fun setVersion(db: Database, version: Int) =
    db.useConnection {
        it.createStatement().execute("PRAGMA user_version = $version")
    }
