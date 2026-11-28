package app.trecho.run

/** 홈 시계 아이콘 상태. 폰은 워치와 직접 연결하지 않고 Garmin 데이터만 가져온다. */
object Watch {
    enum class Link { OFF, CONNECTING, CONNECTED }
}
