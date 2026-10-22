package com.livingbook.ui.theme

import com.livingbook.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    @Test
    fun themeModeMapsToDarkFlag() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun readingStyleScalesSizeAndLineHeight() {
        val style = readingStyle(com.livingbook.data.ReadingFont.SANS, 1.5f)
        assertEquals(27f, style.fontSize.value, 0.01f)
        assertEquals(27f * 1.6f, style.lineHeight.value, 0.01f)
    }
}
