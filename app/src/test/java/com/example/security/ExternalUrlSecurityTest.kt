package com.example.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalUrlSecurityTest {

    @Test
    fun acceptsAbsoluteHttpsRetailerUrls() {
        assertEquals(
            "https://www.anker.com/eu-en/search?q=Anker%20Prime",
            safeExternalHttpsUrl("https://www.anker.com/eu-en/search?q=Anker%20Prime")
        )
        assertEquals(
            "https://example.com:443/product?id=1",
            safeExternalHttpsUrl("https://example.com:443/product?id=1")
        )
    }

    @Test
    fun rejectsCleartextAndApplicationSpecificSchemes() {
        assertNull(safeExternalHttpsUrl("http://example.com/product"))
        assertNull(safeExternalHttpsUrl("javascript:alert(1)"))
        assertNull(safeExternalHttpsUrl("intent://example.com/#Intent;scheme=https;end"))
        assertNull(safeExternalHttpsUrl("file:///data/user/0/private.txt"))
    }

    @Test
    fun rejectsMalformedHostsCredentialsAndUnusualPorts() {
        assertNull(safeExternalHttpsUrl("https:///missing-host"))
        assertNull(safeExternalHttpsUrl("https://user:password@example.com/product"))
        assertNull(safeExternalHttpsUrl("https://example.com:8443/product"))
        assertNull(safeExternalHttpsUrl("https://localhost/private"))
        assertNull(safeExternalHttpsUrl("https://router.local/admin"))
    }

    @Test
    fun rejectsBlankControlCharactersAndOversizedUrls() {
        assertNull(safeExternalHttpsUrl(null))
        assertNull(safeExternalHttpsUrl("   "))
        assertNull(safeExternalHttpsUrl("https://example.com/\nproduct"))
        assertNull(safeExternalHttpsUrl("https://example.com/" + "a".repeat(4100)))
    }
}
