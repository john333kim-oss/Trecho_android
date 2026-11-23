package app.trecho.run.core

import kotlin.math.abs

/**
 * Garmin FIT 파일에서 러닝에 필요한 것만 읽는 최소 파서.
 * record(20): 시각·위치·거리·고도, session(18): 시작 시각·총 시간·총 거리·걸음 수.
 */
object FitParser {
    private const val FIT_EPOCH_S = 631065600L      // 1989-12-31 00:00 UTC
    private const val SEMI = 180.0 / 2147483648.0   // semicircle → 도

    data class Sample(val tsS: Long, val lat: Double?, val lon: Double?, val distM: Double?, val altM: Double?, val hr: Int? = null)
    data class Session(val startMs: Long?, val timerMs: Long?, val distM: Double?, val steps: Int?)
    data class Activity(val samples: List<Sample>, val session: Session?)

    private class Field(val num: Int, val size: Int)
    private class Def(val global: Int, val bigEndian: Boolean, val fields: List<Field>, val devBytes: Int)

    fun parse(b: ByteArray): Activity {
        require(b.size >= 12) { "FIT too short" }
        val headerSize = b[0].toInt() and 0xFF
        val dataSize = u32(b, 4, false)
        require(String(b, 8, 4, Charsets.US_ASCII) == ".FIT") { "not a FIT file" }
        val end = minOf(b.size.toLong(), headerSize + dataSize).toInt()
        var p = headerSize
        val defs = HashMap<Int, Def>()
        val samples = ArrayList<Sample>()
        var session: Session? = null
        var lastTs = 0L
        while (p < end) {
            val h = b[p++].toInt() and 0xFF
            if (h and 0x80 != 0) {
                // 압축 시각 헤더(데이터 메시지)
                val local = (h shr 5) and 0x03
                val off = (h and 0x1F).toLong()
                var ts = (lastTs and 0x1F.inv().toLong()) + off
                if (off < (lastTs and 0x1F)) ts += 0x20
                lastTs = ts
                val d = defs[local] ?: error("no def $local")
                val v = readData(b, p, d); p += size(d)
                if (d.global == 20) samples += sample(v, d, ts)
                continue
            }
            val local = h and 0x0F
            if (h and 0x40 != 0) {
                val dev = h and 0x20 != 0
                val big = b[p + 1].toInt() == 1
                val global = u16(b, p + 2, big)
                val n = b[p + 4].toInt() and 0xFF
                p += 5
                val fields = (0 until n).map { i -> Field(b[p + i * 3].toInt() and 0xFF, b[p + i * 3 + 1].toInt() and 0xFF) }
                p += n * 3
                var devBytes = 0
                if (dev) {
                    val nd = b[p].toInt() and 0xFF; p++
                    for (i in 0 until nd) devBytes += b[p + i * 3 + 1].toInt() and 0xFF
                    p += nd * 3
                }
                defs[local] = Def(global, big, fields, devBytes)
            } else {
                val d = defs[local] ?: error("no def $local")
                val v = readData(b, p, d); p += size(d)
                v[253]?.let { if (it != 0xFFFFFFFFL) lastTs = it }
                when (d.global) {
                    20 -> samples += sample(v, d, lastTs)
                    18 -> session = Session(
                        startMs = v[2]?.takeIf { it != 0xFFFFFFFFL }?.let { (it + FIT_EPOCH_S) * 1000 },
                        timerMs = v[8]?.takeIf { it != 0xFFFFFFFFL },
                        distM = v[9]?.takeIf { it != 0xFFFFFFFFL }?.let { it / 100.0 },
                        // total_strides(=걸음/2) 필드 10, 러닝은 한 걸음 주기 = 2걸음
                        steps = v[10]?.takeIf { it != 0xFFFFFFFFL }?.let { (it * 2).toInt() },
                    )
                }
            }
        }
        return Activity(samples, session)
    }

    private fun size(d: Def) = d.fields.sumOf { it.size } + d.devBytes

