package app.trecho.run

import android.content.Context
import app.trecho.run.core.RunRecord
import app.trecho.run.core.Split
import app.trecho.run.core.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /**
     * 같은 러닝이 이 앱(폰 GPS)과 워치 가져오기(Garmin·Health Connect) 양쪽에 있으면 앱 기록을 남기고
     * 가져온 쪽을 지운다(지운 목록에 남아 다시 들어오지 않음). 시간이 2분 여유로 겹치면 같은 러닝으로 본다.
     * 지운 개수를 돌려준다.
     */
    suspend fun removeImportedDuplicates(): Int {
        val runs = all()
        fun span(r: RunRecord): LongRange {
            val end = maxOf(r.startWallMs + r.activeMs, r.points.lastOrNull()?.wallMs ?: 0L)
            return (r.startWallMs - 120_000)..(end + 120_000)
        }
        val phone = runs.filter { it.source == "phone" }.map { span(it) }
        val dups = runs.filter { r ->
            r.source != "phone" && phone.any { p -> val s = span(r); s.first <= p.last && p.first <= s.last }
        }
        dups.forEach { delete(it.id) }
        return dups.size
    }

    /** 드라이브 저장 표시. 그사이 지운 기록은 되살리지 않는다 */
    suspend fun markUploaded(id: String) { get(id)?.let { save(it.copy(uploaded = true)) } }

    /** 저장돼 있거나, 사용자가 지운 기록이면 true (가져오기가 지운 기록을 되살리지 않도록) */
    fun exists(id: String) = fileOf(id).exists() || legacyOf(id).exists() || deleted().any { it.first == id }

    /** 지운 기록과 시작 시각이 2분 안으로 같은 러닝인지 (Garmin API·Health Connect 쪽 같은 러닝도 막는다) */
    fun deletedNear(startMs: Long) = deleted().any { kotlin.math.abs(it.second - startMs) < 120_000 }

    /**
     * 기록을 지우고 '지운 목록'(id, 시작 시각)에 남긴다. 이 목록이 없으면 Garmin·Health Connect
     * 동기화가 같은 러닝을 다시 가져와 지운 기록이 되살아난다.
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val start = runCatching { get(id)?.startWallMs }.getOrNull() ?: 0L
        synchronized(tombLock) { tombFile.appendText("$id,$start\n") }
        fileOf(id).delete(); legacyOf(id).delete()
    }

    private val tombFile = File(ctx.filesDir, "deleted_runs.txt")
    private fun deleted(): List<Pair<String, Long>> = synchronized(tombLock) {
        if (!tombFile.exists()) emptyList() else tombFile.readLines().mapNotNull { line ->
            val p = line.split(','); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null
        }
    }
    private companion object { val tombLock = Any() }

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
                    p.hr?.let { put(it) }
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
                    a.getLong(3), a.getLong(4), a.getDouble(5), if (a.length() > 6 && !a.isNull(6)) a.getInt(6) else null)
            },
            splits = (0 until sps.length()).map { i ->
                val a = sps.getJSONArray(i)
                Split(a.getInt(0), a.getDouble(1), a.getLong(2))
            },
        )
    }
}

/** 간단한 설정 저장. */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Google 드라이브 저장 연결 여부(동의를 마친 뒤 켜짐) */
    var driveOn: Boolean
        get() = prefs.getBoolean("driveOn", false)
        set(v) = prefs.edit().putBoolean("driveOn", v).apply()
    /** 첫 러닝 뒤 드라이브 연결 안내를 이미 보여줬는지 */
    var drivePromptDone: Boolean
        get() = prefs.getBoolean("drivePromptDone", false)
        set(v) = prefs.edit().putBoolean("drivePromptDone", v).apply()
    /** 러닝이 끝나면 드라이브에 자동 저장 */
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
    /** 심박 방송을 받을 워치(BLE 주소·이름) */
    var watchAddress: String
        get() = prefs.getString("watchAddress", "").orEmpty()
        set(v) = prefs.edit().putString("watchAddress", v).apply()
    var watchName: String
        get() = prefs.getString("watchName", "").orEmpty()
        set(v) = prefs.edit().putString("watchName", v).apply()
    /** 워치 심박 방송이 시작되면 러닝 기록 자동 시작·종료 */
    var watchAuto: Boolean
        get() = prefs.getBoolean("watchAuto", false)
        set(v) = prefs.edit().putBoolean("watchAuto", v).apply()
    val hasWatch get() = watchAddress.isNotEmpty() || watchName.isNotEmpty()

    var userName: String
        get() = prefs.getString("userName", "").orEmpty()
        set(v) = prefs.edit().putString("userName", v.trim()).apply()
}
