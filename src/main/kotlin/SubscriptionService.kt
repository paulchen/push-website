package at.rueckgr

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

    // TODO persistence
    private val subscriptions: MutableMap<String, Subscription> = mutableMapOf()

    fun subscribe(subscription: Subscription) {
        subscriptions[subscription.endpoint] = subscription
    }

    fun unsubscribe(subscription: Subscription) = subscriptions.remove(subscription.endpoint)

    fun getSubscription(endpoint: String) = subscriptions[endpoint]

    fun getSubscriptions() = subscriptions
}

data class SubscriptionKeys(val p256dh: String, val auth: String)

data class Subscription(val endpoint: String, val expirationTime: Long, val keys: SubscriptionKeys)