    /** 필드 번호 → 부호 없는 값(4바이트 이하만). 큰 필드는 건너뛴다. */
    private fun readData(b: ByteArray, start: Int, d: Def): Map<Int, Long> {
        val m = HashMap<Int, Long>()
        var p = start
        for (f in d.fields) {
            when (f.size) {
                1 -> m[f.num] = (b[p].toLong() and 0xFF)
                2 -> m[f.num] = u16(b, p, d.bigEndian).toLong()
                4 -> m[f.num] = u32(b, p, d.bigEndian)
            }
            p += f.size
        }
        return m
    }

    private fun sample(v: Map<Int, Long>, d: Def, ts: Long): Sample {
        fun s32(x: Long?) = x?.takeIf { it != 0x7FFFFFFFL && it != 0xFFFFFFFFL }?.toInt()
        val lat = s32(v[0])?.let { it * SEMI }
        val lon = s32(v[1])?.let { it * SEMI }
        val dist = v[5]?.takeIf { it != 0xFFFFFFFFL }?.let { it / 100.0 }
        val alt = v[78]?.takeIf { it != 0xFFFFFFFFL }?.let { it / 5.0 - 500 }
            ?: v[2]?.takeIf { it != 0xFFFFL }?.let { it / 5.0 - 500 }
        val hr = v[3]?.takeIf { it in 1..254 }?.toInt()
        return Sample(ts + FIT_EPOCH_S, lat, lon, dist, alt, hr)
    }

    private fun u16(b: ByteArray, p: Int, big: Boolean): Int {
        val a = b[p].toInt() and 0xFF; val c = b[p + 1].toInt() and 0xFF
        return if (big) (a shl 8) or c else (c shl 8) or a
    }

    private fun u32(b: ByteArray, p: Int, big: Boolean): Long {
        var x = 0L
        for (i in 0 until 4) {
            val byte = b[p + if (big) i else 3 - i].toLong() and 0xFF
            x = (x shl 8) or byte
        }
        return x
    }

    /**
     * FIT 샘플 → 앱 기록용 경로 점과 1km 구간.
     * 일시정지는 30초 넘게 비고 거의 움직이지 않은 구간으로 추정해 활동 시간에서 뺀다.
     */
    fun toTrack(samples: List<Sample>): Pair<List<TrackPoint>, List<Split>> {
        val withDist = samples.filter { it.distM != null }.sortedBy { it.tsS }
        if (withDist.isEmpty()) return emptyList<TrackPoint>() to emptyList()
        val points = ArrayList<TrackPoint>()
        val splits = ArrayList<Split>()
        var activeMs = 0L
        var prev: Sample? = null
        var nextKm = 1000.0
        var lastSplitMs = 0L
        var lastSplitDist = 0.0
        for (s in withDist) {
            val pv = prev
            if (pv != null) {
                val dt = s.tsS - pv.tsS
                val dd = s.distM!! - pv.distM!!
                if (dt in 1..30 || (dt > 30 && dd / dt > 0.5)) activeMs += dt * 1000
                // 1km 지점을 지나면 선형 보간해서 구간 시간 기록
                while (s.distM >= nextKm && dd > 0) {
                    val frac = (nextKm - pv.distM) / dd
                    val crossMs = activeMs - ((1 - frac) * (if (dt in 1..30 || (dt > 30 && dd / dt > 0.5)) dt * 1000 else 0)).toLong()
                    splits += Split(splits.size + 1, nextKm - lastSplitDist, crossMs - lastSplitMs)
                    lastSplitMs = crossMs; lastSplitDist = nextKm; nextKm += 1000.0
                }
            }
            if (s.lat != null && s.lon != null && !(abs(s.lat) < 1e-9 && abs(s.lon) < 1e-9)) {
                points += TrackPoint(s.lat, s.lon, s.altM, s.tsS * 1000, activeMs, s.distM!!, s.hr)
            }
            prev = s
        }
        val tail = withDist.last().distM!! - lastSplitDist
        if (tail >= 50) splits += Split(splits.size + 1, tail, activeMs - lastSplitMs)
        return points to splits
    }
}
