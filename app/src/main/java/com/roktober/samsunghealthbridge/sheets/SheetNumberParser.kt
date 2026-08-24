package com.roktober.samsunghealthbridge.sheets

object SheetNumberParser {
    fun parseDouble(value: String): Double? = value.replace(',', '.').toDoubleOrNull()
}
