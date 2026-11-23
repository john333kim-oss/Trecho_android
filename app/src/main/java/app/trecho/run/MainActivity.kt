package app.trecho.run

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.trecho.run.core.RunRecord
import app.trecho.run.core.RunState
import app.trecho.run.core.formatDuration
import app.trecho.run.core.formatKm
import app.trecho.run.core.formatPace
import app.trecho.run.core.personalBests
import app.trecho.run.ui.RouteMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// 색: 코발트(주색) + 트랙 코럴(강조). 랜딩 페이지와 같은 계열.
private val Cobalt = Color(0xFF2F45E8)
private val CobaltDark = Color(0xFF8796FF)
private val Lane = Color(0xFFE2553A)
private val LaneDark = Color(0xFFFF8A6E)

private val LightColors = lightColorScheme(
    primary = Cobalt, onPrimary = Color.White, secondary = Lane, onSecondary = Color.White,
    background = Color(0xFFF3F5F4), onBackground = Color(0xFF14201C),
    surface = Color.White, onSurface = Color(0xFF14201C), surfaceVariant = Color(0xFFE6E9FD),
    onSurfaceVariant = Color(0xFF5B6A65), outline = Color(0xFFD6DDDA),
)
private val DarkColors = darkColorScheme(
    primary = CobaltDark, onPrimary = Color(0xFF0F1513), secondary = LaneDark, onSecondary = Color(0xFF0F1513),
    background = Color(0xFF0F1513), onBackground = Color(0xFFE8EEEB),
    surface = Color(0xFF17201D), onSurface = Color(0xFFE8EEEB), surfaceVariant = Color(0xFF1F2650),
    onSurfaceVariant = Color(0xFF9AA8A3), outline = Color(0xFF2A3632),
)

enum class Lang { KO, EN }

/** 화면 언어. 설정에서 바꾸면 앱 전체가 바로 바뀐다. */
val LocalLang = staticCompositionLocalOf { Lang.KO }

fun Lang.t(ko: String, en: String): String = if (this == Lang.EN) en else ko

private fun bestLabel(l: Lang, label: String) = when (label) {
    "하프" -> l.t("하프", "Half")
    "풀" -> l.t("풀", "Full")
    else -> label
}

private enum class Tab { Home, Run, You }

private sealed interface Overlay {
    data class Detail(val id: String) : Overlay
    data object Settings : Overlay
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = RunRepository(applicationContext)
        val settings = Settings(applicationContext)
        setContent {
            var lang by remember { mutableStateOf(if (settings.language == "en") Lang.EN else Lang.KO) }
            CompositionLocalProvider(LocalLang provides lang) {
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        App(repo, settings, onLang = { lang = it; settings.language = if (it == Lang.EN) "en" else "ko" })
                    }
                }
            }
        }
    }
}

