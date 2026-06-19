package co.adsparkle.sdk

/**
 * Optional payload that accompanies a tracked event.
 *
 * All fields are nullable. Provide only the ones relevant to the event type:
 *
 * | Field          | Used for                                  |
 * |----------------|-------------------------------------------|
 * | [transactionId]| purchase, subscription, refund            |
 * | [amount]       | purchase, subscription                    |
 * | [currency]     | purchase, subscription (ISO 4217, e.g. USD)|
 * | [productIds]   | any (optional list of product identifiers)|
 * | [customParams] | any (optional flat string→string map)     |
 *
 * Java interop: a no-arg constructor and per-field defaults are provided, and
 * the type is also usable via [AdSparkleEvent.Builder].
 */
data class AdSparkleEvent @JvmOverloads constructor(
    @JvmField val transactionId: String? = null,
    @JvmField val amount: Double? = null,
    @JvmField val currency: String? = null,
    @JvmField val productIds: List<String>? = null,
    @JvmField val customParams: Map<String, String>? = null,
) {

    /**
     * Java-friendly builder for [AdSparkleEvent].
     *
     * ```java
     * AdSparkleEvent e = new AdSparkleEvent.Builder()
     *     .transactionId("txn_123")
     *     .amount(9.99)
     *     .currency("USD")
     *     .build();
     * ```
     */
    class Builder {
        private var transactionId: String? = null
        private var amount: Double? = null
        private var currency: String? = null
        private var productIds: List<String>? = null
        private var customParams: Map<String, String>? = null

        fun transactionId(value: String?) = apply { this.transactionId = value }
        fun amount(value: Double?) = apply { this.amount = value }
        fun currency(value: String?) = apply { this.currency = value }
        fun productIds(value: List<String>?) = apply { this.productIds = value }
        fun customParams(value: Map<String, String>?) = apply { this.customParams = value }

        fun build(): AdSparkleEvent =
            AdSparkleEvent(transactionId, amount, currency, productIds, customParams)
    }
}
