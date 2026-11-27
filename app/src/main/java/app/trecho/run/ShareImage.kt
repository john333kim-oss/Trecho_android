package app.trecho.run

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import app.trecho.run.core.RunRecord
import app.trecho.run.core.formatDuration
import app.trecho.run.core.formatKm
import app.trecho.run.core.formatPace
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/**
 * 인스타그램 게시물용 이미지: 고른 사진(4:5로 자름) 위에 왼쪽 아래 'KK Run' 색 로고·날짜·페이스·시간·거리를 얹는다.
 */
object ShareImage {
    private const val W = 1080
    private const val H = 1350
    const val INSTAGRAM = "com.instagram.android"

    fun render(ctx: Context, photo: Uri, r: RunRecord, english: Boolean): File {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, photo)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // 너무 큰 사진은 줄여서 읽는다
            val scale = max(1, min(info.size.width / W, info.size.height / H))
            dec.setTargetSize(info.size.width / scale, info.size.height / scale)
        }
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // 사진을 4:5로 가운데 자르기
        val s = max(W.toFloat() / src.width, H.toFloat() / src.height)
        val cw = (W / s).toInt(); val ch = (H / s).toInt()
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        c.drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()

        // 글씨가 잘 보이게 아래쪽을 어둡게
        val shade = Paint().apply {
            shader = LinearGradient(0f, H * 0.38f, 0f, H.toFloat(), Color.TRANSPARENT, Color.argb(175, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, H * 0.38f, W.toFloat(), H.toFloat(), shade)

        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val black = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), Typeface.BOLD_ITALIC)
        fun paint(size: Float, tf: Typeface, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; this.alpha = alpha; textSize = size; typeface = tf
            setShadowLayer(8f, 0f, 2f, Color.argb(110, 0, 0, 0))
        }

        val left = 84f
        val label = paint(40f, Typeface.create("sans-serif-medium", Typeface.NORMAL), 230)
        val value = paint(92f, bold)

        // 아래에서 위로 배치
        var y = H - 96f
        c.drawText(formatKm(r.distM) + " km", left, y, value)
        y -= 104f
        c.drawText(if (english) "Distance" else "거리", left, y, label)
        y -= 66f
        val col2 = W * 0.47f
        c.drawText("${formatPace(r.avgPaceSecPerKm)} /km", left, y, value)
        c.drawText(formatDuration(r.activeMs), col2, y, value)
        y -= 104f
        c.drawText(if (english) "Pace" else "페이스", left, y, label)
        c.drawText(if (english) "Time" else "시간", col2, y, label)
        y -= 70f
        val date = Instant.ofEpochMilli(r.startWallMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        c.drawText(date, left, y, paint(58f, bold))
        y -= 58f + 34f

        // 날짜 위 왼쪽: 'KK Run' 로고 (KK 코럴, Run 파랑, 아래 코럴 줄)
        drawLogo(c, left, y)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "run-${r.id}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        return file
    }

    private val LOGO_RUN = Color.rgb(120, 150, 255)

    /** 왼쪽 기준선 [x], 글자 바닥 [baseY]에 'KK Run' 로고 */
    private fun drawLogo(c: Canvas, x: Float, baseY: Float, scale: Float = 1f) {
        val black = Typeface.create(Typeface.create("sans-serif-black", Typeface.NORMAL), Typeface.BOLD_ITALIC)
        fun p(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size * scale; typeface = black
            setShadowLayer(8f, 0f, 2f, Color.argb(110, 0, 0, 0))
        }
        val look = Brand.look.value
        val kk = p(96f, look.color.toInt())
        val run = p(96f, LOGO_RUN)
        val kkW = kk.measureText(look.letters)
        c.drawText(look.letters, x, baseY, kk)
        val rx = x + kkW + 18f * scale
        c.drawText("Run", rx, baseY, run)
        val runW = run.measureText("Run")
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.color.toInt() }
        c.drawRoundRect(RectF(rx, baseY + 16f * scale, rx + runW, baseY + 28f * scale), 6f * scale, 6f * scale, stripe)
    }

    /**
     * 한 달 러닝 달력 이미지(4:5). 러닝한 날은 코럴 동그라미와 거리, 위에는 달·총거리·횟수·시간.
     */
    fun renderMonth(ctx: Context, month: java.time.YearMonth, runs: List<RunRecord>, english: Boolean): File {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.rgb(20, 28, 78), Color.rgb(12, 14, 26), Shader.TileMode.CLAMP)
        })
        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fun paint(size: Float, tf: Typeface, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = tf; textAlign = align }
        val left = 84f
        drawLogo(c, left, 170f, 0.8f)
        val title = if (english) month.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH))
        else "${month.year}년 ${month.monthValue}월"
        c.drawText(title, left, 290f, paint(64f, bold))
        val total = runs.sumOf { it.distM }
        val stats = listOf(
            (if (english) "Distance" else "거리") to "${formatKm(total)} km",
            (if (english) "Runs" else "러닝") to "${runs.size}",
            (if (english) "Time" else "시간") to formatDuration(runs.sumOf { it.activeMs }),
        )
        stats.forEachIndexed { i, (k, v) ->
            val x = left + i * 310f
            c.drawText(k, x, 370f, paint(34f, medium, Color.argb(200, 255, 255, 255)))
            c.drawText(v, x, 440f, paint(58f, bold))
        }
        // 달력
        val heads = if (english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("월", "화", "수", "목", "금", "토", "일")
        val gridL = 60f; val gridW = W - 120f; val cell = gridW / 7
        val top = 540f
        heads.forEachIndexed { i, h -> c.drawText(h, gridL + cell * i + cell / 2, top, paint(32f, medium, Color.argb(170, 255, 255, 255), Paint.Align.CENTER)) }
        val zone = ZoneId.systemDefault()
        val kmByDay = runs.groupBy { Instant.ofEpochMilli(it.startWallMs).atZone(zone).toLocalDate().dayOfMonth }
            .mapValues { (_, rs) -> rs.sumOf { it.distM } }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val rowH = 118f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.look.value.color.toInt() }
        for (d in 1..month.lengthOfMonth()) {
            val idx = lead + d - 1
            val cx = gridL + cell * (idx % 7) + cell / 2
            val cy = top + 70f + rowH * (idx / 7)
            val km = kmByDay[d]
            if (km != null) {
                c.drawCircle(cx, cy, 46f, dot)
                c.drawText("$d", cx, cy + 4f, paint(34f, bold, Color.WHITE, Paint.Align.CENTER))
                c.drawText(String.format(java.util.Locale.US, "%.1f", km / 1000), cx, cy + 40f, paint(24f, bold, Color.WHITE, Paint.Align.CENTER))
            } else {
                c.drawText("$d", cx, cy + 12f, paint(34f, medium, Color.argb(150, 255, 255, 255), Paint.Align.CENTER))
            }
        }
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "month-$month.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        return file
    }

    @Suppress("unused")
    private fun drawRoute(c: Canvas, r: RunRecord, box: RectF) {
        val pts = r.points
        if (pts.size < 2) return
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        val cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = (maxLon - minLon) * cosLat; val spanY = maxLat - minLat
        val span = max(spanX, spanY).takeIf { it > 0 } ?: return
        val k = box.width() / span
        val offX = box.left + (box.width() - spanX * k).toFloat() / 2
        val offY = box.top + (box.height() - spanY * k).toFloat() / 2
        val path = Path()
        pts.forEachIndexed { i, p ->
            val x = offX + ((p.lon - minLon) * cosLat * k).toFloat()
            val y = offY + ((maxLat - p.lat) * k).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            setShadowLayer(8f, 0f, 2f, Color.argb(110, 0, 0, 0))
        })
    }

    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    /** 인스타그램 새 게시물로 공유. 인스타그램이 없으면 공유 앱 선택 창. */
    fun shareToInstagram(ctx: Context, uri: Uri) {
        ctx.grantUriPermission(INSTAGRAM, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val feed = Intent("com.instagram.share.ADD_TO_FEED").apply {
            setDataAndType(uri, "image/jpeg")
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(INSTAGRAM)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(feed)
        } catch (e: ActivityNotFoundException) {
            try {
                ctx.startActivity(sendIntent(uri).setPackage(INSTAGRAM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: ActivityNotFoundException) {
                shareAny(ctx, uri)
            }
        }
    }

    fun shareAny(ctx: Context, uri: Uri) {
        ctx.startActivity(Intent.createChooser(sendIntent(uri), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sendIntent(uri: Uri) = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