@Composable
private fun App(repo: RunRepository, settings: Settings, onLang: (Lang) -> Unit) {
    val l = LocalLang.current
    var tab by remember { mutableStateOf(Tab.Home) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var recording by remember { mutableStateOf(RunSession.isActive) }
    var runs by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var userName by remember { mutableStateOf(settings.userName) }
    LaunchedEffect(reload) { runs = repo.all() }

    // 워치 심박 방송으로 서비스가 기록을 자동 시작·저장하면 화면도 따라간다
    val liveSnap by RunSession.snapshot.collectAsState()
    LaunchedEffect(liveSnap != null) { if (liveSnap != null) recording = true }
    val autoSaved by RunSession.autoSaved.collectAsState()
    LaunchedEffect(autoSaved) {
        val id = autoSaved ?: return@LaunchedEffect
        RunSession.autoSaved.value = null
        recording = false; reload++; overlay = Overlay.Detail(id)
    }
    val watch by Watch.status.collectAsState()
    var watchSheet by remember { mutableStateOf(false) }

    // ---- Garmin(Health Connect) 연동
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val health = remember { HealthSync(ctx.applicationContext, repo) }
    val garminApi = remember { GarminApi(ctx.applicationContext, repo) }
    var garmin by remember { mutableStateOf(GarminUi(HealthSync.Status.UNAVAILABLE, settings.lastSyncMs, null, false,
        if (garminApi.hasKey) ApiState.OK else ApiState.OFF)) }
    fun syncGarmin() {
        if (garmin.syncing) return
        scope.launch {
            val st = runCatching { health.status() }.getOrDefault(HealthSync.Status.UNAVAILABLE)
            garmin = garmin.copy(status = st, api = if (garminApi.hasKey) garmin.api.takeIf { it != ApiState.OFF } ?: ApiState.OK else ApiState.OFF)
            if (st != HealthSync.Status.CONNECTED && !garminApi.hasKey) return@launch
            garmin = garmin.copy(syncing = true)
            var changed = false
            var latest = garmin.latestGarminMs
            var ok = false
            // 1) Personal API 키가 있으면 경로 포함 기록 먼저
            if (garminApi.hasKey) {
                val r = runCatching { garminApi.importRuns() }
                garmin = garmin.copy(apiError = garminApi.lastError, api = when (r.exceptionOrNull()) {
                    null -> ApiState.OK
                    is GarminApi.AuthException -> ApiState.KEY_ERROR
                    else -> ApiState.NET_ERROR
                })
                r.getOrNull()?.let { ok = true; if (it.imported > 0) changed = true; it.latestMs?.let { m -> latest = maxOf(latest ?: 0L, m) } }
            }
            // 2) Health Connect (경로 없음). API로 이미 받은 러닝은 건너뜀
            if (st == HealthSync.Status.CONNECTED) {
                runCatching { health.importRuns() }.getOrNull()?.let {
                    ok = true; if (it.imported > 0) changed = true
                    it.latestGarminMs?.let { m -> latest = maxOf(latest ?: 0L, m) }
                }
            }
            if (ok) settings.lastSyncMs = System.currentTimeMillis()
            garmin = garmin.copy(lastSyncMs = settings.lastSyncMs, latestGarminMs = latest, syncing = false)
            if (changed) reload++
        }
    }
    val hcPermLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { syncGarmin() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                syncGarmin()
                Watch.refreshBt(ctx)
                // 워치 자동 기록을 켜 두었으면 앱을 열 때마다 대기 서비스를 확인(재부팅·종료 후 복구)
                if (settings.watchAuto && settings.hasWatch && Watch.hasBlePermission(ctx) &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { RunService.send(ctx, RunService.ACTION_WATCH_ON) }
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun connectGarmin() {
        if (garmin.api == ApiState.KEY_ERROR) { overlay = Overlay.Settings; return }
        if (garmin.api != ApiState.OFF) { syncGarmin(); return }
        when (garmin.status) {
            HealthSync.Status.NO_PERMISSION -> hcPermLauncher.launch(health.permissions)
            HealthSync.Status.CONNECTED -> syncGarmin()
            else -> runCatching {
                // Health Connect가 없거나 업데이트가 필요하면 Play 스토어로
                ctx.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ---- 인스타 공유: 사진 고르기 → 기록 얹은 이미지 → 미리보기
    var shareRun by remember { mutableStateOf<RunRecord?>(null) }
    var shareFile by remember { mutableStateOf<java.io.File?>(null) }
    var shareBusy by remember { mutableStateOf(false) }
    val lang = LocalLang.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val run = shareRun
        if (uri == null || run == null) { shareRun = null; return@rememberLauncherForActivityResult }
        shareBusy = true
        scope.launch {
            shareFile = runCatching {
                withContext(Dispatchers.Default) { ShareImage.render(ctx, uri, run, lang == Lang.EN) }
            }.getOrNull()
            shareBusy = false
        }
    }
    fun shareMonth(m: YearMonth, rs: List<RunRecord>) {
        shareBusy = true
        scope.launch {
            shareFile = runCatching {
                withContext(Dispatchers.Default) { ShareImage.renderMonth(ctx, m, rs, lang == Lang.EN) }
            }.getOrNull()
            shareBusy = false
        }
    }
    fun startShare(r: RunRecord) {
        shareRun = r
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    BackHandler(enabled = !recording && (overlay != null || tab != Tab.Home)) {
        if (overlay != null) overlay = null else tab = Tab.Home
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (recording) {
            RecordScreen(repo, settings, onFinished = { id ->
                recording = false; reload++
                if (id != null) overlay = Overlay.Detail(id) else tab = Tab.Home
            })
        } else Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val o = overlay) {
                    is Overlay.Detail -> DetailScreen(o.id, repo, settings, runs, onShare = { startShare(it) }, onBack = { reload++; overlay = null })
                    Overlay.Settings -> SettingsScreen(settings, garminApi, onLang, onBack = { userName = settings.userName; overlay = null; syncGarmin() })
                    null -> when (tab) {
                        Tab.Home -> HomeScreen(runs, userName, watch, settings.watchAuto && settings.hasWatch,
                            onWatch = { watchSheet = true },
                            onShare = { startShare(it) },
                            onOpen = { overlay = Overlay.Detail(it) },
                            onGoRun = { tab = Tab.Run })
                        Tab.Run -> ReadyScreen(runs, onStart = { recording = true })
                        Tab.You -> AccountScreen(runs, userName, onOpen = { overlay = Overlay.Detail(it) }, onSettings = { overlay = Overlay.Settings },
                            onShareMonth = { m, rs -> shareMonth(m, rs) })
                    }
                }
            }
            FancyNavBar(
                selected = if (overlay != null) null else when (tab) { Tab.Home -> NavKind.Home; Tab.Run -> NavKind.Run; Tab.You -> NavKind.You },
                onSelect = { overlay = null; tab = when (it) { NavKind.Home -> Tab.Home; NavKind.Run -> Tab.Run; NavKind.You -> Tab.You } },
                labels = mapOf(NavKind.Home to l.t("홈", "Home"), NavKind.Run to l.t("러닝", "Run"), NavKind.You to l.t("계정", "Account")),
            )
        }
    }

    if (watchSheet) WatchSheet(garmin, watch, settings,
        onGarmin = { connectGarmin() },
        onSettings = { watchSheet = false; overlay = Overlay.Settings },
        onDismiss = { watchSheet = false })

    if (shareBusy) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text(lang.t("이미지 만드는 중…", "Creating image…")) })
    }
    shareFile?.let { f ->
        val img = remember(f) { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        AlertDialog(
            onDismissRequest = { shareFile = null; shareRun = null },
            title = { Text(lang.t("인스타그램에 공유", "Share to Instagram")) },
            text = { img?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) } },
            confirmButton = {
                Button(onClick = {
                    ShareImage.shareToInstagram(ctx, ShareImage.uriFor(ctx, f)); shareFile = null; shareRun = null
                }) { Text(lang.t("Instagram 게시물", "Instagram post")) }
            },
            dismissButton = {
                TextButton(onClick = { ShareImage.shareAny(ctx, ShareImage.uriFor(ctx, f)); shareFile = null; shareRun = null }) {
                    Text(lang.t("다른 앱", "Other apps"))
                }
            },
        )
    }
}

private enum class ApiState { OFF, OK, KEY_ERROR, NET_ERROR }

private data class GarminUi(val status: HealthSync.Status, val lastSyncMs: Long, val latestGarminMs: Long?, val syncing: Boolean,
                            val api: ApiState = ApiState.OFF, val apiError: String? = null)

// ---------------------------------------------------------------- 홈

