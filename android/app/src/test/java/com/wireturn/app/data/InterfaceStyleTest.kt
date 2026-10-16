package com.wireturn.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceStyleTest {
    @Test fun existingInstallWithoutPreferenceUsesNewInterface() {
        assertEquals(InterfaceStyle.ORBIT, InterfaceStyle.fromStored(null))
    }
    @Test fun explicitClassicSurvivesPreferenceReload() {
        assertEquals(InterfaceStyle.CLASSIC, InterfaceStyle.fromStored("CLASSIC"))
    }
    @Test fun explicitOrbitSurvivesPreferenceReload() {
        assertEquals(InterfaceStyle.ORBIT, InterfaceStyle.fromStored("ORBIT"))
    }
    @Test fun unknownValueDoesNotBreakStartup() {
        assertEquals(InterfaceStyle.ORBIT, InterfaceStyle.fromStored("future-layout"))
    }
}
