package app.trecho.run

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.trecho.run.core.RunRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Green = Color(0xFF22C55E)
private val Coral = Color(0xFFFF6B4A)
private val Indigo = Color(0xFF3B5BFF)

// ---------------------------------------------------------------- 하단 메뉴

enum class NavKind { Home, Calendar, Settings }

/**
 * 글자 없는 떠 있는 하단 메뉴. 가운데 러닝 버튼은 그라데이션 원형으로 크게,
 * 고른 탭은 아이콘 색과 아래 점으로 표시한다.
 */
@Composable
fun FancyNavBar(selected: NavKind?, onSelect: (NavKind) -> Unit, labels: Map<NavKind, String>) {
    val surface = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(bottom = 0.dp)
                .shadow(18.dp, RoundedCornerShape(32.dp), ambientColor = Indigo, spotColor = Indigo)
                .clip(RoundedCornerShape(32.dp)).background(surface),
            horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
        ) {
            // 세 메뉴 모두 같은 크기·색 규칙(고른 메뉴만 강조색 + 아래 점)
            NavSlot(NavKind.Home, selected, onSelect, labels)
            NavSlot(NavKind.Calendar, selected, onSelect, labels)
            NavSlot(NavKind.Settings, selected, onSelect, labels)
        }
    }
}

@Composable
private fun NavSlot(kind: NavKind, selected: NavKind?, onSelect: (NavKind) -> Unit, labels: Map<NavKind, String>) {
    val on = selected == kind
    val color by animateColorAsState(
        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), label = "nav")
    val dot by animateFloatAsState(if (on) 1f else 0f, label = "dot")
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(kind) }
            .semantics { contentDescription = labels[kind].orEmpty() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(28.dp).offset(y = (-3).dp)) {
            when (kind) {
                NavKind.Home -> drawHomeGlyph(color, on)
                NavKind.Settings -> drawGearGlyph(color, on)
                NavKind.Calendar -> drawCalendarGlyph(color, on)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).size((6 * dot).dp).clip(CircleShape).background(color))
    }
}

private fun DrawScope.drawHomeGlyph(c: Color, filled: Boolean) {
    val w = size.width; val h = size.height
    val roof = Path().apply {
        moveTo(w * 0.08f, h * 0.48f); lineTo(w * 0.5f, h * 0.1f); lineTo(w * 0.92f, h * 0.48f)
    }
    val body = Path().apply {
        moveTo(w * 0.2f, h * 0.42f); lineTo(w * 0.2f, h * 0.9f); lineTo(w * 0.8f, h * 0.9f); lineTo(w * 0.8f, h * 0.42f)
    }
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (filled) {
        drawPath(Path().apply {
            moveTo(w * 0.5f, h * 0.14f); lineTo(w * 0.8f, h * 0.42f); lineTo(w * 0.8f, h * 0.9f)
            lineTo(w * 0.2f, h * 0.9f); lineTo(w * 0.2f, h * 0.42f); close()
        }, c.copy(alpha = 0.22f))
    }
    drawPath(roof, c, style = stroke)
    drawPath(body, c, style = stroke)
    // 문
    drawRoundRect(c, Offset(w * 0.42f, h * 0.62f), Size(w * 0.16f, h * 0.28f), CornerRadius(w * 0.05f))
}

private fun DrawScope.drawYouGlyph(c: Color, filled: Boolean) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round)
    if (filled) drawCircle(c.copy(alpha = 0.22f), radius = w * 0.18f, center = Offset(w / 2, h * 0.3f))
    drawCircle(c, radius = w * 0.18f, center = Offset(w / 2, h * 0.3f), style = stroke)
    val body = Path().apply {
        moveTo(w * 0.12f, h * 0.92f)
        cubicTo(w * 0.14f, h * 0.6f, w * 0.86f, h * 0.6f, w * 0.88f, h * 0.92f)
    }
    if (filled) drawPath(Path().apply { addPath(body); close() }, c.copy(alpha = 0.22f))
    drawPath(body, c, style = stroke)
}

