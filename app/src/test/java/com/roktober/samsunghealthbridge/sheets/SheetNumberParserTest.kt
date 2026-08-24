package com.roktober.samsunghealthbridge.sheets

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetNumberParserTest {
    @Test
    fun parsesLocalizedDecimalComma() {
        assertEquals(34.65525436, SheetNumberParser.parseDouble("34,65525436") ?: error("not parsed"), 0.0)
    }

    @Test
    fun parsesApiDecimalPoint() {
        assertEquals(34.65525436, SheetNumberParser.parseDouble("34.65525436") ?: error("not parsed"), 0.0)
    }
}
