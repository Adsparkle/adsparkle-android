package co.adsparkle.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventTypeTest {

    @Test fun `install resolves correctly`() {
        assertEquals(EventType.INSTALL, EventType.resolve("install"))
        assertEquals(EventType.INSTALL, EventType.resolve("INSTALL"))
    }

    @Test fun `sign_up aliases all resolve`() {
        listOf("sign_up", "signup", "register", "SIGN_UP", "Register").forEach {
            assertEquals("Failed for '$it'", EventType.SIGN_UP, EventType.resolve(it))
        }
    }

    @Test fun `purchase aliases resolve`() {
        listOf("purchase", "order", "sale").forEach {
            assertEquals("Failed for '$it'", EventType.PURCHASE, EventType.resolve(it))
        }
    }

    @Test fun `subscription aliases resolve`() {
        listOf("subscription", "subscribe").forEach {
            assertEquals("Failed for '$it'", EventType.SUBSCRIPTION, EventType.resolve(it))
        }
    }

    @Test fun `refund aliases resolve`() {
        listOf("refund", "chargeback").forEach {
            assertEquals("Failed for '$it'", EventType.REFUND, EventType.resolve(it))
        }
    }

    @Test fun `unknown type returns null`() {
        assertNull(EventType.resolve("unknown_event"))
        assertNull(EventType.resolve(""))
        assertNull(EventType.resolve("  "))
    }

    @Test fun `canonical values are correct`() {
        assertEquals("sign_up",      EventType.SIGN_UP.canonical)
        assertEquals("subscription", EventType.SUBSCRIPTION.canonical)
        assertEquals("purchase",     EventType.PURCHASE.canonical)
        assertEquals("refund",       EventType.REFUND.canonical)
    }
}
