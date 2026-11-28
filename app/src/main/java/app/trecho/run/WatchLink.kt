package app.trecho.run

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** 홈 시계 아이콘 상태. 폰은 워치와 연결하지 않고 Garmin 데이터만 가져온다. */
object Watch {
    enum class Link { OFF, CONNECTING, CONNECTED }
}

/**
 * 워치 러닝 상태 표시 전용. Garmin 워치는 러닝 중 '심박 방송'을 켜 두면 표준 심박 서비스(0x180D)를 광고한다.
 * 앱이 화면에 있는 동안 그 광고만 듣고(연결·기록 안 함), 최근 20초 안에 보였으면 러닝중으로 본다.
 * 등록해 둔 워치(주소·이름)가 있으면 그것만, 없으면 이름에 Garmin 모델명이 들어간 기기만 본다.
 */
@SuppressLint("MissingPermission")
object WatchPresence {
    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val GARMIN_NAMES = listOf("forerunner", "fenix", "fēnix", "venu", "epix", "instinct", "enduro", "vivoactive", "garmin", "approach")
    private const val TIMEOUT_MS = 20_000L

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var lastSeen = 0L
    private var cb: ScanCallback? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            _running.value = lastSeen > 0 && System.currentTimeMillis() - lastSeen < TIMEOUT_MS
            main.postDelayed(this, 5_000)
        }
    }

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(ctx: Context) = permissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start(ctx: Context, settings: Settings) {
        if (cb != null || !hasPermission(ctx)) return
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val addr = settings.watchAddress; val name = settings.watchName
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName.orEmpty()
                val mine = when {
                    addr.isNotEmpty() || name.isNotEmpty() ->
                        result.device.address == addr || (name.isNotEmpty() && n == name)
                    else -> GARMIN_NAMES.any { n.lowercase().contains(it) }
                }
                if (mine) { lastSeen = System.currentTimeMillis(); _running.value = true }
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
        val mode = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        if (runCatching { scanner.startScan(filters, mode, c) }.isSuccess) {
            cb = c
            main.removeCallbacks(tick); main.post(tick)
        }
    }

    fun stop(ctx: Context? = null) {
        main.removeCallbacks(tick)
        val c = cb ?: return
        cb = null
        runCatching {
            val bm = ctx?.getSystemService(BluetoothManager::class.java)
            (bm?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter())?.bluetoothLeScanner?.stopScan(c)
        }
    }
}
