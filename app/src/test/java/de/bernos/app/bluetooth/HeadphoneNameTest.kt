package de.bernos.app.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphoneNameTest {

    @Test
    fun `Sonos Ace wird erkannt`() {
        assertTrue(isSonosHeadphone("Sonos Ace"))
        assertTrue(isSonosHeadphone("Meine Ace"))
        assertTrue(isSonosHeadphone("SONOS ACE 2"))
    }

    @Test
    fun `andere Bluetooth-Geraete werden ignoriert`() {
        assertFalse(isSonosHeadphone("Galaxy Watch7 (ABCD)"))
        assertFalse(isSonosHeadphone("WH-1000XM5"))
        assertFalse(isSonosHeadphone("Space Speaker"))
    }
}
