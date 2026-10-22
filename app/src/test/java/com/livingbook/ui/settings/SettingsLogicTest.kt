package com.livingbook.ui.settings

import com.livingbook.data.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    @Test
    fun onlyProxyWithoutConsentAsksForConsent() {
        assertTrue(needsAiConsent(AiMode.PROXY, consented = false))
        assertFalse(needsAiConsent(AiMode.PROXY, consented = true))
        assertFalse(needsAiConsent(AiMode.DEMO, consented = false))
        assertFalse(needsAiConsent(AiMode.OFF, consented = false))
    }

    @Test
    fun textSizeShownAsPercent() {
        assertEquals("100%", textSizeLabel(1f))
        assertEquals("80%", textSizeLabel(0.8f))
        assertEquals("130%", textSizeLabel(1.3000001f))
    }
}
