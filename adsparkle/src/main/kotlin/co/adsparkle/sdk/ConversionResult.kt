package co.adsparkle.sdk

/**
 * Outcome of a [AdSparkle.trackConversion] call, returned via callback and
 * also exposed as return-value-based result for coroutine-friendly wrappers.
 */
sealed class ConversionResult {
    /** The postback was sent successfully (HTTP 2xx). */
    object Success : ConversionResult()

    /**
     * No click id is present in the local chain — the user arrived organically.
     * This is an expected, non-error case.
     */
    object NoClickId : ConversionResult()

    /**
     * The supplied event type string could not be mapped to a canonical event.
     * @param rawType The string the caller passed.
     */
    data class UnknownEventType(val rawType: String) : ConversionResult()

    /**
     * The postback failed and has been added to the offline retry queue.
     * @param cause The underlying exception, if any.
     */
    data class Queued(val cause: Exception? = null) : ConversionResult()
}
