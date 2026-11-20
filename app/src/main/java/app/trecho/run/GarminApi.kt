package app.trecho.run

import android.content.Context
import android.util.Base64
import app.trecho.run.core.FitParser
import app.trecho.run.core.RunRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Garmin Connect Personal API(본인 데이터 전용 공식 API)로 러닝을 GPS 경로까지 가져온다.
 * 키는 사용자가 설정 화면에서 넣고, 이 폰의 별도 설정 파일(garmin_secret.xml)에만 저장한다.
 * 이 파일은 백업 규칙에 없으므로 Google 드라이브 백업에도 올라가지 않는다.
 * 문서: https://developer.garmin.com/gc-developer-program/personal/personal-faq/
 */
class GarminApi(ctx: Context, private val repo: RunRepository) {
    private val prefs = ctx.getSharedPreferences("garmin_secret", Context.MODE_PRIVATE)

    class AuthException(msg: String) : IOException(msg)
    data class Result(val imported: Int, val latestMs: Long?)

    var apiKey: String
        get() = prefs.getString("apiKey", "").orEmpty()
        set(v) = prefs.edit().putString("apiKey", v.trim()).remove("token").remove("tokenExp").apply()
    val hasKey get() = apiKey.isNotEmpty()

    private fun http(method: String, path: String, auth: String, accept: String = "application/json"): ByteArray {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 30_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", auth)
            c.setRequestProperty("Accept", accept)
            if (method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0) }
            val code = c.responseCode
            if (code == 401 || code == 403) throw AuthException("HTTP $code")
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** 액세스 토큰(24시간). 남은 시간이 1시간 미만이면 새로 받는다. */
    private fun token(): String {
        val cached = prefs.getString("token", null)
        if (cached != null && prefs.getLong("tokenExp", 0) - System.currentTimeMillis() > 3_600_000) return cached
        val key = apiKey.ifEmpty { throw AuthException("no key") }
        val o = JSONObject(String(http("POST", TOKEN_PATH, "ApiKey $key")))
        val t = o.optString("access_token").ifEmpty { o.optString("accessToken") }.ifEmpty { throw IOException("no token") }
        val exp = jwtExpMs(t) ?: (System.currentTimeMillis() + 23 * 3_600_000L)
        prefs.edit().putString("token", t).putLong("tokenExp", exp).apply()
        return t
    }

    private fun jwtExpMs(t: String): Long? = runCatching {
        val part = t.split('.')[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))).getLong("exp") * 1000
    }.getOrNull()

    /** 최근 [days]일의 러닝 중 아직 경로가 없는 것만 가져온다. */
    suspend fun importRuns(days: Long = 30): Result = withContext(Dispatchers.IO) {
        val bearer = "Bearer " + token()
        val start = LocalDate.now().minusDays(days)
        val q = "?start=0&limit=50&activityType=running&startDate=$start&endDate=${LocalDate.now()}"
        val list = JSONArray(String(http("GET", SEARCH_PATH + q, bearer)))
        val existing = repo.all()
        var imported = 0
        var latest: Long? = null
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val type = a.optJSONObject("activityType")?.optString("typeKey").orEmpty()
            if (type.isNotEmpty() && !type.contains("running")) continue
            val actId = a.optLong("activityId").takeIf { it > 0 } ?: continue
            val listStart = parseGmt(a.optString("startTimeGMT"))
            if (listStart != null) latest = maxOf(latest ?: 0L, listStart)
            val id = "gc-$actId"
            if (repo.exists(id)) continue

            val zip = http("GET", "$DOWNLOAD_PATH$actId", bearer, "application/zip, application/x-zip-compressed, application/octet-stream")
            val fit = extractFit(zip) ?: continue
            val act = runCatching { FitParser.parse(fit) }.getOrNull() ?: continue
            val (points, splits) = FitParser.toTrack(act.samples)
            val startMs = act.session?.startMs ?: listStart ?: points.firstOrNull()?.wallMs ?: continue
            val distM = act.session?.distM ?: act.samples.mapNotNull { it.distM }.maxOrNull() ?: a.optDouble("distance", 0.0)
            val activeMs = act.session?.timerMs ?: points.lastOrNull()?.activeMs ?: (a.optDouble("duration", 0.0) * 1000).toLong()
            val steps = act.session?.steps ?: a.optInt("steps", 0)
            if (distM < 50) continue
            repo.save(RunRecord(
                id = id, startWallMs = startMs, distM = distM, activeMs = activeMs, steps = steps,
                strideM = if (steps > 0) distM / steps else 1.0,
                points = points, splits = splits,
                title = a.optString("activityName").ifEmpty { null }, source = "Garmin",
            ))
            // Health Connect로 먼저 들어온 같은 러닝(경로 없음)은 지운다
            existing.filter { it.id.startsWith("hc-") && abs(it.startWallMs - startMs) < 120_000 }.forEach { repo.delete(it.id) }
            imported++
        }
        Result(imported, latest)
    }

    private fun parseGmt(s: String): Long? = runCatching {
        LocalDateTime.parse(s.take(19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toInstant(ZoneOffset.UTC).toEpochMilli()
    }.getOrNull()

    /** 다운로드는 ZIP. 안의 .fit 파일을 꺼낸다(ZIP이 아니면 그대로 FIT로 본다). */
    private fun extractFit(bytes: ByteArray): ByteArray? {
        if (bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") return bytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.name.endsWith(".fit", ignoreCase = true)) return z.readBytes()
            }
        }
    }

    companion object {
        const val KEY_PAGE = "https://connect.garmin.com/app/settings/personalApiToken"
        private const val BASE = "https://personal.connectapi.garmin.com"
        private const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        private const val SEARCH_PATH = "/activitylist-service/activities/search/activities"
        private const val DOWNLOAD_PATH = "/download-service/files/activity/"
    }
}
