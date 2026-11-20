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

/** 러닝 기록을 폰 내부 저장소(앱 전용 폴더)에 JSON 파일로 보관한다. */
class RunRepository(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "runs").apply { mkdirs() }

    suspend fun all(): List<RunRecord> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startWallMs }
    }

    suspend fun get(id: String): RunRecord? = withContext(Dispatchers.IO) {
        File(dir, "$id.json").takeIf { it.exists() }?.let { fromJson(JSONObject(it.readText())) }
    }

    suspend fun save(r: RunRecord) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "${r.id}.json.tmp")
        tmp.writeText(toJson(r).toString())
        tmp.renameTo(File(dir, "${r.id}.json"))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { File(dir, "$id.json").delete() }

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
        put("points", JSONArray().apply {
            r.points.forEach { p ->
                put(JSONArray().apply {
                    put(p.lat); put(p.lon); put(p.altM ?: JSONObject.NULL); put(p.wallMs); put(p.activeMs); put(p.distM)
                })
            }
        })
        put("splits", JSONArray().apply {
            r.splits.forEach { s -> put(JSONArray().apply { put(s.index); put(s.distM); put(s.durationMs) }) }
        })
    }

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
    var userName: String
        get() = prefs.getString("userName", "").orEmpty()
        set(v) = prefs.edit().putString("userName", v.trim()).apply()
}