@Composable
private fun HomeScreen(
    runs: List<RunRecord>, userName: String, watch: Watch.Status, watching: Boolean,
    onWatch: () -> Unit, onShare: (RunRecord) -> Unit, onOpen: (String) -> Unit, onGoRun: () -> Unit,
) {
    val l = LocalLang.current
    val bests = remember(runs) { personalBests(runs) }
    // 마지막 러닝한 날과 그 전날·전전날 기록
    val lastDay = runs.maxByOrNull { it.startWallMs }?.let { localDate(it.startWallMs) }
    val recent = if (lastDay == null) emptyList() else
        runs.filter { val d = localDate(it.startWallMs); !d.isAfter(lastDay) && !d.isBefore(lastDay.minusDays(2)) }
            .sortedByDescending { it.startWallMs }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                BluetoothIcon(watch.btOn, watching, onClick = onWatch, label = l.t("블루투스 상태", "Bluetooth status"))
                Spacer(Modifier.width(8.dp))
                WatchIcon(watch.link, onClick = onWatch, label = when (watch.link) {
                    Watch.Link.CONNECTED -> l.t("워치 연결됨", "Watch connected")
                    Watch.Link.CONNECTING -> l.t("워치 연결 중", "Watch connecting")
                    else -> l.t("워치 연결 안 됨", "Watch not connected")
                })
            }
        }
        if (recent.isNotEmpty()) {
            items(recent, key = { "recent-" + it.id }) { r -> RunCard(r, userName, onShare = { onShare(r) }) { onOpen(r.id) } }
        } else item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(l.t("아직 기록이 없습니다. 첫 러닝을 시작해 보세요.", "No runs yet. Start your first run."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onGoRun, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp)) {
                    Text(l.t("러닝 시작", "Start a run"), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (bests.isNotEmpty()) item {
            Box(Modifier.padding(horizontal = 16.dp)) {
                Panel {
                    Text(l.t("최고기록", "Best efforts"), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        bests.take(4).forEach { b -> Stat(bestLabel(l, b.label), formatDuration(b.ms)) }
                    }
                }
            }
        }
        item {
            Text(
                "Build by 김기룡",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            )
        }
    }
}

/** 워치·Garmin 연결 정보 창 (홈의 시계·블루투스 아이콘을 누르면) */
@Composable
private fun WatchSheet(garmin: GarminUi, watch: Watch.Status, settings: Settings,
                       onGarmin: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val l = LocalLang.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onSettings) { Text(l.t("워치 설정", "Watch settings")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(l.t("닫기", "Close")) } },
        title = { Text(l.t("워치 연결", "Watch")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val name = settings.watchName.ifBlank { settings.watchAddress }
                Text(
                    when {
                        !settings.hasWatch -> l.t("등록한 워치가 없습니다. 워치 설정에서 심박 센서로 등록하세요.",
                            "No watch registered. Register it as a heart-rate sensor in Watch settings.")
                        !settings.watchAuto -> l.t("$name 등록됨 · 자동 기록 꺼짐", "$name registered · auto record off")
                        else -> when (watch.link) {
                            Watch.Link.CONNECTED -> l.t("$name 연결됨", "$name connected") + (watch.hr?.let { " · ♥ $it bpm" } ?: "")
                            Watch.Link.CONNECTING -> l.t("$name 연결 중…", "Connecting to $name…")
                            else -> l.t("$name 대기 중 · 워치에서 러닝을 시작하면 연결됩니다", "Waiting for $name · starts when you start a run on the watch")
                        }
                    }, fontSize = 14.sp)
                if (!watch.btOn) Text(l.t("휴대폰 블루투스가 꺼져 있습니다.", "Phone Bluetooth is off."), color = MaterialTheme.colorScheme.secondary, fontSize = 14.sp)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                GarminBar(garmin, onGarmin)
            }
        },
    )
}

/** 홈 맨 위 Garmin 연동 상태 줄 */
@Composable
private fun GarminBar(g: GarminUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val l = LocalLang.current
    val ok = g.api == ApiState.OK || (g.api == ApiState.OFF && g.status == HealthSync.Status.CONNECTED)
    val timeFmt = DateTimeFormatter.ofPattern(if (l == Lang.EN) "MMM d, h:mm a" else "M/d HH:mm",
        if (l == Lang.EN) Locale.ENGLISH else Locale.KOREAN)
    fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
    val syncedDetail = if (g.syncing) l.t("가져오는 중…", "Syncing…") else listOfNotNull(
        g.latestGarminMs?.let { l.t("마지막 Garmin 러닝 ", "Last Garmin run ") + fmt(it) },
        if (g.lastSyncMs > 0) l.t("확인 ", "Checked ") + fmt(g.lastSyncMs) else null,
    ).joinToString(" · ")
    val (title, detail, action) = if (g.api != ApiState.OFF) when (g.api) {
        ApiState.KEY_ERROR -> Triple(l.t("Garmin API 키 확인 필요", "Check Garmin API key"),
            l.t("키가 틀렸거나 만료됐습니다: ", "Key is wrong or expired: ") + (g.apiError ?: ""), l.t("설정", "Settings"))
        ApiState.NET_ERROR -> Triple(l.t("Garmin 연결됨 (경로 포함)", "Garmin connected (with routes)"),
            l.t("가져오기 실패: ", "Sync failed: ") + (g.apiError ?: "?"), l.t("다시", "Retry"))
        else -> Triple(l.t("Garmin 연결됨 (경로 포함)", "Garmin connected (with routes)"),
            listOfNotNull(syncedDetail.ifEmpty { null }, g.apiError?.let { l.t("일부 실패: ", "Some failed: ") + it }).joinToString(" · ")
                .ifEmpty { l.t("새로고침을 눌러 가져오세요", "Tap Refresh to import") }, l.t("새로고침", "Refresh"))
    } else when (g.status) {
        HealthSync.Status.CONNECTED -> Triple(
            l.t("Garmin 연결됨", "Garmin connected"),
            if (g.syncing) l.t("가져오는 중…", "Syncing…") else listOfNotNull(
                g.latestGarminMs?.let { l.t("마지막 Garmin 러닝 ", "Last Garmin run ") + fmt(it) },
                if (g.lastSyncMs > 0) l.t("확인 ", "Checked ") + fmt(g.lastSyncMs) else null,
            ).joinToString(" · ").ifEmpty { l.t("Garmin Connect에서 Health Connect 공유를 켜 주세요", "Turn on Health Connect sharing in Garmin Connect") },
            l.t("새로고침", "Refresh"),
        )
        HealthSync.Status.NO_PERMISSION -> Triple(l.t("Garmin 연결 안 됨", "Garmin not connected"),
            l.t("Health Connect 권한을 허용하면 워치 기록을 가져옵니다", "Allow Health Connect access to import watch runs"), l.t("연결", "Connect"))
        HealthSync.Status.NEEDS_UPDATE -> Triple(l.t("Garmin 연결 안 됨", "Garmin not connected"),
            l.t("Health Connect 업데이트가 필요합니다", "Health Connect needs an update"), l.t("업데이트", "Update"))
        HealthSync.Status.UNAVAILABLE -> Triple(l.t("Garmin 연결 안 됨", "Garmin not connected"),
            l.t("Health Connect를 사용할 수 없습니다", "Health Connect is not available"), l.t("설치", "Install"))
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Color(0xFF1F8A5B) else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
        }
        Text(action, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** 러닝 카드: 머리글(아바타·이름·공유) → 지도 → 거리·페이스·시간 */
@Composable
private fun RunCard(r: RunRecord, userName: String, onShare: () -> Unit, onClick: () -> Unit) {
    val l = LocalLang.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(userName, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(runHeadline(l, r) + if (r.source != "phone") " · ${r.source}" else "",
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = l.t("인스타그램 공유", "Share to Instagram"), modifier = Modifier.size(20.dp))
            }
        }
        if (r.points.size >= 2) {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                RouteMap(r.points, follow = false, lineColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.fillMaxSize(), interactive = false)
                // 지도 위 터치는 카드 열기로 쓰고, 목록 스크롤은 막지 않는다
                Box(Modifier.fillMaxSize().clickable(onClick = onClick))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TopStat(l.t("거리", "Distance"), "${formatKm(r.distM)} km")
            TopStat(l.t("페이스", "Pace"), "${formatPace(r.avgPaceSecPerKm)} /km")
            TopStat(l.t("시간", "Time"), formatDuration(r.activeMs))
            r.avgHr?.let { TopStat(l.t("심박", "HR"), "$it") }
        }
    }
}

