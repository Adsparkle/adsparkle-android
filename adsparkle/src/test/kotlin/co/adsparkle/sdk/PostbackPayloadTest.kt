package co.adsparkle.sdk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostbackPayloadTest {

    private val samplePayload = PostbackPayload(
        clickId       = "550e8400-e29b-41d4-a716-446655440000",
        clickIds      = listOf("550e8400-e29b-41d4-a716-446655440000"),
        eventType     = "purchase",
        userId        = "user_123",
        transactionId = "txn_abc",
        amount        = 49.99,
        currency      = "usd",
        productIds    = listOf("sku_1", "sku_2"),
        customParams  = mapOf("campaign" to "summer_sale")
    )

    @Test fun `toJson contains all required fields`() {
        val json = JSONObject(samplePayload.toJson())
        assertEquals("550e8400-e29b-41d4-a716-446655440000", json.getString("click_id"))
        assertEquals("purchase",                              json.getString("event_type"))
        assertEquals("user_123",                              json.getString("user_id"))
        assertEquals("txn_abc",                               json.getString("transaction_id"))
        assertEquals(49.99,                                   json.getDouble("amount"), 0.001)
    }

    @Test fun `currency is uppercased in output`() {
        val json = JSONObject(samplePayload.toJson())
        assertEquals("USD", json.getString("currency"))
    }

    @Test fun `click_ids array is present`() {
        val json     = JSONObject(samplePayload.toJson())
        val arr      = json.getJSONArray("click_ids")
        assertEquals(1, arr.length())
        assertEquals("550e8400-e29b-41d4-a716-446655440000", arr.getString(0))
    }

    @Test fun `product_ids array is present`() {
        val json = JSONObject(samplePayload.toJson())
        val arr  = json.getJSONArray("product_ids")
        assertEquals(2, arr.length())
    }

    @Test fun `custom_params object is present`() {
        val json = JSONObject(samplePayload.toJson())
        val cp   = json.getJSONObject("custom_params")
        assertEquals("summer_sale", cp.getString("campaign"))
    }

    @Test fun `optional fields absent when null`() {
        val minimal = PostbackPayload(
            clickId   = "550e8400-e29b-41d4-a716-446655440000",
            clickIds  = listOf("550e8400-e29b-41d4-a716-446655440000"),
            eventType = "install",
            userId    = "anon_abc123"
        )
        val json = JSONObject(minimal.toJson())
        assertFalse(json.has("transaction_id"))
        assertFalse(json.has("amount"))
        assertFalse(json.has("currency"))
        assertFalse(json.has("product_ids"))
        assertFalse(json.has("custom_params"))
    }

    @Test fun `fromJson round-trips correctly`() {
        val serialised = samplePayload.toJson()
        val restored   = PostbackPayload.fromJson(serialised)
        assertEquals(samplePayload.clickId,       restored.clickId)
        assertEquals(samplePayload.eventType,     restored.eventType)
        assertEquals(samplePayload.userId,        restored.userId)
        assertEquals(samplePayload.transactionId, restored.transactionId)
        assertEquals(samplePayload.amount,        restored.amount)
        // Currency is uppercased on serialisation so round-trip returns uppercase
        assertEquals("USD",                       restored.currency)
        assertEquals(samplePayload.productIds,    restored.productIds)
        assertEquals(samplePayload.customParams,  restored.customParams)
    }
}
