package io.github.bytejuno.thetacore

import android.content.ActivityNotFoundException
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDiagnosticsTest {
    @Test fun opensOnlyAppDetailsWhenAvailable() {
        val attempted = mutableListOf<String>()
        assertTrue(openBackgroundSettings { attempted.add(it) })
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), attempted)
    }

    @Test fun missingAppDetailsFallsBackToBatterySettings() {
        val attempted = mutableListOf<String>()
        assertTrue(openBackgroundSettings {
            attempted.add(it)
            if (it == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException()
        })
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), attempted)
    }

    @Test fun deniedSettingsShowManualInstructions() {
        var attempts = 0
        assertFalse(openBackgroundSettings {
            attempts++
            throw SecurityException()
        })
        assertEquals(2, attempts)
    }
}
