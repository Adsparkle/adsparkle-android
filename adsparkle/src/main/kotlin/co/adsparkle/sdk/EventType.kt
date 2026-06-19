package co.adsparkle.sdk

/**
 * Canonical event types accepted by the AdSparkle postback endpoint.
 * Common caller aliases are normalised to these values before sending.
 */
enum class EventType(val canonical: String) {
    INSTALL("install"),
    SIGN_UP("sign_up"),
    LOGIN("login"),
    DOWNLOAD("download"),
    PURCHASE("purchase"),
    SUBSCRIPTION("subscription"),
    REFUND("refund");

    companion object {
        /**
         * Resolves an arbitrary caller-supplied string to a canonical [EventType], or returns
         * `null` if the value is unknown. Matching is case-insensitive and alias-aware.
         */
        fun resolve(raw: String): EventType? {
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                "install"                  -> INSTALL
                "sign_up", "signup", "register" -> SIGN_UP
                "login"                    -> LOGIN
                "download"                 -> DOWNLOAD
                "purchase", "order", "sale" -> PURCHASE
                "subscription", "subscribe" -> SUBSCRIPTION
                "refund", "chargeback"      -> REFUND
                else                       -> null
            }
        }
    }
}
