package app.trecho.run.core

import java.time.Instant
import java.util.Locale

/** 저장되는 러닝 1회. */
data class RunRecord(
    val id: String,
    val startWallMs: Long,
    val distM: Double,
    val activeMs: Long,
    val steps: Int,
    val strideM: Double,
    val points: List<TrackPoint>,
    val splits: List<Split>,
    val uploaded: Boolean = false,
    val title: String? = null,
    /** 기록 출처: "phone"(이 앱 GPS) 또는 가져온 앱 이름(예: "Garmin") */
    val source: String = "phone",
) {
    val avgPaceSecPerKm: Double? get() = if (distM >= 50) activeMs / 1000.0 / (distM / 1000.0) else null
    val cadenceSpm: Int? get() = if (activeMs >= 60_000 && steps > 0) (steps * 60_000.0 / activeMs).toInt() else null
    val elevationGainM: Double get() = elevationGain(points.mapNotNull { it.altM })
}

fun formatPace(secPerKm: Double?): String {
    if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm <= 0 || secPerKm > 3600) return "--:--"
    val s = Math.round(secPerKm).toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun formatDuration(ms: Long): String {
    val total = Math.round(ms / 1000.0).toInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatKm(distM: Double): String = String.format(Locale.US, "%.2f", distM / 1000.0)

/** 2m 히스테리시스로 GPS 고도 잡음을 줄인 누적 상승. */
fun elevationGain(alts: List<Double>): Double {
    var gain = 0.0
    var ref: Double? = null
    for (a in alts) {
        val r = ref
        if (r == null || a < r) ref = a
        else if (a - r >= 2.0) { gain += a - r; ref = a }
    }
    return gain
}

/**
 * 경로 안에서 targetM를 가장 빨리 달린 구간 시간(ms). 두 포인터 슬라이딩 윈도.
 * 점의 distM(누적 거리)과 activeMs(일시정지 제외 시간)를 쓴다.
 */
fun bestEffortMs(points: List<TrackPoint>, targetM: Double): Long? {
    if (points.size < 2 || points.last().distM < targetM) return null
    var best: Long? = null
    var j = 0
    for (i in points.indices) {
        while (j < points.size && points[j].distM - points[i].distM < targetM) j++
        if (j >= points.size) break
        val seg = points[j].distM - points[i].distM
        val t = ((points[j].activeMs - points[i].activeMs) * targetM / seg).toLong()
        if (t > 0 && (best == null || t < best)) best = t
    }
    return best
}

val BEST_EFFORTS = linkedMapOf("1K" to 1000.0, "5K" to 5000.0, "10K" to 10000.0, "하프" to 21097.5, "풀" to 42195.0)

data class BestEffort(val label: String, val ms: Long, val runId: String, val startWallMs: Long)

fun personalBests(runs: List<RunRecord>): List<BestEffort> =
    BEST_EFFORTS.mapNotNull { (label, m) ->
        runs.mapNotNull { r -> bestEffortMs(r.points, m)?.let { BestEffort(label, it, r.id, r.startWallMs) } }
            .minByOrNull { it.ms }
    }

/** 서버(Claude 연결)로 보내기 위한 GPX. */
fun toGpx(run: RunRecord, name: String = "Run"): String {
    val sb = StringBuilder()
    sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    sb.append("""<gpx version="1.1" creator="RunKKRun" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
    sb.append("<trk><name>").append(escapeXml(name)).append("</name><type>running</type><trkseg>\n")
    for (p in run.points) {
        sb.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", p.lat, p.lon))
        if (p.altM != null) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.altM))
        sb.append("<time>").append(Instant.ofEpochMilli(p.wallMs).toString()).append("</time></trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    return sb.toString()
}

private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
