package com.roktober.samsunghealthbridge.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetTargetResolverTest {
    private val resolver =
        SpreadsheetTargetResolver(
            canonicalId = "canonical",
            legacyIds = setOf("legacy"),
        )

    @Test
    fun `clean install uses canonical spreadsheet`() {
        assertEquals("canonical", resolver.resolve(null))
    }

    @Test
    fun `legacy spreadsheet migrates to canonical spreadsheet`() {
        assertEquals("canonical", resolver.resolve("legacy"))
    }

    @Test
    fun `explicit custom spreadsheet remains unchanged`() {
        assertEquals("custom", resolver.resolve("custom"))
    }
}
