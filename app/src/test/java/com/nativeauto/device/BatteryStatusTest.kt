package com.nativeauto.device

import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryStatusTest {

    @Test fun percentFromLevelAndScale() {
        assertEquals(BatteryStatus(82, false), batteryStatusOf(82, 100, BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
        assertEquals(BatteryStatus(50, false), batteryStatusOf(128, 256, BatteryManager.BATTERY_STATUS_DISCHARGING, 0))
    }

    @Test fun chargingFromStatusOrPlug() {
        assertEquals(true, batteryStatusOf(40, 100, BatteryManager.BATTERY_STATUS_CHARGING, 0)?.charging)
        assertEquals(true, batteryStatusOf(100, 100, BatteryManager.BATTERY_STATUS_FULL, 0)?.charging)
        // Plugged in but not charging (e.g. a weak bike USB port): still shown as on power.
        assertEquals(true, batteryStatusOf(60, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_USB)?.charging)
    }

    @Test fun unusableBroadcastsAreDropped() {
        assertNull(batteryStatusOf(-1, 100, 0, 0))
        assertNull(batteryStatusOf(50, 0, 0, 0))
    }

    @Test fun clampedToARealPercentage() = assertEquals(100, batteryStatusOf(120, 100, 0, 0)?.percent)
}
