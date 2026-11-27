package app.trecho.run

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.trecho.run.core.RunRecord
import app.trecho.run.core.toGpx
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 러닝 기록을 사용자의 Google 드라이브 "RunKKRun" 폴더에 GPX 파일로 저장한다.
 * 권한은 drive.file(이 앱이 만든 파일만 접근)만 요청한다. 키·비밀값은 코드에 없고,
 * Google Cloud의 Android OAuth 클라이언트(패키지 + 서명 SHA-1)로 앱이 식별된다.
 */
object DriveSync {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER = "RunKKRun"
    private val lock = Mutex()

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    sealed interface Auth {
        data class Token(val value: String) : Auth
        /** 처음 한 번 계정 선택·동의 화면이 필요하다 */
        data class NeedsConsent(val intent: PendingIntent) : Auth
        data class Failed(val message: String) : Auth
    }

    suspend fun authorize(ctx: Context): Auth = runCatching {
        val r = Identity.getAuthorizationClient(ctx).authorize(request()).await()
        val pi = r.pendingIntent
        when {
            r.hasResolution() && pi != null -> Auth.NeedsConsent(pi)
            r.accessToken != null -> Auth.Token(r.accessToken!!)
            else -> Auth.Failed("no token")
        }
    }.getOrElse { Auth.Failed(it.message ?: it.javaClass.simpleName) }

    /** 동의 화면에서 돌아온 결과에서 토큰을 꺼낸다 */
    fun tokenFrom(ctx: Context, data: Intent?): Result<String> = runCatching {
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data).accessToken
            ?: error("no token")
    }

    /** 화면 없이 토큰 받기(이미 동의한 경우만). 백그라운드 자동 저장용 */
    private suspend fun silentToken(ctx: Context): String? = (authorize(ctx) as? Auth.Token)?.value

    /** 아직 드라이브에 안 올라간 기록을 모두 올린다. 올린 개수를 돌려준다. */
    suspend fun saveAll(ctx: Context, token: String? = null): Result<Int> = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val t = token ?: silentToken(ctx) ?: error("not connected")
                val repo = RunRepository(ctx)
                val pending = repo.all().filter { !it.uploaded }
                if (pending.isEmpty()) return@runCatching 0
                val folder = folderId(t)
                pending.forEach { r ->
                    upload(t, folder, r)
                    repo.markUploaded(r.id)
                }
                pending.size
            }
        }
    }

    /** 자동 저장이 켜져 있고 연결돼 있으면 남은 기록을 조용히 올린다(실패하면 다음에 다시). 올린 개수 */
    suspend fun autoSave(ctx: Context): Int {
        val st = Settings(ctx)
        return if (st.driveOn && st.autoUpload) saveAll(ctx).getOrDefault(0) else 0
    }

    /** 화면·서비스가 닫혀도 끝까지 올리도록 앱 수명의 스코프에서 실행 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun autoSaveLater(ctx: Context) { val app = ctx.applicationContext; appScope.launch { autoSave(app) } }

    private fun folderId(token: String): String {
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        val found = JSONObject(http(token, "GET", "https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id)&spaces=drive"))
            .getJSONArray("files")
        if (found.length() > 0) return found.getJSONObject(0).getString("id")
        val body = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString()
        return JSONObject(http(token, "POST", "https://www.googleapis.com/drive/v3/files?fields=id", body.toByteArray(),
            "application/json; charset=UTF-8")).getString("id")
    }

    private fun fileName(r: RunRecord): String {
        val day = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(r.startWallMs))
        val km = String.format(Locale.US, "%.2fkm", r.distM / 1000)
        return "$day $km.gpx"
    }

    private fun upload(token: String, folder: String, r: RunRecord) {
        val boundary = "runkk" + System.nanoTime()
        val meta = JSONObject().put("name", fileName(r)).put("mimeType", "application/gpx+xml")
            .put("parents", org.json.JSONArray().put(folder))
            .put("description", "RunKKRun ${r.id} · ${r.source}")
        val body = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").append(meta).append("\r\n")
            append("--$boundary\r\nContent-Type: application/gpx+xml\r\n\r\n").append(toGpx(r, r.title ?: "Run")).append("\r\n")
            append("--$boundary--\r\n")
        }.toByteArray()
        http(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", body,
            "multipart/related; boundary=$boundary")
    }

    private fun http(token: String, method: String, url: String, body: ByteArray? = null, type: String? = null): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", type) }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            check(code in 200..299) { "Drive $code ${text.take(200)}" }
            return text
        } finally { c.disconnect() }
    }
}
