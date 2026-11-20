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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.input.KeyboardType
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

private sealed interface Screen {
    data object Home : Screen
    data object Record : Screen
    data class Detail(val id: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = RunRepository(applicationContext)
        val settings = Settings(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(repo, settings)
                }
            }
        }
    }
}

@Composable
private fun App(repo: RunRepository, settings: Settings) {
    var screen by remember { mutableStateOf<Screen>(if (RunSession.isActive) Screen.Record else Screen.Home) }
    var runs by remember { mutableStateOf<List<RunRecord>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { runs = repo.all() }

    BackHandler(enabled = screen is Screen.Detail || screen is Screen.Settings) { screen = Screen.Home }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(runs,
                onStart = { screen = Screen.Record },
                onOpen = { screen = Screen.Detail(it) },
                onSettings = { screen = Screen.Settings })
            Screen.Record -> RecordScreen(repo, settings,
                onFinished = { id -> reload++; screen = if (id != null) Screen.Detail(id) else Screen.Home })
            is Screen.Detail -> DetailScreen(s.id, repo, settings, runs,
                onBack = { reload++; screen = Screen.Home })
            Screen.Settings -> SettingsScreen(settings, onBack = { screen = Screen.Home })
        }
    }
}

// ---------------------------------------------------------------- 홈

