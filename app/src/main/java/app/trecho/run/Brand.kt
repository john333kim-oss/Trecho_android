package app.trecho.run

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 앱 이름 개인화: "Run" + 두 글자 + "Run". 기본은 RunKKRun.
 * 두 글자와 각 글자의 색은 설정에서 바꾸고, 홈 로고와 공유 이미지 로고에 함께 쓴다.
 * 휴대폰 홈 화면의 앱 아이콘 이름은 설치할 때 정해져서 바뀌지 않는다.
 */
object Brand {
    data class Look(val a: String = "K", val b: String = "K", val color: Long = DEFAULT_COLOR, val colorB: Long = DEFAULT_COLOR) {
        val letters get() = a + b
    }

    const val DEFAULT_COLOR = 0xFFE2553AL
    /** 고를 수 있는 색 (코럴·주황·노랑·초록·청록·파랑·보라·분홍) */
    val PALETTE = listOf(0xFFE2553AL, 0xFFFF8F1FL, 0xFFF2B705L, 0xFF2EB872L, 0xFF00A8C6L, 0xFF3B5BFFL, 0xFF7B5CFFL, 0xFFFF4FA3L)

    private val _look = MutableStateFlow(Look())
    val look: StateFlow<Look> = _look

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        _look.value = Look(
            p.getString("brandA", "K").orEmpty().ifBlank { "K" },
            p.getString("brandB", "K").orEmpty().ifBlank { "K" },
            p.getLong("brandColor", DEFAULT_COLOR),
            p.getLong("brandColorB", p.getLong("brandColor", DEFAULT_COLOR)),
        )
    }

    fun save(ctx: Context, look: Look) {
        val l = look.copy(a = look.a.trim().take(1).ifBlank { "K" }, b = look.b.trim().take(1).ifBlank { "K" })
        prefs(ctx).edit().putString("brandA", l.a).putString("brandB", l.b).putLong("brandColor", l.color).putLong("brandColorB", l.colorB).apply()
        _look.value = l
    }
}
