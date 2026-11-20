package app.trecho.run.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunTrackerTest {
    private val lat0 = 37.5
    private val lon0 = 127.0
    private val mPerDegLat = 111_195.0

    /** 북쪽으로 일정 속도로 달리는 GPS 점을 1초마다 넣는다. 반환: 마지막 단조 시각 */
    private fun runNorth(t: RunTracker, startMono: Long, startWall: Long, seconds: Int, mps: Double,
                         fromM: Double = 0.0, accuracy: Float = 5f, stepsPerSec: Int = 3, stepBase: Long = 0): Long {
        var mono = startMono
        for (i in 0..seconds) {
            mono = startMono + i * 1000L
            val y = fromM + i * mps
            t.onLocation(GeoFix(lat0 + y / mPerDegLat, lon0, 20.0, startWall + i * 1000L, accuracy), mono)
            t.onStepCounter(stepBase + i * stepsPerSec.toLong(), mono)
        }
        return mono
    }

    @Test fun distanceAndPace() {
        val t = RunTracker()
        t.start(0, 1_000_000)
        val end = runNorth(t, 0, 1_000_000, 600, 3.0)       // 10분, 3m/s = 1.8km
        val s = t.snapshot(end)
        assertEquals(1800.0, s.distM, 15.0)
        assertEquals(600_000L, s.activeMs)
        assertEquals(333.3, s.avgPaceSecPerKm!!, 4.0)      // 5:33/km
        assertEquals(333.3, s.currentPaceSecPerKm!!, 4.0)
        assertEquals(180, s.cadenceSpm!!.toDouble().toInt(), )
        assertEquals(1, s.splits.size)
        assertEquals(333_333.0, s.splits[0].durationMs.toDouble(), 2_000.0)
    }

    @Test fun badAccuracyAndJumpsIgnored() {
        val t = RunTracker()
        t.start(0, 0)
        t.onLocation(GeoFix(lat0, lon0, null, 0, 5f), 0)
        t.onLocation(GeoFix(lat0 + 500 / mPerDegLat, lon0, null, 1000, 80f), 1000)   // 정확도 나쁨
        t.onLocation(GeoFix(lat0 + 500 / mPerDegLat, lon0, null, 2000, 5f), 2000)    // 2초에 500m = 튐
        assertEquals(0.0, t.distM, 0.001)
        t.onLocation(GeoFix(lat0 + 6 / mPerDegLat, lon0, null, 3000, 5f), 3000)
        assertEquals(6.0, t.distM, 0.2)
    }

    @Test fun jitterBelowThresholdAccumulates() {
        val t = RunTracker()
        t.start(0, 0)
        for (i in 0..10) t.onLocation(GeoFix(lat0 + (i % 2) * 1.5 / mPerDegLat, lon0, null, i * 1000L, 5f), i * 1000L)
        assertEquals(0.0, t.distM, 0.001)   // 제자리 흔들림 1.5m는 거리 0
    }

    @Test fun pauseExcludesTimeAndGap() {
        val t = RunTracker()
        t.start(0, 0)
        var mono = runNorth(t, 0, 0, 100, 3.0)              // 300m
        t.pause(mono)
        // 일시정지 중 이동·시간은 반영 안 됨
        t.onLocation(GeoFix(lat0 + 2000 / mPerDegLat, lon0, null, 200_000, 5f), 200_000)
        t.resume(300_000)
        mono = runNorth(t, 300_000, 300_000, 100, 3.0, fromM = 2000.0, stepBase = 1000)
        val s = t.snapshot(mono)
        assertEquals(600.0, s.distM, 10.0)
        assertEquals(200_000L, s.activeMs)
    }

    @Test fun stepsFillGpsGap() {
        val t = RunTracker()
        t.start(0, 0)
        // 200초 GPS 정상: 2.5m/s, 초당 2.5보 → 보폭 1.0m 근처 학습 (정수 걸음 위해 5보/2초)
        var mono = 0L
        for (i in 0..200) {
            mono = i * 1000L
            t.onLocation(GeoFix(lat0 + i * 2.5 / mPerDegLat, lon0, null, mono, 5f), mono)
            t.onStepCounter((i * 5 / 2).toLong(), mono)
        }
        val before = t.distM
        assertEquals(1.0, t.strideM, 0.05)
        // 60초 GPS 없음(터널), 걸음은 계속 → 약 150m 보정 (처음 8초는 GPS 유효 간주)
        for (i in 201..260) {
            mono = i * 1000L
            t.onStepCounter((i * 5 / 2).toLong(), mono)
        }
        val added = t.distM - before
        assertTrue("added=$added", added in 110.0..160.0)
        assertTrue(!t.snapshot(mono).gpsOk)
    }

    @Test fun finishAddsPartialSplit() {
        val t = RunTracker()
        t.start(0, 0)
        val end = runNorth(t, 0, 0, 500, 3.0)               // 1.5km
        t.finish(end)
        assertEquals(2, t.splits.size)
        assertEquals(500.0, t.splits[1].distM, 10.0)
        assertEquals(RunState.FINISHED, t.state)
    }

    @Test fun bestEffortsAndFormat() {
        val t = RunTracker()
        t.start(0, 0)
        var mono = runNorth(t, 0, 0, 400, 3.0)              // 1.2km @3m/s
        mono = runNorth(t, mono + 1000, mono + 1000, 400, 4.0, fromM = 1200.0, stepBase = 2000) // 1.6km @4m/s
        t.finish(mono)
        val rec = RunRecord("r1", 0, t.distM, t.activeMs(mono), t.steps, t.strideM, t.points.toList(), t.splits.toList())
        val k1 = bestEffortMs(rec.points, 1000.0)
        assertNotNull(k1)
        assertEquals(250_000.0, k1!!.toDouble(), 3_000.0)  // 4m/s → 4:10/km
        assertNull(bestEffortMs(rec.points, 5000.0))
        assertEquals("4:10", formatPace(250.0))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("--:--", formatPace(null))
        assertTrue(toGpx(rec).contains("<trkpt"))
    }

    @Test fun elevationHysteresis() {
        assertEquals(0.0, elevationGain(listOf(10.0, 11.0, 10.0, 11.5, 10.2)), 0.001)
        assertEquals(10.0, elevationGain(listOf(10.0, 13.0, 16.0, 20.0, 15.0)), 0.001)
    }
}
