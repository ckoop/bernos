package de.bernos.app.bluetooth

import de.bernos.app.bluetooth.LowBatteryAlert.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class LowBatteryAlertTest {

    @Test
    fun `warnt einmal beim Unterschreiten von 15 Prozent`() {
        val alert = LowBatteryAlert()
        assertEquals(Action.None, alert.update(40))
        assertEquals(Action.None, alert.update(15))
        assertEquals(Action.Warn(14), alert.update(14))
        assertEquals(Action.None, alert.update(10))
        assertEquals(Action.None, alert.update(5))
    }

    @Test
    fun `warnt sofort, wenn der erste Wert schon niedrig ist`() {
        assertEquals(Action.Warn(8), LowBatteryAlert().update(8))
    }

    @Test
    fun `kein erneutes Warnen beim Schwanken um die Schwelle`() {
        val alert = LowBatteryAlert()
        alert.update(14)
        assertEquals(Action.None, alert.update(15))
        assertEquals(Action.None, alert.update(19))
        assertEquals(Action.None, alert.update(14))
    }

    @Test
    fun `nach dem Laden auf 20 Prozent wird wieder gewarnt`() {
        val alert = LowBatteryAlert()
        alert.update(12)
        assertEquals(Action.Clear, alert.update(20))
        assertEquals(Action.None, alert.update(50))
        assertEquals(Action.Warn(13), alert.update(13))
    }

    @Test
    fun `Trennen setzt die Warnung nicht zurueck`() {
        val alert = LowBatteryAlert()
        alert.update(12)
        assertEquals(Action.None, alert.update(null))
        assertEquals(Action.None, alert.update(11))
    }
}
