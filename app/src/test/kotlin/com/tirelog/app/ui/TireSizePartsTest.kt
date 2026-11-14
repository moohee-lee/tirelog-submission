package com.tirelog.app.ui

import com.tirelog.app.ui.main.TireFormState
import com.tirelog.app.ui.main.TireSizeParts
import com.tirelog.app.ui.main.tireSizeError
import com.tirelog.core.engine.NewSetInput
import com.tirelog.core.model.TireSetType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TireSizePartsTest {
    @Test fun standardSizesAndVehicleDefaultsPopulateTheThreeNumericParts() {
        val expected = TireSizeParts("235", "45", "18")
        for (value in listOf("235/45R18", "235/45 R18", "235 / 45 r18", "235/45ZR18")) {
            assertEquals(expected, TireSizeParts.parse(value))
        }
        assertEquals("235/45 R18", expected.text)
    }

    @Test fun optionalSizeCanBeEmptyButPartialOrZeroPartsCannotBeSaved() {
        val form = TireFormState(NewSetInput(TireSetType.ALL_SEASON, null, null))
        val today = LocalDate.of(2026, 11, 7)
        assertTrue(form.valid(today))
        assertNull(form.value().size)
        for (size in listOf("235/ R", "235/45 R", "/45 R18", "23/45 R18", "000/45 R18", "235/0 R18", "235/45 R0")) {
            form.size = size
            assertNotNull(tireSizeError(size))
            assertFalse(form.valid(today))
        }
        form.size = TireSizeParts("235", "45", "18").text
        assertTrue(form.valid(today))
        assertEquals("235/45 R18", form.value().size)
        form.size = TireSizeParts().text
        assertTrue(form.valid(today))
        assertNull(form.value().size)
    }

    @Test fun editingOtherInformationPreservesExistingSizeTextIncludingOlderFormats() {
        for (size in listOf("235/45R18", "235/45 R18 98W XL", "35x12.50R17")) {
            val form = TireFormState(NewSetInput(TireSetType.WINTER, size, null))
            form.model = "모델만 수정"
            assertEquals(size, form.value().size)
            assertTrue(form.valid(LocalDate.of(2026, 11, 7)))
        }
    }
}
