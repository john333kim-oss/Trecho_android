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
 * 인스타그램 게시물용 이미지: 고른 사진(4:5로 자름) 위에 왼쪽 아래 경로 모양·날짜·페이스·시간·거리,
 * 오른쪽에 KR Run 로고를 흰 글씨로 얹는다.
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

        // 날짜 위: 경로 모양(있으면)
        val box = RectF(left, y - 150f, left + 150f, y)
        drawRoute(c, r, box)

        // 오른쪽 로고
        val logoKr = paint(96f, black)
        val logoRun = paint(60f, black)
        val right = W - 84f
        val krW = logoKr.measureText("KR")
        val runW = logoRun.measureText("Run")
        val ly = box.centerY() + 10f
        c.drawText("KR", right - krW - runW - 14f, ly, logoKr)
        c.drawText("Run", right - runW, ly, logoRun)
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 138, 110) }
        c.drawRoundRect(RectF(right - runW, ly + 16f, right, ly + 26f), 5f, 5f, stripe)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "run-${r.id}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        return file
    }

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
