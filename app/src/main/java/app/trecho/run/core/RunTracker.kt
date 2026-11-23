package app.trecho.run.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** GPS 한 점 (안드로이드 Location에서 변환). */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val altM: Double?,
    val wallMs: Long,        // 실제 시각 (epoch ms)
    val accuracyM: Float,
)

/** 기록에 남는 경로 점. activeMs = 일시정지 시간을 뺀 경과 시간. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altM: Double?,
    val wallMs: Long,
    val activeMs: Long,
    val distM: Double,
    /** 심박(bpm). 워치 심박 방송을 받는 중일 때만 있다. */
    val hr: Int? = null,
)

data class Split(val index: Int, val distM: Double, val durationMs: Long) {
    /** 초/km */
    val paceSecPerKm: Double get() = if (distM > 0) durationMs / 1000.0 / (distM / 1000.0) else 0.0
}

enum class RunState { IDLE, RUNNING, PAUSED, FINISHED }

/** 화면에 보여줄 순간 상태. */
data class RunSnapshot(
    val state: RunState,
    val distM: Double,
    val activeMs: Long,
    val currentPaceSecPerKm: Double?,
    val avgPaceSecPerKm: Double?,
    val cadenceSpm: Int?,
    val steps: Int,
    val gpsOk: Boolean,
    val splits: List<Split>,
    val heartRate: Int? = null,
)

/**
 * 러닝 측정 핵심 로직. 안드로이드 의존성이 없어 JVM 단위 테스트가 가능하다.
 *
 * - GPS: 정확도가 나쁜 점(기본 25m 초과)과 순간이동(12m/s 초과)은 버린다.
 *   제자리 흔들림을 줄이려고 직전 채택 점에서 3m 이상 움직였을 때만 거리를 더한다.
 * - 걸음수: GPS가 좋을 때 보폭(거리/걸음)을 학습하고, GPS가 8초 이상 끊기면 걸음수×보폭으로 거리를 보탠다.
 * - 1km마다 구간 기록(경계 통과 시각은 선형 보간).
 */
