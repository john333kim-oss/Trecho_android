package app.trecho.run

import android.content.Context
import app.trecho.run.core.RunRecord
import app.trecho.run.core.Split
import app.trecho.run.core.TrackPoint
import app.trecho.run.core.toGpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 러닝 기록을 폰 내부 저장소(앱 전용 폴더 files/runs)에 gzip 압축 JSON으로 보관한다.
 * 이 폴더와 설정은 Android 자동 백업으로 Google 드라이브에 백업된다(앱당 25MB 한도라 압축해 둔다).
 */
class RunRepository(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "runs").apply { mkdirs() }

    private fun fileOf(id: String) = File(dir, "$id.json.gz")
    private fun legacyOf(id: String) = File(dir, "$id.json")

    private fun read(f: File): JSONObject =
        JSONObject(if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() } else f.readText())

    private fun write(r: RunRecord) {
        val tmp = File(dir, "${r.id}.json.gz.tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(toJson(r).toString()) }
        tmp.renameTo(fileOf(r.id))
        legacyOf(r.id).delete()
    }

    suspend fun all(): List<RunRecord> = withContext(Dispatchers.IO) {
        // 예전 버전의 압축 안 된 파일은 읽으면서 압축 파일로 바꾼다
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
            runCatching { write(fromJson(read(f))) }
        }
        dir.listFiles { f -> f.name.endsWith(".json.gz") }.orEmpty()
            .mapNotNull { runCatching { fromJson(read(it)) }.getOrNull() }
            .sortedByDescending { it.startWallMs }
    }

    suspend fun get(id: String): RunRecord? = withContext(Dispatchers.IO) {
        (fileOf(id).takeIf { it.exists() } ?: legacyOf(id).takeIf { it.exists() })?.let { fromJson(read(it)) }
    }

    suspend fun save(r: RunRecord) = withContext(Dispatchers.IO) { write(r) }

    fun exists(id: String) = fileOf(id).exists() || legacyOf(id).exists()

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { fileOf(id).delete(); legacyOf(id).delete() }

    /**
     * 설정한 서버(Claude 연결용 기록 서버)로 GPX를 보낸다. serverBase 예: https://run.example.com/<비밀경로>
     * 성공 시 서버의 응답 메시지를 돌려준다.
     */
    suspend fun upload(r: RunRecord, serverBase: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(serverBase.startsWith("https://")) { "서버 주소는 https:// 로 시작해야 합니다" }
            val url = URL(serverBase.trimEnd('/') + "/api/upload")
            val boundary = "----trecho" + System.currentTimeMillis()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            val gpx = toGpx(r, r.title ?: "Run").toByteArray()
            conn.outputStream.use { out ->
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"${r.id}.gpx\"\r\nContent-Type: application/gpx+xml\r\n\r\n".toByteArray())
                out.write(gpx)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            check(code in 200..299) { "서버 응답 $code" }
            val first = JSONArray(body).optJSONObject(0)
            check(first?.optBoolean("ok") == true) { first?.optString("message") ?: "알 수 없는 응답" }
            save(r.copy(uploaded = true))
            first!!.optString("message")
        }
    }

    private fun toJson(r: RunRecord) = JSONObject().apply {
        put("v", 1)
        put("id", r.id); put("startWallMs", r.startWallMs); put("distM", r.distM); put("activeMs", r.activeMs)
        put("steps", r.steps); put("strideM", r.strideM); put("uploaded", r.uploaded)
        r.title?.let { put("title", it) }
        put("source", r.source)
        put("points", JSONArray().apply {
            r.points.forEach { p ->
                put(JSONArray().apply {
                    // 위경도 소수 6자리(약 0.1m), 고도·거리 0.1m로 줄여 저장 공간을 아낀다
                    put(round(p.lat, 1e6)); put(round(p.lon, 1e6)); put(p.altM?.let { round(it, 10.0) } ?: JSONObject.NULL)
                    put(p.wallMs); put(p.activeMs); put(round(p.distM, 10.0))
                })
            }
        })
        put("splits", JSONArray().apply {
            r.splits.forEach { s -> put(JSONArray().apply { put(s.index); put(s.distM); put(s.durationMs) }) }
        })
    }

    private fun round(v: Double, f: Double) = Math.round(v * f) / f

    private fun fromJson(o: JSONObject): RunRecord {
        val pts = o.getJSONArray("points")
        val sps = o.getJSONArray("splits")
        return RunRecord(
            id = o.getString("id"),
            startWallMs = o.getLong("startWallMs"),
            distM = o.getDouble("distM"),
            activeMs = o.getLong("activeMs"),
            steps = o.optInt("steps"),
            strideM = o.optDouble("strideM", 1.0),
            uploaded = o.optBoolean("uploaded"),
            title = o.optString("title").ifEmpty { null },
            source = o.optString("source").ifEmpty { "phone" },
            points = (0 until pts.length()).map { i ->
                val a = pts.getJSONArray(i)
                TrackPoint(a.getDouble(0), a.getDouble(1), if (a.isNull(2)) null else a.getDouble(2),
                    a.getLong(3), a.getLong(4), a.getDouble(5))
            },
            splits = (0 until sps.length()).map { i ->
                val a = sps.getJSONArray(i)
                Split(a.getInt(0), a.getDouble(1), a.getLong(2))
            },
        )
    }
}

/** 간단한 설정 저장 (서버 주소). */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var serverBase: String
        get() = prefs.getString("serverBase", "").orEmpty()
        set(v) = prefs.edit().putString("serverBase", v.trim()).apply()
    var autoUpload: Boolean
        get() = prefs.getBoolean("autoUpload", true)
        set(v) = prefs.edit().putBoolean("autoUpload", v).apply()
    /** 화면 언어: "ko" 또는 "en" */
    var language: String
        get() = prefs.getString("language", "ko") ?: "ko"
        set(v) = prefs.edit().putString("language", v).apply()
    /** Health Connect(Garmin) 마지막 가져오기 시각, 0이면 없음 */
    var lastSyncMs: Long
        get() = prefs.getLong("lastSyncMs", 0L)
        set(v) = prefs.edit().putLong("lastSyncMs", v).apply()
    var userName: String
        get() = prefs.getString("userName", "").orEmpty()
        set(v) = prefs.edit().putString("userName", v.trim()).apply()
}
