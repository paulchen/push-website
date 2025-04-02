package at.rueckgr

fun main() {
    // to schedule submission of notifications that are already in the database
    NotificationService.getInstance()

    RestApi().start()
}