class RunTracker(
    private val maxAccuracyM: Float = 25f,
    private val maxSpeedMps: Double = 12.0,
    private val minMoveM: Double = 3.0,
    private val gpsStaleMs: Long = 8_000,
    defaultStrideM: Double = 1.0,
    private val splitM: Double = 1000.0,
) {
    var state: RunState = RunState.IDLE
        private set

    private var accumulatedMs = 0L
    private var resumedAtMs = 0L          // 단조 시계 기준
    private var lastFix: GeoFix? = null
    private var lastGoodGpsMonoMs = Long.MIN_VALUE
    private var baseStepCounter: Long? = null
    private var lastStepCounter: Long? = null

    var distM = 0.0
        private set
    var steps = 0
        private set
    var strideM = defaultStrideM
        private set

    private var strideGpsM = 0.0
    private var strideSteps = 0

    val points = mutableListOf<TrackPoint>()
    val splits = mutableListOf<Split>()
    private var lastSplitActiveMs = 0L
    private var lastDistActiveMs = 0L      // 직전에 거리가 늘어난 시점

    // 최근 30초 창: (activeMs, distM, steps)
    private val window = ArrayDeque<Triple<Long, Double, Int>>()
    private val windowMs = 30_000L
    private val stepWindow = ArrayDeque<Pair<Long, Int>>()   // (activeMs, steps) 케이던스용

    var startWallMs = 0L
        private set

    // 최근 심박과 받은 시각(단조 시계). 10초 넘게 안 오면 없는 것으로 본다.
    private var hr: Int? = null
    private var hrMonoMs = Long.MIN_VALUE
    private val hrStaleMs = 10_000L

    fun onHeartRate(bpm: Int, monoMs: Long) {
        if (bpm !in 30..240) return
        hr = bpm; hrMonoMs = monoMs
    }

    private fun currentHr(monoMs: Long): Int? =
        hr.takeIf { hrMonoMs != Long.MIN_VALUE && monoMs - hrMonoMs <= hrStaleMs }

    private var lastMonoMs = 0L

    fun start(monoMs: Long, wallMs: Long) {
        check(state == RunState.IDLE) { "already started" }
        state = RunState.RUNNING
        resumedAtMs = monoMs
        startWallMs = wallMs
    }

    fun pause(monoMs: Long) {
        if (state != RunState.RUNNING) return
        accumulatedMs += monoMs - resumedAtMs
        state = RunState.PAUSED
        lastFix = null            // 재개 후 첫 점까지의 이동은 거리에 넣지 않는다
        window.clear()
        stepWindow.clear()
    }

    fun resume(monoMs: Long) {
        if (state != RunState.PAUSED) return
        resumedAtMs = monoMs
        state = RunState.RUNNING
    }

    fun finish(monoMs: Long) {
        if (state == RunState.RUNNING) accumulatedMs += monoMs - resumedAtMs
        if (state == RunState.RUNNING || state == RunState.PAUSED) {
            val tail = distM - splits.sumOf { it.distM }
            if (tail >= 50) splits += Split(splits.size + 1, tail, accumulatedMs - lastSplitActiveMs)
            state = RunState.FINISHED
        }
    }

    fun activeMs(monoMs: Long): Long =
        if (state == RunState.RUNNING) accumulatedMs + (monoMs - resumedAtMs) else accumulatedMs

    fun onLocation(fix: GeoFix, monoMs: Long) {
        lastMonoMs = monoMs
        if (state != RunState.RUNNING) return
        if (fix.accuracyM > maxAccuracyM) return
        val now = activeMs(monoMs)
        val prev = lastFix
        if (prev == null) {
            lastFix = fix
            lastGoodGpsMonoMs = monoMs
            addPoint(fix, now)
            return
        }
        val d = haversineM(prev.lat, prev.lon, fix.lat, fix.lon)
        val dtS = (fix.wallMs - prev.wallMs) / 1000.0
        if (dtS > 0 && d / dtS > maxSpeedMps) return   // 튐
        lastGoodGpsMonoMs = monoMs
        if (d < minMoveM) return                          // 제자리 흔들림: 기준점 유지
        addDistance(d, now)
        strideGpsM += d
        lastFix = fix
        addPoint(fix, now)
    }

    /** 안드로이드 TYPE_STEP_COUNTER 값(부팅 후 누적)을 그대로 넣는다. */
    fun onStepCounter(counter: Long, monoMs: Long) {
        val last = lastStepCounter
        lastStepCounter = counter
        if (baseStepCounter == null) baseStepCounter = counter
        if (last == null || state != RunState.RUNNING) return
        val delta = (counter - last).toInt()
        if (delta <= 0 || delta > 1000) return
        steps += delta
        val nowActive = activeMs(monoMs)
        stepWindow.addLast(nowActive to steps)
        while (stepWindow.size > 2 && nowActive - stepWindow.first().first > windowMs) stepWindow.removeFirst()
        val gpsFresh = lastGoodGpsMonoMs != Long.MIN_VALUE && monoMs - lastGoodGpsMonoMs <= gpsStaleMs
        if (gpsFresh) {
            strideSteps += delta
            if (strideSteps >= 40 && strideGpsM > 20) {
                strideM = (strideGpsM / strideSteps).coerceIn(0.4, 2.2)
            }
        } else {
            addDistance(delta * strideM, activeMs(monoMs))   // GPS 끊김: 걸음으로 보정
        }
    }

    private fun addDistance(d: Double, nowActiveMs: Long) {
        val before = distM
        distM += d
        // 1km 경계 통과 시 구간 기록 (선형 보간)
        var nextBoundary = (splits.size + 1) * splitM
        while (distM >= nextBoundary) {
            val frac = if (d > 0) (nextBoundary - before) / d else 1.0
            val crossMs = (lastDistActiveMs + (nowActiveMs - lastDistActiveMs) * frac).toLong()
            splits += Split(splits.size + 1, splitM, crossMs - lastSplitActiveMs)
            lastSplitActiveMs = crossMs
            nextBoundary = (splits.size + 1) * splitM
        }
        lastDistActiveMs = nowActiveMs
        window.addLast(Triple(nowActiveMs, distM, steps))
        while (window.size > 2 && nowActiveMs - window.first().first > windowMs) window.removeFirst()
    }

    private fun addPoint(fix: GeoFix, activeMs: Long) {
        points += TrackPoint(fix.lat, fix.lon, fix.altM, fix.wallMs, activeMs, distM, currentHr(lastMonoMs))
    }

    fun snapshot(monoMs: Long): RunSnapshot {
        val active = activeMs(monoMs)
        val avg = if (distM >= 50) active / 1000.0 / (distM / 1000.0) else null
        var cur: Double? = null
        var cadence: Int? = null
        if (state == RunState.RUNNING && window.size >= 2) {
            val a = window.first()
            val dD = distM - a.second
            val dT = active - a.first
            if (dD >= 20 && dT >= 5_000) cur = dT / 1000.0 / (dD / 1000.0)
        }
        if (state == RunState.RUNNING && stepWindow.size >= 2) {
            val (t0, s0) = stepWindow.first()
            val (t1, s1) = stepWindow.last()
            if (t1 - t0 >= 10_000 && s1 > s0 && active - t1 <= 10_000) cadence = Math.round((s1 - s0) * 60_000.0 / (t1 - t0)).toInt()
        }
        val gpsOk = lastGoodGpsMonoMs != Long.MIN_VALUE && monoMs - lastGoodGpsMonoMs <= gpsStaleMs
        return RunSnapshot(state, distM, active, cur, avg, cadence, steps, gpsOk, splits.toList(), currentHr(monoMs))
    }
}

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * asin(min(1.0, sqrt(max(0.0, h))))
}