/** 달력: 둥근 사각형 + 위 고리 2개 + 날짜 점 */
private fun DrawScope.drawCalendarGlyph(c: Color, filled: Boolean) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    if (filled) drawRoundRect(c.copy(alpha = 0.22f), Offset(w * 0.1f, h * 0.18f), Size(w * 0.8f, h * 0.72f), CornerRadius(w * 0.14f))
    drawRoundRect(c, Offset(w * 0.1f, h * 0.18f), Size(w * 0.8f, h * 0.72f), CornerRadius(w * 0.14f), style = stroke)
    drawLine(c, Offset(w * 0.1f, h * 0.4f), Offset(w * 0.9f, h * 0.4f), strokeWidth = w * 0.09f)
    drawLine(c, Offset(w * 0.32f, h * 0.06f), Offset(w * 0.32f, h * 0.26f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    drawLine(c, Offset(w * 0.68f, h * 0.06f), Offset(w * 0.68f, h * 0.26f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    for (row in 0..1) for (col in 0..2) {
        drawCircle(c, radius = w * 0.055f, center = Offset(w * (0.3f + col * 0.2f), h * (0.56f + row * 0.18f)))
    }
}

/** 톱니바퀴: 이 8개 + 가운데 구멍 */
private fun DrawScope.drawGearGlyph(c: Color, filled: Boolean) {
    val w = size.width; val h = size.height
    val ctr = Offset(w / 2, h / 2)
    val outer = w * 0.46f; val inner = w * 0.34f
    val gear = Path()
    val teeth = 8
    for (i in 0 until teeth * 4) {
        val a = Math.toRadians(i * 360.0 / (teeth * 4) - 90).toFloat()
        val r = if ((i % 4) == 1 || (i % 4) == 2) outer else inner
        val p = Offset(ctr.x + r * kotlin.math.cos(a), ctr.y + r * kotlin.math.sin(a))
        if (i == 0) gear.moveTo(p.x, p.y) else gear.lineTo(p.x, p.y)
    }
    gear.close()
    if (filled) drawPath(gear, c.copy(alpha = 0.22f))
    drawPath(gear, c, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
    drawCircle(c, radius = w * 0.12f, center = ctr, style = Stroke(width = w * 0.08f))
}

/** 달리는 느낌의 재생 삼각형 + 속도선 */
private fun DrawScope.drawRunGlyph(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply {
        moveTo(w * 0.36f, h * 0.14f); lineTo(w * 0.9f, h * 0.5f); lineTo(w * 0.36f, h * 0.86f); close()
    }, c, style = Stroke(width = w * 0.09f, join = StrokeJoin.Round))
    drawPath(Path().apply {
        moveTo(w * 0.36f, h * 0.14f); lineTo(w * 0.9f, h * 0.5f); lineTo(w * 0.36f, h * 0.86f); close()
    }, c)
    val s = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
    drawLine(c, Offset(w * 0.04f, h * 0.38f), Offset(w * 0.2f, h * 0.38f), strokeWidth = s.width, cap = StrokeCap.Round)
    drawLine(c, Offset(w * 0.0f, h * 0.62f), Offset(w * 0.2f, h * 0.62f), strokeWidth = s.width, cap = StrokeCap.Round)
}

// ---------------------------------------------------------------- 워치·블루투스 상태 아이콘

/**
 * 원형 시계 아이콘. 연결 전: 테두리 없음 / 연결 중: 초록 호가 빙글빙글 / 연결됨: 초록 테두리 고정.
 */
@Composable
fun WatchIcon(link: Watch.Link, modifier: Modifier = Modifier, onClick: () -> Unit, label: String) {
    val glyph = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surface
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(
        modifier.size(40.dp).clip(CircleShape).background(bg).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp).rotate(if (link == Watch.Link.CONNECTING) angle else 0f)) {
            val sw = 3.dp.toPx()
            val inset = sw / 2
            when (link) {
                Watch.Link.CONNECTED -> drawCircle(Green, radius = size.minDimension / 2 - inset, style = Stroke(sw))
                Watch.Link.CONNECTING -> drawArc(
                    Brush.sweepGradient(listOf(Green.copy(alpha = 0f), Green)), startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw),
                    style = Stroke(sw, cap = StrokeCap.Round))
                else -> {}
            }
        }
        Canvas(Modifier.size(22.dp)) { drawWatchGlyph(glyph) }
    }
}

private fun DrawScope.drawWatchGlyph(c: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.1f
    // 줄
    drawRoundRect(c, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.22f), CornerRadius(w * 0.06f))
    drawRoundRect(c, Offset(w * 0.32f, h * 0.78f), Size(w * 0.36f, h * 0.22f), CornerRadius(w * 0.06f))
    // 둥근 화면
    drawCircle(c, radius = w * 0.34f, center = Offset(w / 2, h / 2), style = Stroke(sw))
    // 바늘
    drawLine(c, Offset(w / 2, h / 2), Offset(w / 2, h * 0.32f), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
    drawLine(c, Offset(w / 2, h / 2), Offset(w * 0.64f, h * 0.56f), strokeWidth = sw * 0.8f, cap = StrokeCap.Round)
    // 버튼
    drawRoundRect(c, Offset(w * 0.86f, h * 0.44f), Size(w * 0.1f, h * 0.12f), CornerRadius(w * 0.03f))
}

/** 블루투스 아이콘. 켜져 있고 워치 대기 중이면 파랑, 켜져만 있으면 회색, 꺼져 있으면 사선. */
@Composable
fun BluetoothIcon(btOn: Boolean, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, label: String) {
    val color = when {
        btOn && active -> Indigo
        btOn -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height
            val p = Path().apply {
                moveTo(w * 0.22f, h * 0.3f); lineTo(w * 0.74f, h * 0.7f); lineTo(w * 0.5f, h * 0.92f)
                lineTo(w * 0.5f, h * 0.08f); lineTo(w * 0.74f, h * 0.3f); lineTo(w * 0.22f, h * 0.7f)
            }
            drawPath(p, color, style = Stroke(w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (!btOn) drawLine(color, Offset(w * 0.08f, h * 0.08f), Offset(w * 0.92f, h * 0.92f), strokeWidth = w * 0.1f, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- 러닝 이름

/** 러닝 끝난 시각: 경로 마지막 점, 없으면 시작+활동 시간 */
fun endWallMs(r: RunRecord): Long = r.points.lastOrNull()?.wallMs?.takeIf { it > r.startWallMs } ?: (r.startWallMs + r.activeMs)

/** '아침 러닝 - 11월 20일(금) 09:35' (시작 시각으로 아침/점심/저녁, 끝난 시각 표시) */
fun runHeadline(l: Lang, r: RunRecord): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(r.startWallMs).atZone(zone)
    val end = Instant.ofEpochMilli(endWallMs(r)).atZone(zone)
    val part = when (start.hour) {
        in 0..10 -> l.t("아침 러닝", "Morning Run")
        in 11..14 -> l.t("점심 러닝", "Lunch Run")
        else -> l.t("저녁 러닝", "Evening Run")
    }
    val date = if (l == Lang.EN) end.format(DateTimeFormatter.ofPattern("MMM d (EEE) HH:mm", Locale.ENGLISH))
    else end.format(DateTimeFormatter.ofPattern("M월 d일(E) HH:mm", Locale.KOREAN))
    return "$part - $date"
}
