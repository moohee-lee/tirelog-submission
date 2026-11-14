package com.tirelog.app.ui

import androidx.compose.ui.text.AnnotatedString
import com.tirelog.app.ui.main.RegistrationNumberTransformation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationNumberTransformationTest {
    @Test fun groupingPreservesEveryDecimalDigitAndPartialInput() {
        for ((raw, formatted) in mapOf("0" to "0", "999" to "999", "1000" to "1,000",
            "12345.6789" to "12,345.6789", "1000000." to "1,000,000.", "0000" to "0,000",
            "" to "", "-1" to "-1", "abc" to "abc", "1.2.3" to "1.2.3")) {
            val result = RegistrationNumberTransformation.filter(AnnotatedString(raw))
            assertEquals(formatted, result.text.text)
            for (offset in 0..raw.length) {
                val transformed = result.offsetMapping.originalToTransformed(offset)
                assertTrue(transformed in 0..formatted.length)
                assertEquals(offset, result.offsetMapping.transformedToOriginal(transformed))
            }
            for (offset in 0..formatted.length)
                assertTrue(result.offsetMapping.transformedToOriginal(offset) in 0..raw.length)
        }
    }
}
