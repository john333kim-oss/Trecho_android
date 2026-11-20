package app.trecho.run

import android.os.SystemClock
import app.trecho.run.core.GeoFix
import app.trecho.run.core.RunRecord
import app.trecho.run.core.RunSnapshot
import app.trecho.run.core.RunState
import app.trecho.run.core.RunTracker
import app.trecho.run.core.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 진행 중인 러닝 1회를 앱 전체에서 공유한다. 서비스가 센서 값을 넣고, 화면은 [snapshot]과 [path]를 구독한다.
 * 모든 호출은 메인 스레드에서 한다.
 */
object RunSession {
    private var tracker: RunTracker? = null

    private val _snapshot = MutableStateFlow<RunSnapshot?>(null)
    val snapshot: StateFlow<RunSnapshot?> = _snapshot

    private val _path = MutableStateFlow<List<TrackPoint>>(emptyList())
    val path: StateFlow<List<TrackPoint>> = _path

    val isActive: Boolean
        get() = tracker?.state.let { it == RunState.RUNNING || it == RunState.PAUSED }

    private fun mono() = SystemClock.elapsedRealtime()

    fun start() {
        tracker = RunTracker().also { it.start(mono(), System.currentTimeMillis()) }
        _path.value = emptyList()
        tick()
    }

    fun pause() { tracker?.pause(mono()); tick() }
    fun resume() { tracker?.resume(mono()); tick() }

    fun onLocation(fix: GeoFix) {
        val t = tracker ?: return
        val before = t.points.size
        t.onLocation(fix, mono())
        if (t.points.size != before) _path.value = t.points.toList()
        tick()
    }

    fun onStepCounter(counter: Long) {
        tracker?.onStepCounter(counter, mono())
    }

    fun tick() {
        _snapshot.value = tracker?.snapshot(mono())
    }

    /** 끝내고 저장할 기록을 돌려준다. 50m 미만이면 null(저장하지 않음). */
    fun finish(): RunRecord? {
        val t = tracker ?: return null
        val now = mono()
        t.finish(now)
        tracker = null
        _snapshot.value = null
        _path.value = emptyList()
        if (t.distM < 50) return null
        return RunRecord(
            id = UUID.randomUUID().toString().take(12),
            startWallMs = t.startWallMs,
            distM = t.distM,
            activeMs = t.activeMs(now),
            steps = t.steps,
            strideM = t.strideM,
            points = t.points.toList(),
            splits = t.splits.toList(),
        )
    }

    fun discard() {
        tracker = null
        _snapshot.value = null
        _path.value = emptyList()
    }
}
