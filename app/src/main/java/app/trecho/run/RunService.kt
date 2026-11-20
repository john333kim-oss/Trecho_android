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

/**
 * 러닝 중 화면이 꺼져도 GPS·걸음 센서를 계속 받는 포그라운드 서비스.
 * 알림에 거리·시간·평균 페이스를 1초마다 갱신한다.
 */
class RunService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "run"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, RunService::class.java).setAction(action)
            if (action == ACTION_START) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false

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
            updateNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                if (!RunSession.isActive) RunSession.start()
                startListening()
            }
            ACTION_PAUSE -> { RunSession.pause(); updateNotification() }
            ACTION_RESUME -> { RunSession.resume(); updateNotification() }
            ACTION_STOP -> { stopListening(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
            null -> {
                // 시스템이 서비스를 다시 띄웠는데 진행 중 기록이 없으면 정리한다.
                if (!RunSession.isActive) stopSelf()
            }
        }
        return START_NOT_STICKY
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
        val title = when (s?.state) {
            RunState.PAUSED -> getString(R.string.notif_paused)
            else -> getString(R.string.notif_running)
        }
        val text = if (s == null) "" else
            "${formatKm(s.distM)} km · ${formatDuration(s.activeMs)} · ${formatPace(s.avgPaceSecPerKm)}/km"
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

    private fun updateNotification() {
        if (!listening) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