/** 이름이 위, 숫자가 아래 */
@Composable
private fun TopStat(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RunRow(r: RunRecord, onClick: () -> Unit) {
    val l = LocalLang.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title ?: dateLabel(l, r.startWallMs), fontWeight = FontWeight.Bold)
            Text("${formatDuration(r.activeMs)} · ${formatPace(r.avgPaceSecPerKm)}/km" +
                (if (r.source != "phone") " · ${r.source}" else "") + if (r.uploaded) l.t(" · 전송됨", " · Sent") else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Text("${formatKm(r.distM)} km", fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------- 러닝 탭 (시작 전)

@Composable
private fun ReadyScreen(runs: List<RunRecord>, onStart: () -> Unit) {
    val l = LocalLang.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(l.t("러닝", "Run"), fontSize = 34.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
            Text(l.t("밖으로 나가 GPS가 잡히면 시작하세요.", "Head outside and start once GPS is ready."),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(
            onClick = onStart,
            modifier = Modifier.size(180.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) { Text(l.t("시작", "START"), fontSize = 30.sp, fontWeight = FontWeight.Black) }
        Text(
            l.t("총 ${runs.size}회 · ${formatKm(runs.sumOf { it.distM })} km", "${runs.size} runs · ${formatKm(runs.sumOf { it.distM })} km total"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 계정

@Composable
private fun AccountScreen(runs: List<RunRecord>, userName: String, onOpen: (String) -> Unit, onSettings: () -> Unit,
                          onShareMonth: (YearMonth, List<RunRecord>) -> Unit) {
    val l = LocalLang.current
    val bests = remember(runs) { personalBests(runs) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(userName, 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(userName.ifBlank { l.t("러너", "Runner") }, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = l.t("설정", "Settings")) }
        }
        WeeklyChart(runs)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹", fontSize = 22.sp) }
                Text(monthLabel(l, month), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { picking = true }.padding(vertical = 6.dp))
                TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.now()) { Text("›", fontSize = 22.sp) }
            }
            val monthRuns = runs.filter { YearMonth.from(localDate(it.startWallMs)) == month }.sortedByDescending { it.startWallMs }
            RunCalendar(month, monthRuns)
            OutlinedButton(onClick = { onShareMonth(month, monthRuns) }, enabled = monthRuns.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(l.t("이번 달 기록 인스타그램 공유", "Share this month to Instagram"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(l.t("거리", "Distance"), "${formatKm(monthRuns.sumOf { it.distM })} km")
                Stat(l.t("시간", "Time"), formatDuration(monthRuns.sumOf { it.activeMs }))
                Stat(l.t("러닝", "Runs"), "${monthRuns.size}")
            }
            if (monthRuns.isEmpty()) {
                Text(l.t("이 달에는 기록이 없습니다.", "No runs this month."), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            monthRuns.forEach { r -> RunRow(r) { onOpen(r.id) } }
        }
        Panel {
            Text(l.t("전체 기록", "All time"), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(l.t("거리", "Distance"), "${formatKm(runs.sumOf { it.distM })} km")
                Stat(l.t("시간", "Time"), formatDuration(runs.sumOf { it.activeMs }))
                Stat(l.t("러닝", "Runs"), "${runs.size}")
            }
        }
        if (bests.isNotEmpty()) Panel {
            Text(l.t("최고기록", "Best efforts"), style = MaterialTheme.typography.titleSmall)
            bests.forEach { b ->
                Row(Modifier.fillMaxWidth()) {
                    Text(bestLabel(l, b.label), modifier = Modifier.weight(1f))
                    Text(formatDuration(b.ms), fontWeight = FontWeight.Bold)
                }
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(l.t("설정", "Settings")) }
    }
    if (picking) MonthPicker(month, firstYear = runs.minOfOrNull { localDate(it.startWallMs).year } ?: month.year,
        onPick = { month = it; picking = false }, onDismiss = { picking = false })
}

private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun monthLabel(l: Lang, m: YearMonth): String =
    if (l == Lang.EN) m.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) else "${m.year}년 ${m.monthValue}월"

/** 최근 12주 주별 누적 거리 영역 그래프. 점을 누르면 그 주의 거리·시간·상승 고도를 위에 보여준다. */
@Composable
private fun WeeklyChart(runs: List<RunRecord>) {
    val l = LocalLang.current
    val thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val weeks = (11 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
    val weekRuns = weeks.map { start ->
        runs.filter { val d = localDate(it.startWallMs); !d.isBefore(start) && d.isBefore(start.plusWeeks(1)) }
    }
    val km = weekRuns.map { rs -> rs.sumOf { it.distM } / 1000.0 }
    var sel by remember { mutableStateOf(weeks.lastIndex) }
    // 축 눈금: 20km 단위로 올림
    val top = (Math.ceil((km.maxOrNull() ?: 0.0).coerceAtLeast(1.0) / 20.0) * 20.0).coerceAtLeast(20.0)
    val line = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surface
    val marker = MaterialTheme.colorScheme.onSurface

    Panel {
        val start = weeks[sel]; val end = start.plusDays(6)
        val rangeFmt = if (l == Lang.EN) DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH) else DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
        Text("${start.format(rangeFmt)} – ${end.format(rangeFmt)}, ${end.year}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(l.t("거리", "Distance"), String.format(Locale.US, "%.2f km", km[sel]))
            Stat(l.t("시간", "Time"), formatDuration(weekRuns[sel].sumOf { it.activeMs }))
            Stat(l.t("상승 고도", "Elev gain"), "${weekRuns[sel].sumOf { it.elevationGainM }.toInt()} m")
        }
        Text(l.t("최근 12주", "Past 12 weeks"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(150.dp)) {
            Canvas(
                Modifier.weight(1f).fillMaxHeight().pointerInput(km) {
                    detectTapGestures { pos ->
                        val step = size.width / (km.size - 1).toFloat()
                        sel = ((pos.x / step) + 0.5f).toInt().coerceIn(0, km.lastIndex)
                    }
                }
            ) {
                val padY = 8.dp.toPx()
                val h = size.height - padY * 2
                val step = size.width / (km.size - 1)
                fun x(i: Int) = i * step
                fun y(v: Double) = padY + h - (v / top * h).toFloat()
                // 가로 눈금선 (0, 절반, 최대)
                listOf(0.0, top / 2, top).forEach { v ->
                    drawLine(grid, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
                }
                val path = Path().apply { km.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                val area = Path().apply {
                    addPath(path); lineTo(x(km.lastIndex), y(0.0)); lineTo(x(0), y(0.0)); close()
                }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.55f), line.copy(alpha = 0.04f)), startY = padY, endY = padY + h))
                drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                // 선택한 주 세로선
                drawLine(marker, Offset(x(sel), padY), Offset(x(sel), y(0.0)), strokeWidth = 2.dp.toPx())
                km.forEachIndexed { i, v ->
                    val c = Offset(x(i), y(v))
                    if (i == sel) {
                        drawCircle(line.copy(alpha = 0.3f), radius = 11.dp.toPx(), center = c)
                        drawCircle(line, radius = 6.dp.toPx(), center = c)
                    } else {
                        drawCircle(bg, radius = 4.5.dp.toPx(), center = c)
                        drawCircle(line, radius = 4.5.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
            Column(Modifier.width(52.dp).fillMaxHeight().padding(start = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(top, top / 2, 0.0).forEach { v ->
                    Text("${v.toInt()} km", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 52.dp)) {
            weeks.forEachIndexed { i, w ->
                // 달이 바뀌는 주에만 월 표시
                val show = i == 0 || w.monthValue != weeks[i - 1].monthValue
                Text(if (show) (if (l == Lang.EN) w.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH).uppercase() else "${w.monthValue}월") else "",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

/** 월 달력. 러닝한 날은 강조색 동그라미, 오늘은 테두리. */
@Composable
private fun RunCalendar(month: YearMonth, monthRuns: List<RunRecord>) {
    val l = LocalLang.current
    val runDays = monthRuns.map { localDate(it.startWallMs).dayOfMonth }.toSet()
    val today = LocalDate.now()
    val heads = if (l == Lang.EN) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("월", "화", "수", "목", "금", "토", "일")
    val lead = month.atDay(1).dayOfWeek.value - 1 // 월요일 시작
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            heads.forEach { Text(it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        for (row in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val day = row * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                        if (day in 1..month.lengthOfMonth()) {
                            val ran = day in runDays
                            val isToday = month.atDay(day) == today
                            Box(
                                Modifier.size(34.dp).clip(CircleShape)
                                    .background(if (ran) MaterialTheme.colorScheme.secondary else Color.Transparent)
                                    .then(if (isToday && !ran) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$day", fontSize = 14.sp,
                                    fontWeight = if (ran || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (ran) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 년·월 선택 창 */
@Composable
private fun MonthPicker(current: YearMonth, firstYear: Int, onPick: (YearMonth) -> Unit, onDismiss: () -> Unit) {
    val l = LocalLang.current
    val now = YearMonth.now()
    var year by remember { mutableStateOf(current.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(now) }) { Text(l.t("이번 달", "This month")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(l.t("닫기", "Close")) } },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }, enabled = year > minOf(firstYear, now.year)) { Text("‹", fontSize = 22.sp) }
                Text(if (l == Lang.EN) "$year" else "${year}년", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                TextButton(onClick = { year++ }, enabled = year < now.year) { Text("›", fontSize = 22.sp) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0 until 3) {
                        val m = r * 3 + c + 1
                        val ym = YearMonth.of(year, m)
                        val enabled = ym <= now
                        val selected = ym == current
                        OutlinedButton(
                            onClick = { onPick(ym) }, enabled = enabled, modifier = Modifier.weight(1f),
                            colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary) else ButtonDefaults.outlinedButtonColors(),
                        ) {
                            Text(if (l == Lang.EN) java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) else "${m}월",
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Avatar(name: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).ifBlank { "R" }, color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

// ---------------------------------------------------------------- 러닝 중

@Composable
private fun RecordScreen(repo: RunRepository, settings: Settings, onFinished: (String?) -> Unit) {
    val l = LocalLang.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by RunSession.snapshot.collectAsState()
    val path by RunSession.path.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) RunService.send(ctx, RunService.ACTION_START)
        else permissionDenied = true
    }
    LaunchedEffect(Unit) {
        if (!RunSession.isActive) {
            val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) RunService.send(ctx, RunService.ACTION_START) else launcher.launch(perms)
        }
    }

    fun finish(save: Boolean) {
        val rec = RunSession.finish()
        RunService.send(ctx, RunService.ACTION_STOP)
        if (!save || rec == null) { onFinished(null); return }
        scope.launch {
            repo.save(rec)
            if (settings.autoUpload && settings.serverBase.isNotBlank()) repo.upload(rec, settings.serverBase)
            onFinished(rec.id)
        }
    }

    if (permissionDenied) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(l.t("위치 권한이 필요합니다", "Location permission needed"), style = MaterialTheme.typography.titleLarge)
            Text(l.t("거리와 경로를 재려면 '정확한 위치' 권한을 허용해 주세요. 휴대폰 설정 > 애플리케이션 > RunKKRun > 권한에서 바꿀 수 있습니다.",
                "Allow 'Precise location' to measure distance and route. You can change it in Settings > Apps > RunKKRun > Permissions."))
            Button(onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }) { Text(l.t("설정 열기", "Open settings")) }
            OutlinedButton(onClick = { onFinished(null) }) { Text(l.t("돌아가기", "Back")) }
        }
        return
    }

    val s = snap
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(0.38f)) {
            RouteMap(path, follow = true, lineColor = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxSize())
            GpsChip(s?.gpsOk == true, Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Column(
            Modifier.fillMaxWidth().weight(0.62f).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(formatKm(s?.distM ?: 0.0), fontSize = 76.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text(l.t("킬로미터", "kilometers"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(l.t("시간", "Time"), formatDuration(s?.activeMs ?: 0), big = true)
                Stat(l.t("현재 페이스", "Current pace"), formatPace(s?.currentPaceSecPerKm), big = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(l.t("평균 페이스", "Avg pace"), formatPace(s?.avgPaceSecPerKm), big = true)
                Stat(l.t("케이던스", "Cadence"), s?.cadenceSpm?.let { "$it spm" } ?: "--", big = true)
            }
            s?.heartRate?.let { hr ->
                Text("♥ $hr bpm", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
            s?.splits?.lastOrNull()?.let {
                Text(l.t("${it.index}km 구간 ${formatPace(it.paceSecPerKm)}/km", "Km ${it.index} split ${formatPace(it.paceSecPerKm)}/km"), color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            }
            if (!confirmStop) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val paused = s?.state == RunState.PAUSED
                    Button(
                        onClick = { RunService.send(ctx, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) },
                        modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp),
                    ) { Text(if (paused) l.t("다시 시작", "Resume") else l.t("일시정지", "Pause"), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(32.dp)) { Text(l.t("끝내기", "Finish"), fontSize = 18.sp) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { finish(true) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text(l.t("저장하고 끝내기", "Save and finish"), fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmStop = false }, modifier = Modifier.weight(1f)) { Text(l.t("계속 달리기", "Keep running")) }
                        TextButton(onClick = { finish(false) }, modifier = Modifier.weight(1f)) { Text(l.t("기록 버리기", "Discard")) }
                    }
                }
            }
        }
    }
    // 서비스가 없을 때도 화면 시간은 흐르게
    LaunchedEffect(Unit) { while (true) { RunSession.tick(); delay(1000) } }
}

@Composable
private fun GpsChip(ok: Boolean, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF1F8A5B) else MaterialTheme.colorScheme.secondary))
        Spacer(Modifier.width(6.dp))
        Text(if (ok) LocalLang.current.t("GPS 양호", "GPS good") else LocalLang.current.t("GPS 찾는 중", "Finding GPS"), fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 기록 상세

@Composable
private fun DetailScreen(id: String, repo: RunRepository, settings: Settings, all: List<RunRecord>, onShare: (RunRecord) -> Unit, onBack: () -> Unit) {
    val l = LocalLang.current
    val scope = rememberCoroutineScope()
    var run by remember { mutableStateOf<RunRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { run = repo.get(id) }
    val r = run ?: return
    val bests = remember(all) { personalBests(all).filter { it.runId == r.id } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(l.t("‹ 뒤로", "‹ Back")) }
            Spacer(Modifier.weight(1f))
            Text(dateLabel(l, r.startWallMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onShare(r) }) { Icon(Icons.Filled.Share, contentDescription = l.t("공유", "Share")) }
        }
        if (r.points.isNotEmpty()) {
            RouteMap(r.points, follow = false, lineColor = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.fillMaxWidth().height(300.dp))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatKm(r.distM), fontSize = 52.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
                Text(" km", fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(l.t("시간", "Time"), formatDuration(r.activeMs))
                    Stat(l.t("평균 페이스", "Avg pace"), formatPace(r.avgPaceSecPerKm))
                    Stat(l.t("케이던스", "Cadence"), r.cadenceSpm?.let { "$it" } ?: "--")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(l.t("상승 고도", "Elev. gain"), "${r.elevationGainM.toInt()} m")
                    Stat(l.t("걸음", "Steps"), "${r.steps}")
                    Stat(l.t("보폭", "Stride"), String.format(Locale.US, "%.2f m", r.strideM))
                }
                if (r.avgHr != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(l.t("평균 심박", "Avg HR"), "${r.avgHr} bpm")
                    Stat(l.t("최대 심박", "Max HR"), "${r.maxHr} bpm")
                    Spacer(Modifier.width(1.dp))
                }
            }
            if (bests.isNotEmpty()) {
                Text(l.t("이번 러닝이 최고기록: ", "New best: ") + bests.joinToString { "${bestLabel(l, it.label)} ${formatDuration(it.ms)}" },
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
            if (r.splits.isNotEmpty()) {
                Panel {
                    Text(l.t("구간별 페이스", "Splits"), style = MaterialTheme.typography.titleSmall)
                    val fastest = r.splits.filter { it.distM >= 500 }.minOfOrNull { it.paceSecPerKm } ?: 0.0
                    val slowest = r.splits.maxOf { it.paceSecPerKm }
                    r.splits.forEach { sp ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(if (sp.distM < 1000) "${sp.index} (${sp.distM.toInt()}m)" else "${sp.index} km",
                                modifier = Modifier.width(86.dp), fontSize = 14.sp)
                            Text(formatPace(sp.paceSecPerKm), modifier = Modifier.width(56.dp), fontWeight = FontWeight.Bold)
                            val frac = if (slowest > 0) (sp.paceSecPerKm / slowest).toFloat().coerceIn(0.1f, 1f) else 0f
                            // 빠를수록 막대가 길게: 느린 구간 대비 비율을 뒤집는다
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth((1.1f - frac).coerceIn(0.08f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (sp.paceSecPerKm == fastest) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!r.uploaded) {
                Button(onClick = {
                    if (settings.serverBase.isBlank()) { message = l.t("설정에서 서버 주소를 먼저 넣어 주세요.", "Set the server address in Settings first."); return@Button }
                    message = l.t("보내는 중…", "Sending…")
                    scope.launch {
                        message = repo.upload(r, settings.serverBase).fold(
                            onSuccess = { run = r.copy(uploaded = true); l.t("서버로 보냈어요: $it", "Sent to server: $it") },
                            onFailure = { l.t("보내지 못했어요: ${it.message}", "Couldn't send: ${it.message}") })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(l.t("Claude 연결 서버로 보내기", "Send to Claude server")) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (!confirmDelete) {
                TextButton(onClick = { confirmDelete = true }) { Text(l.t("기록 삭제", "Delete activity"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { repo.delete(r.id); onBack() } },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text(l.t("정말 삭제", "Delete")) }
                    OutlinedButton(onClick = { confirmDelete = false }) { Text(l.t("취소", "Cancel")) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun SettingsScreen(settings: Settings, garminApi: GarminApi, onLang: (Lang) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val l = LocalLang.current
    var server by remember { mutableStateOf(settings.serverBase) }
    var auto by remember { mutableStateOf(settings.autoUpload) }
    var name by remember { mutableStateOf(settings.userName) }
    var gKey by remember { mutableStateOf(garminApi.apiKey) }
    fun saveAndBack() {
        settings.serverBase = server; settings.autoUpload = auto; settings.userName = name
        if (gKey.trim() != garminApi.apiKey) garminApi.apiKey = gKey
        onBack()
    }
    BackHandler { saveAndBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { saveAndBack() }) { Text(l.t("‹ 저장하고 돌아가기", "‹ Save and back")) }
        }
        Text(l.t("언어", "Language"), style = MaterialTheme.typography.titleMedium)
        listOf(Lang.KO to "한국어", Lang.EN to "English").forEach { (lang, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onLang(lang) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = l == lang, onClick = { onLang(lang) })
                Text(label, fontSize = 16.sp)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(l.t("이름", "Name"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(l.t("홈·계정 화면에 표시", "Shown on Home and Account")) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(l.t("Garmin Personal API 키 (경로 포함 가져오기)", "Garmin Personal API key (import with routes)"), style = MaterialTheme.typography.titleMedium)
        Text(l.t("본인 기록 전용 키입니다. Garmin Connect 웹 설정에서 만든 키를 붙여 넣으세요. 이 폰에만 저장되고 백업에는 올라가지 않습니다. 비워 두면 Health Connect(경로 없음)만 씁니다.",
                "Personal-use key for your own data. Paste the key created in Garmin Connect web settings. Stored only on this phone, never backed up. Leave empty to use Health Connect only (no routes)."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedTextField(value = gKey, onValueChange = { gKey = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(l.t("API 키", "API key")) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        val keyDirty = gKey.trim() != garminApi.apiKey
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { saveAndBack() }, enabled = keyDirty) { Text(l.t("저장", "Save")) }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GarminApi.KEY_PAGE))) }
            }) { Text(l.t("키 만들기 페이지", "Create key page")) }
            if (gKey.isNotEmpty()) TextButton(onClick = { gKey = "" }) { Text(l.t("지우기", "Clear")) }
        }
        if (!keyDirty && garminApi.hasKey) Text(l.t("✓ 키 저장됨", "✓ Key saved"), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        garminApi.lastError?.let { err ->
            SelectionContainer {
                Text(l.t("마지막 오류: ", "Last error: ") + err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        WatchSettings(settings)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(l.t("Claude 연결 서버", "Claude server"), style = MaterialTheme.typography.titleMedium)
        Text(l.t("PC에서 돌리는 기록 서버 주소입니다. 비밀 경로까지 넣으세요. 예: https://run.내도메인/<비밀경로>",
                "Address of your record server, including the secret path. e.g. https://run.example.com/<secret>"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedTextField(value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(l.t("서버 주소", "Server address")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.t("러닝이 끝나면 자동으로 보내기", "Send automatically after each run"), modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(l.t("배터리 설정 (중요)", "Battery (important)"), style = MaterialTheme.typography.titleMedium)
        Text(l.t("갤럭시는 배터리 절약 때문에 화면이 꺼진 앱을 멈출 수 있습니다. 러닝 중 기록이 끊기지 않게 '배터리 > 제한 없음'으로 바꿔 주세요.",
                "Galaxy phones may stop apps when the screen is off. Set 'Battery > Unrestricted' so runs keep recording."),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }) { Text(l.t("앱 설정 열기", "Open app settings")) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (!BuildConfig.HAS_MAPS_KEY) Text(l.t("지도 © OpenStreetMap 기여자", "Map © OpenStreetMap contributors"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text("RunKKRun ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 워치 심박 센서 등록

private data class FoundWatch(val address: String, val name: String, val rssi: Int)

/** 설정 > 워치: 심박 방송을 켠 워치를 찾아 등록하고, 자동 기록을 켜고 끈다. */
@Composable
private fun WatchSettings(settings: Settings) {
    val ctx = LocalContext.current
    val l = LocalLang.current
    var name by remember { mutableStateOf(settings.watchName.ifBlank { settings.watchAddress }) }
    var auto by remember { mutableStateOf(settings.watchAuto) }
    var finding by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val perms = buildList {
        addAll(Watch.blePermissions())
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.distinct().toTypedArray()
    fun granted() = Watch.hasBlePermission(ctx) &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (granted()) { denied = false; pending?.invoke() } else denied = true
        pending = null
    }
    fun withPerms(block: () -> Unit) { if (granted()) block() else { pending = block; launcher.launch(perms) } }
    fun applyAuto(on: Boolean) {
        auto = on; settings.watchAuto = on
        runCatching { RunService.send(ctx, if (on && settings.hasWatch) RunService.ACTION_WATCH_ON else RunService.ACTION_WATCH_OFF) }
    }

    Text(l.t("워치 심박 연결 (러닝 자동 기록)", "Watch heart rate (auto record)"), style = MaterialTheme.typography.titleMedium)
    Text(l.t("워치 설정에서 '활동 중 심박 방송(Broadcast Heart Rate)'을 켜 두면, 워치에서 러닝을 시작할 때 이 앱이 심박 방송을 감지해 폰 GPS 기록을 자동으로 시작하고, 워치에서 끝내면 1분 뒤 자동으로 저장합니다. 일시정지는 따라가지 않습니다.",
            "Turn on 'Broadcast Heart Rate During Activity' on the watch. When you start a run on the watch, this app detects the broadcast and starts recording with phone GPS; when you end it, the run is saved a minute later. Pauses are not followed."),
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    Text(if (name.isBlank()) l.t("등록한 워치 없음", "No watch registered") else l.t("등록됨: $name", "Registered: $name"),
        fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { withPerms { finding = true } }) { Text(l.t("워치 찾기", "Find watch")) }
        if (name.isNotBlank()) TextButton(onClick = {
            applyAuto(false); settings.watchAddress = ""; settings.watchName = ""; name = ""
        }) { Text(l.t("등록 해제", "Remove")) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(l.t("워치 러닝으로 자동 기록", "Auto record from watch runs"), modifier = Modifier.weight(1f))
        Switch(checked = auto, enabled = name.isNotBlank(), onCheckedChange = { on -> if (on) withPerms { applyAuto(true) } else applyAuto(false) })
    }
    if (denied) Text(l.t("근처 기기·위치 권한이 필요합니다. 휴대폰 설정 > 애플리케이션에서 허용해 주세요.",
        "Nearby devices and location permissions are needed. Allow them in phone Settings > Apps."), color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)

    if (finding) FindWatchDialog(onPick = { w ->
        settings.watchAddress = w.address; settings.watchName = w.name
        name = w.name.ifBlank { w.address }; finding = false
        applyAuto(true)
    }, onDismiss = { finding = false })
}

@android.annotation.SuppressLint("MissingPermission")
@Composable
private fun FindWatchDialog(onPick: (FoundWatch) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val l = LocalLang.current
    var found by remember { mutableStateOf<List<FoundWatch>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    val btOn = remember { Watch.btEnabled(ctx) }
    DisposableEffect(Unit) {
        val scanner = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val addr = result.device.address
                val nm = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull().orEmpty()
                val w = FoundWatch(addr, nm, result.rssi)
                found = (found.filter { it.address != addr } + w).sortedByDescending { it.rssi }
            }
        }
        val filters = listOf(android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(Watch.HR_SERVICE)).build())
        val settingsScan = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner?.startScan(filters, settingsScan, cb) }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val stop = Runnable { runCatching { scanner?.stopScan(cb) }; scanning = false }
        h.postDelayed(stop, 30_000)
        onDispose { h.removeCallbacks(stop); runCatching { scanner?.stopScan(cb) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(l.t("닫기", "Close")) } },
        title = { Text(l.t("워치 찾기", "Find watch")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!btOn) Text(l.t("휴대폰 블루투스를 먼저 켜 주세요.", "Turn on phone Bluetooth first."), color = MaterialTheme.colorScheme.secondary)
                Text(l.t("Garmin 워치 설정에서 심박 방송을 켜고(모델마다 다름. 예: 센서 및 액세서리 > 손목 심박 > 활동 중 방송), 워치에서 러닝을 시작하세요. 아래에 워치 이름이 나오면 누르세요.",
                        "On the Garmin watch, turn on heart-rate broadcast (varies by model, e.g. Sensors & Accessories > Wrist Heart Rate > Broadcast During Activity) and start a run. Tap your watch when it appears."),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (found.isEmpty()) Text(if (scanning) l.t("심박 방송 찾는 중…", "Searching for heart-rate broadcast…") else l.t("찾지 못했습니다. 다시 열어 보세요.", "Nothing found. Try again."))
                found.forEach { w ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(w) }
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name.ifBlank { l.t("이름 없는 기기", "Unnamed device") }, fontWeight = FontWeight.Bold)
                            Text(w.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${w.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------- 공용

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier) {
        Text("Run", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Text("KK", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary)
        Text("Run", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Stat(label: String, value: String, big: Boolean = false) {
    Column {
        Text(value, fontSize = if (big) 30.sp else 20.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DATE_FMT_KO = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)
private val DATE_FMT_EN = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.ENGLISH)
private fun dateLabel(l: Lang, ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(if (l == Lang.EN) DATE_FMT_EN else DATE_FMT_KO)
