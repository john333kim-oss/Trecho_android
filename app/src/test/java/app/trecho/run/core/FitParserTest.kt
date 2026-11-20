package app.trecho.run.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitParserTest {
    /** 북쪽으로 3m/s, 1초 간격 record N개 + session 1개짜리 FIT 파일을 만든다. */
    private fun buildFit(n: Int, pauseAt: Int = -1): ByteArray {
        val body = ByteArrayOutputStream()
        fun le(size: Int, block: ByteBuffer.() -> Unit) =
            body.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(block).array())
        // 정의: local 0 = record(20): 253 ts u32, 0 lat s32, 1 lon s32, 5 dist u32, 2 alt u16
        body.write(0x40)
        le(5 + 5 * 3) {
            put(0); put(0); putShort(20); put(5)
            put(253.toByte()); put(4); put(0x86.toByte())
            put(0); put(4); put(0x85.toByte())
            put(1); put(4); put(0x85.toByte())
            put(5); put(4); put(0x86.toByte())
            put(2); put(2); put(0x84.toByte())
        }
        val t0 = 1_100_000_000L
        val semPerDeg = 2147483648.0 / 180.0
        var t = t0
        for (i in 0 until n) {
            if (i == pauseAt) t += 120   // 2분 멈춤
            val distM = i * 3.0
            val lat = 37.5 + distM / 111_195.0
            body.write(0x00)
            le(18) {
                putInt(t.toInt()); putInt((lat * semPerDeg).toInt()); putInt((127.0 * semPerDeg).toInt())
                putInt((distM * 100).toInt()); putShort(((20 + 500) * 5).toShort())
            }
            t++
        }
        // 정의: local 1 = session(18): 2 start u32, 8 timer u32, 9 dist u32
        body.write(0x41)
        le(5 + 3 * 3) {
            put(0); put(0); putShort(18); put(3)
            put(2); put(4); put(0x86.toByte()); put(8); put(4); put(0x86.toByte()); put(9); put(4); put(0x86.toByte())
        }
        body.write(0x01)
        le(12) { putInt(t0.toInt()); putInt(((n - 1) * 1000)); putInt(((n - 1) * 300)) }
        val data = body.toByteArray()
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(12); put(0x20); putShort(2132); putInt(data.size); put(".FIT".toByteArray())
        }.array()
        return header + data + byteArrayOf(0, 0)
    }

    @Test fun parsesRecordsAndSession() {
        val a = FitParser.parse(buildFit(601))
        assertEquals(601, a.samples.size)
        val s0 = a.samples.first()
        assertEquals(37.5, s0.lat!!, 1e-6)
        assertEquals(127.0, s0.lon!!, 1e-6)
        assertEquals(20.0, s0.altM!!, 0.01)
        assertEquals((1_100_000_000L + 631065600L), s0.tsS)
        assertEquals(1800.0, a.samples.last().distM!!, 0.01)
        assertNotNull(a.session)
        assertEquals(600_000L, a.session!!.timerMs)
        assertEquals(1800.0, a.session!!.distM!!, 0.01)
    }

    @Test fun trackAndSplits() {
        val (pts, splits) = FitParser.toTrack(FitParser.parse(buildFit(601)).samples)
        assertEquals(601, pts.size)
        assertEquals(600_000L, pts.last().activeMs)
        assertEquals(2, splits.size)
        assertEquals(1000.0, splits[0].distM, 0.01)
        assertEquals(333_333.0, splits[0].durationMs.toDouble(), 1500.0)
        assertEquals(800.0, splits[1].distM, 0.01)
    }

    @Test fun pauseIsExcluded() {
        val (pts, _) = FitParser.toTrack(FitParser.parse(buildFit(601, pauseAt = 300)).samples)
        // 2분 멈춤은 활동 시간에서 빠진다(멈춘 구간 이동 3m)
        assertEquals(599_000L, pts.last().activeMs)
    }
}
