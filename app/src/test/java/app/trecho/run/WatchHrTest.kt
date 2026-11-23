package app.trecho.run

import app.trecho.run.core.GeoFix
import app.trecho.run.core.RunTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchHrTest {
    @Test fun parsesHeartRateMeasurement() {
        assertEquals(72, Watch.parseHr(byteArrayOf(0x00, 72)))
        assertEquals(300, Watch.parseHr(byteArrayOf(0x01, 0x2C, 0x01)))
        assertEquals(180, Watch.parseHr(byteArrayOf(0x16, 180.toByte(), 0x10, 0x02)))
        assertNull(Watch.parseHr(byteArrayOf(0x00)))
    }

    @Test fun trackPointsCarryHeartRate() {
        val t = RunTracker()
        t.start(0, 1_000_000)
        t.onHeartRate(150, 0)
        for (i in 0..20) t.onLocation(GeoFix(37.5 + i * 3.0 / 111_195.0, 127.0, null, 1_000_000L + i * 1000, 5f), i * 1000L)
        assertEquals(150, t.points.first().hr)
        // 10초 넘게 심박이 안 오면 비운다
        assertNull(t.points.last().hr)
        assertEquals(null, t.snapshot(20_000).heartRate)
    }
}
