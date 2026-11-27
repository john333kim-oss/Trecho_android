package app.trecho.run

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.trecho.run.core.GeoFix
import app.trecho.run.core.RunState
import app.trecho.run.core.formatDuration
import app.trecho.run.core.formatKm
import app.trecho.run.core.formatPace
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 러닝 중 화면이 꺼져도 GPS·걸음 센서를 계속 받는 포그라운드 서비스.
 * 알림에 거리·시간·평균 페이스를 1초마다 갱신한다.
 *
 * 워치 대기 모드(ACTION_WATCH_ON): 등록한 워치의 BLE 심박 방송을 기다리다가
 * 방송이 잡히면 기록을 자동 시작하고, 방송이 [AUTO_STOP_MS] 동안 끊기면 마지막 심박 시각에서 끝내고 저장한다.
 */
class RunService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_WATCH_ON = "watch_on"
        const val ACTION_WATCH_OFF = "watch_off"
        private const val AUTO_STOP_MS = 60_000L
        private const val TAG = "RunService"
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RunService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_WATCH_ON) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false

    // 워치 대기
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false
    private var hrLink: HrLink? = null
    private var lastHrMono = 0L          // 마지막 심박을 받은 시각
    private var linkLostMono = 0L        // 자동 기록 중 방송이 끊긴 시각(0이면 연결 중)
    private var rearmed = true           // 방송이 한 번 끊겼다가 다시 와야 다음 자동 시작

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) {
                RunSession.onLocation(
                    GeoFix(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null, l.time,
                        if (l.hasAccuracy()) l.accuracy else 99f)
                )
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            RunSession.tick()
            checkAutoStop()
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) return START_NOT_STICKY
                if (!RunSession.isActive) { RunSession.start(); rearmed = false }
                startListening()
            }
            ACTION_PAUSE -> { RunSession.pause(); updateNotification() }
            ACTION_RESUME -> { RunSession.resume(); updateNotification() }
            ACTION_STOP -> {
                stopListening()
                rearmed = false   // 워치가 아직 방송 중이면 바로 다시 시작하지 않게
                if (watching) updateNotification(force = true) else shutdown()
            }
            ACTION_WATCH_ON -> {
                if (!goForeground()) return START_NOT_STICKY
                startWatching()
            }
            ACTION_WATCH_OFF -> {
                stopWatching()
                if (!RunSession.isActive) shutdown()
            }
            null -> {
                // 시스템이 서비스를 다시 띄움: 워치 대기를 켜 두었으면 이어서, 아니면 정리
                val st = Settings(this)
                if (st.watchAuto && st.hasWatch && goForeground()) startWatching()
                else if (!RunSession.isActive) stopSelf()
            }
        }
        return if (watching) START_STICKY else START_NOT_STICKY
    }

    /** 위치+연결 기기 유형으로 포그라운드 시작. 백그라운드라 위치 유형이 막히면 연결 기기 유형만. */
    private fun goForeground(): Boolean {
        ensureChannel()
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val dev = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val canDev = Watch.hasBlePermission(this)
        for (type in listOfNotNull(if (canDev) loc or dev else null, loc, if (canDev) dev else null)) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type=$type failed: ${e.message}")
            }
        }
        stopSelf()
        return false
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------- 워치 대기

    private fun startWatching() {
        val st = Settings(this)
        if (!st.hasWatch) { stopWatching(); return }
        watching = true
        val link = hrLink ?: HrLink(this, onHr = ::onWatchHr, onLink = ::onWatchLink, onBattery = { Watch.saveBattery(this, it) }).also { hrLink = it }
        link.start(st.watchAddress, st.watchName)
        link.boost()
        Watch.set(Watch.status.value.copy(btOn = Watch.btEnabled(this)))
        updateNotification(force = true)
    }

    private fun stopWatching() {
        watching = false
        hrLink?.stop()
        hrLink = null
        Watch.set(Watch.status.value.copy(link = Watch.Link.OFF, hr = null, btOn = Watch.btEnabled(this)))
    }

    private fun onWatchLink(link: Watch.Link) {
        val hr = if (link == Watch.Link.CONNECTED) Watch.status.value.hr else null
        Watch.set(Watch.status.value.copy(link = link, hr = hr, btOn = Watch.btEnabled(this)))
        if (link == Watch.Link.WAITING || link == Watch.Link.OFF) rearmed = true
        if (link == Watch.Link.CONNECTED) linkLostMono = 0L
        else if (RunSession.isActive && RunSession.startedByWatch && linkLostMono == 0L) linkLostMono = SystemClock.elapsedRealtime()
        updateNotification(force = true)
    }

    private fun onWatchHr(bpm: Int) {
        lastHrMono = SystemClock.elapsedRealtime()
        linkLostMono = 0L
        Watch.set(Watch.status.value.copy(link = Watch.Link.CONNECTED, hr = bpm))
        if (!RunSession.isActive && watching && rearmed && Settings(this).watchAuto) {
            rearmed = false
            // 워치에서 러닝 시작 → 폰 GPS 기록 자동 시작
            if (!goForeground()) return
            RunSession.start(byWatch = true)
            startListening()
        }
        RunSession.onHeartRate(bpm)
    }

    /** 자동 시작한 기록인데 방송이 끊긴 지 1분이 지나면 마지막 심박 시각에서 끝내고 저장 */
    private fun checkAutoStop() {
        if (!RunSession.isActive || !RunSession.startedByWatch) return
        val now = SystemClock.elapsedRealtime()
        // 연결이 끊겼거나, 연결은 남았는데 심박이 15초 넘게 안 오면 방송 종료로 본다
        val since = when {
            linkLostMono != 0L -> linkLostMono
            lastHrMono > 0 && now - lastHrMono > 15_000 -> lastHrMono
            else -> return
        }
        if (now - since < AUTO_STOP_MS) return
        linkLostMono = 0L
        val rec = RunSession.finish(endMono = lastHrMono.takeIf { it > 0 })
        stopListening()
        if (rec == null) { updateNotification(force = true); return }
        scope.launch {
            val repo = RunRepository(applicationContext)
            repo.save(rec)
            DriveSync.autoSaveLater(applicationContext)
            RunSession.autoSaved.value = rec.id
            showSaved(rec)
        }
        if (!watching) shutdown() else updateNotification(force = true)
    }

    private fun showSaved(rec: app.trecho.run.core.RunRecord) {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(getString(R.string.notif_saved))
            .setContentText("${formatKm(rec.distM)} km · ${formatDuration(rec.activeMs)} · ${formatPace(rec.avgPaceSecPerKm)}/km")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n) }
    }

    @SuppressLint("MissingPermission") // 권한은 시작 전에 화면에서 받는다
    private fun startListening() {
        if (listening) return
        listening = true
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(true)
            .build()
        fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trecho:run").apply { acquire(6 * 60 * 60 * 1000L) }
        handler.post(ticker)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        fused.removeLocationUpdates(locationCallback)
        sensors.unregisterListener(this)
        handler.removeCallbacks(ticker)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopListening()
        stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) RunSession.onStepCounter(event.values[0].toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val s = RunSession.snapshot.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val w = Watch.status.value
        val title: String
        val text: String
        if (s == null) {
            title = getString(R.string.notif_watch_waiting)
            text = when (w.link) {
                Watch.Link.CONNECTED -> getString(R.string.notif_watch_connected) + (w.hr?.let { " · $it bpm" } ?: "")
                Watch.Link.CONNECTING -> getString(R.string.notif_watch_connecting)
                else -> getString(R.string.notif_watch_hint)
            }
        } else {
            title = when (s.state) {
                RunState.PAUSED -> getString(R.string.notif_paused)
                else -> getString(R.string.notif_running)
            }
            text = "${formatKm(s.distM)} km · ${formatDuration(s.activeMs)} · ${formatPace(s.avgPaceSecPerKm)}/km" +
                (s.heartRate?.let { " · ♥ $it" } ?: "")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        if (!listening && !force) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }
}
