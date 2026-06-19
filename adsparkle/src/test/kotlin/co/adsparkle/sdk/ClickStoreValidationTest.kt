package co.adsparkle.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickStoreValidationTest {

    @Test fun `valid uuid v4 passes`() {
        assertTrue(ClickStore.isValidClickId("550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(ClickStore.isValidClickId("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        assertTrue(ClickStore.isValidClickId("00000000-0000-0000-0000-000000000000"))
    }

    @Test fun `invalid formats are rejected`() {
        // Too short
        assertFalse(ClickStore.isValidClickId("550e8400-e29b-41d4-a716"))
        // No dashes
        assertFalse(ClickStore.isValidClickId("550e8400e29b41d4a716446655440000"))
        // Extra chars
        assertFalse(ClickStore.isValidClickId("550e8400-e29b-41d4-a716-44665544000Z"))
        // Empty
        assertFalse(ClickStore.isValidClickId(""))
        // Uppercase UUID with X (hex only)
        assertFalse(ClickStore.isValidClickId("GGGGGGGG-e29b-41d4-a716-446655440000"))
    }

    @Test fun `uuid validation is case insensitive`() {
        // Uppercase hex is valid
        assertTrue(ClickStore.isValidClickId("550E8400-E29B-41D4-A716-446655440000"))
    }
}