@Composable
private fun HomeScreen(runs: List<RunRecord>, onStart: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val weekRuns = runs.filter { Instant.ofEpochMilli(it.startWallMs).atZone(zone).toLocalDate() >= monday }
    val bests = remember(runs) { personalBests(runs) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
                TextButton(onClick = onSettings) { Text("설정") }
            }
        }
        item {
            Panel {
                Text("이번 주", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("거리", "${formatKm(weekRuns.sumOf { it.distM })} km")
                    Stat("시간", formatDuration(weekRuns.sumOf { it.activeMs }))
                    Stat("러닝", "${weekRuns.size}회")
                }
            }
        }
        item {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(36.dp)) {
                Text("러닝 시작", fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
        }
        if (bests.isNotEmpty()) item {
            Panel {
                Text("최고기록", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    bests.take(4).forEach { b -> Stat(b.label, formatDuration(b.ms)) }
                }
            }
        }
        item { Text("기록", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        if (runs.isEmpty()) item {
            Text("아직 기록이 없습니다. 밖에서 '러닝 시작'을 눌러 첫 기록을 남겨 보세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(runs, key = { it.id }) { r -> RunRow(r) { onOpen(r.id) } }
    }
}

@Composable
private fun RunRow(r: RunRecord, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title ?: dateLabel(r.startWallMs), fontWeight = FontWeight.Bold)
            Text("${formatDuration(r.activeMs)} · ${formatPace(r.avgPaceSecPerKm)}/km" + if (r.uploaded) " · 전송됨" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Text("${formatKm(r.distM)} km", fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

// ---------------------------------------------------------------- 러닝 중

@Composable
private fun RecordScreen(repo: RunRepository, settings: Settings, onFinished: (String?) -> Unit) {
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
            Text("위치 권한이 필요합니다", style = MaterialTheme.typography.titleLarge)
            Text("거리와 경로를 재려면 '정확한 위치' 권한을 허용해 주세요. 휴대폰 설정 > 애플리케이션 > Trecho > 권한에서 바꿀 수 있습니다.")
            Button(onClick = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }) { Text("설정 열기") }
            OutlinedButton(onClick = { onFinished(null) }) { Text("돌아가기") }
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
                Text("킬로미터", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("시간", formatDuration(s?.activeMs ?: 0), big = true)
                Stat("현재 페이스", formatPace(s?.currentPaceSecPerKm), big = true)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("평균 페이스", formatPace(s?.avgPaceSecPerKm), big = true)
                Stat("케이던스", s?.cadenceSpm?.let { "$it spm" } ?: "--", big = true)
            }
            s?.splits?.lastOrNull()?.let {
                Text("${it.index}km 구간 ${formatPace(it.paceSecPerKm)}/km", color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            }
            if (!confirmStop) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val paused = s?.state == RunState.PAUSED
                    Button(
                        onClick = { RunService.send(ctx, if (paused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE) },
                        modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp),
                    ) { Text(if (paused) "다시 시작" else "일시정지", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(32.dp)) { Text("끝내기", fontSize = 18.sp) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { finish(true) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text("저장하고 끝내기", fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmStop = false }, modifier = Modifier.weight(1f)) { Text("계속 달리기") }
                        TextButton(onClick = { finish(false) }, modifier = Modifier.weight(1f)) { Text("기록 버리기") }
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
        Text(if (ok) "GPS 양호" else "GPS 찾는 중", fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 기록 상세

@Composable
private fun DetailScreen(id: String, repo: RunRepository, settings: Settings, all: List<RunRecord>, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var run by remember { mutableStateOf<RunRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) { run = repo.get(id) }
    val r = run ?: return
    val bests = remember(all) { personalBests(all).filter { it.runId == r.id } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 목록") }
            Spacer(Modifier.weight(1f))
            Text(dateLabel(r.startWallMs), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
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
                    Stat("시간", formatDuration(r.activeMs))
                    Stat("평균 페이스", formatPace(r.avgPaceSecPerKm))
                    Stat("케이던스", r.cadenceSpm?.let { "$it" } ?: "--")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("상승 고도", "${r.elevationGainM.toInt()} m")
                    Stat("걸음", "${r.steps}")
                    Stat("보폭", String.format(Locale.US, "%.2f m", r.strideM))
                }
            }
            if (bests.isNotEmpty()) {
                Text("이번 러닝이 최고기록: " + bests.joinToString { "${it.label} ${formatDuration(it.ms)}" },
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
            if (r.splits.isNotEmpty()) {
                Panel {
                    Text("구간별 페이스", style = MaterialTheme.typography.titleSmall)
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
                    if (settings.serverBase.isBlank()) { message = "설정에서 서버 주소를 먼저 넣어 주세요."; return@Button }
                    message = "보내는 중…"
                    scope.launch {
                        message = repo.upload(r, settings.serverBase).fold(
                            onSuccess = { run = r.copy(uploaded = true); "서버로 보냈어요: $it" },
                            onFailure = { "보내지 못했어요: ${it.message}" })
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Claude 연결 서버로 보내기") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (!confirmDelete) {
                TextButton(onClick = { confirmDelete = true }) { Text("기록 삭제", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { repo.delete(r.id); onBack() } },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) { Text("정말 삭제") }
                    OutlinedButton(onClick = { confirmDelete = false }) { Text("취소") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 설정

@Composable
private fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(settings.serverBase) }
    var auto by remember { mutableStateOf(settings.autoUpload) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { settings.serverBase = server; settings.autoUpload = auto; onBack() }) { Text("‹ 저장하고 돌아가기") }
        }
        Text("Claude 연결 서버", style = MaterialTheme.typography.titleMedium)
        Text("PC에서 돌리는 기록 서버 주소입니다. 비밀 경로까지 넣으세요. 예: https://run.내도메인/<비밀경로>",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedTextField(value = server, onValueChange = { server = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("서버 주소") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("러닝이 끝나면 자동으로 보내기", modifier = Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("배터리 설정 (중요)", style = MaterialTheme.typography.titleMedium)
        Text("갤럭시는 배터리 절약 때문에 화면이 꺼진 앱을 멈출 수 있습니다. 러닝 중 기록이 끊기지 않게 '배터리 > 제한 없음'으로 바꿔 주세요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }) { Text("앱 설정 열기") }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("지도 © OpenStreetMap 기여자", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text("개발 중인 비공개 프로토타입 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 공용

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier) {
        Text("Trech", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        Text("o", fontSize = 30.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.secondary)
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

private val DATE_FMT = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREAN)
private fun dateLabel(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_FMT)
