package co.adsparkle.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable value object representing one postback request body.
 * Serialises to / deserialises from JSON using [org.json] (no extra deps).
 */
internal data class PostbackPayload(
    val clickId:       String,
    val clickIds:      List<String>,
    val eventType:     String,
    val userId:        String,
    val transactionId: String?               = null,
    val amount:        Double?               = null,
    val currency:      String?               = null,
    val productIds:    List<String>?         = null,
    val customParams:  Map<String, String>?  = null
) {
    fun toJson(): String {
        val obj = JSONObject().apply {
            put("click_id",  clickId)
            put("click_ids", JSONArray(clickIds))
            put("event_type", eventType)
            put("user_id",   userId)
            transactionId?.let { put("transaction_id", it) }
            amount?.let        { put("amount", it) }
            currency?.let      { put("currency", it.uppercase()) }
            productIds?.let {
                put("product_ids", JSONArray(it))
            }
            customParams?.let { params ->
                val cp = JSONObject()
                params.forEach { (k, v) -> cp.put(k, v) }
                put("custom_params", cp)
            }
        }
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): PostbackPayload {
            val obj        = JSONObject(json)
            val clickIdsArr = obj.optJSONArray("click_ids")
            val clickIds   = if (clickIdsArr != null) {
                (0 until clickIdsArr.length()).map { clickIdsArr.getString(it) }
            } else emptyList()

            val productIdsArr = obj.optJSONArray("product_ids")
            val productIds = if (productIdsArr != null) {
                (0 until productIdsArr.length()).map { productIdsArr.getString(it) }
            } else null

            val cpObj = obj.optJSONObject("custom_params")
            val customParams: Map<String, String>? = cpObj?.let { cp ->
                val map = mutableMapOf<String, String>()
                cp.keys().forEach { k -> map[k] = cp.getString(k) }
                map
            }

            return PostbackPayload(
                clickId       = obj.getString("click_id"),
                clickIds      = clickIds,
                eventType     = obj.getString("event_type"),
                userId        = obj.getString("user_id"),
                transactionId = obj.optString("transaction_id").ifEmpty { null },
                amount        = if (obj.has("amount")) obj.getDouble("amount") else null,
                currency      = obj.optString("currency").ifEmpty { null },
                productIds    = productIds,
                customParams  = customParams
            )
        }
    }
}
