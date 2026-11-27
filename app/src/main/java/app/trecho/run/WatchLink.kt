package app.trecho.run

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * 워치의 표준 BLE 심박 방송(Heart Rate Service 0x180D)을 받는 부분.
 * Garmin은 '활동 중 심박 방송'을 켜 두면 워치에서 러닝을 시작할 때 방송을 켜고 끝낼 때 끈다.
 */
object Watch {
    enum class Link { OFF, WAITING, CONNECTING, CONNECTED }
    data class Status(val link: Link = Link.OFF, val hr: Int? = null, val btOn: Boolean = false,
                      val battery: Int? = null, val batteryMs: Long = 0L)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    internal fun set(s: Status) { _status.value = s }

    val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** BLE 권한(Android 12+: 근처 기기) */
    fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBlePermission(ctx: Context) = blePermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun btEnabled(ctx: Context): Boolean =
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)

    fun refreshBt(ctx: Context) { set(status.value.copy(btOn = btEnabled(ctx))) }

    /** 마지막으로 읽은 워치 배터리(%)를 저장해 두고, 연결이 끊겨도 보여 준다 */
    fun saveBattery(ctx: Context, pct: Int) {
        val now = System.currentTimeMillis()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt("watchBattery", pct).putLong("watchBatteryMs", now).apply()
        set(status.value.copy(battery = pct, batteryMs = now))
    }

    fun loadBattery(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pct = p.getInt("watchBattery", -1)
        if (pct >= 0 && status.value.battery == null) set(status.value.copy(battery = pct, batteryMs = p.getLong("watchBatteryMs", 0L)))
    }

    /** 심박 측정값(0x2A37) 해석: 첫 바이트 bit0이 1이면 16비트, 아니면 8비트 */
    fun parseHr(v: ByteArray?): Int? {
        if (v == null || v.size < 2) return null
        val flags = v[0].toInt()
        return if (flags and 0x01 != 0) {
            if (v.size < 3) null else (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        } else v[1].toInt() and 0xFF
    }
}

/**
 * 등록한 워치를 찾아 연결하고 심박 알림을 받는다. 끊기면 다시 찾는다.
 * 콜백은 메인 스레드에서 부른다.
 */
@SuppressLint("MissingPermission") // 권한은 등록 화면에서 받고, 없으면 start()가 아무것도 하지 않는다
class HrLink(
    private val ctx: Context,
    private val onHr: (Int) -> Unit,
    private val onLink: (Watch.Link) -> Unit,
    private val onBattery: (Int) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var address = ""
    private var name = ""
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    private var boostUntil = 0L
    private var pendingCccd: BluetoothGattDescriptor? = null

    fun start(address: String, name: String) {
        this.address = address; this.name = name
        if (running) return
        running = true
        scan()
    }

    /** 앱을 열었을 때 30초 동안 빠르게 찾는다(평소엔 배터리 절약 모드) */
    fun boost() {
        if (!running) return
        boostUntil = SystemClock.elapsedRealtime() + 30_000
        if (scanning) { stopScan(); scan() }
        main.postDelayed({ if (scanning) { stopScan(); scan() } }, 30_500)
    }

    fun stop() {
        running = false
        stopScan()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        onLink(Watch.Link.OFF)
    }

    private fun scan() {
        if (!running || scanning) return
        val a = adapter
        if (a == null || !a.isEnabled || !Watch.hasBlePermission(ctx)) {
            onLink(Watch.Link.WAITING)
            main.postDelayed({ scan() }, 30_000)   // 블루투스가 꺼져 있으면 30초 뒤 다시
            return
        }
        val scanner = a.bluetoothLeScanner ?: return
        // 화면이 꺼져도 동작하도록 서비스 UUID 필터를 건다
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Watch.HR_SERVICE)).build())
        val mode = if (SystemClock.elapsedRealtime() < boostUntil) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        runCatching { scanner.startScan(filters, settings, scanCb); scanning = true }
        onLink(Watch.Link.WAITING)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private fun matches(d: BluetoothDevice, advName: String?): Boolean {
        if (address.isNotEmpty() && d.address.equals(address, ignoreCase = true)) return true
        val n = advName ?: runCatching { d.name }.getOrNull()
        return name.isNotEmpty() && n == name
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!running || gatt != null) return@post
                if (!matches(result.device, result.scanRecord?.deviceName)) return@post
                stopScan()
                onLink(Watch.Link.CONNECTING)
                gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; main.postDelayed({ scan() }, 10_000) }
        }
    }

    private fun lost(g: BluetoothGatt) {
        runCatching { g.close() }
        if (gatt == g) gatt = null
        if (running) { onLink(Watch.Link.WAITING); main.postDelayed({ scan() }, 2_000) }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else lost(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(Watch.HR_SERVICE)?.getCharacteristic(Watch.HR_MEASUREMENT)
                if (ch == null) { g.disconnect(); return@post }
                g.setCharacteristicNotification(ch, true)
                val d = ch.getDescriptor(Watch.CCCD) ?: return@post
                // 배터리 서비스가 있으면 먼저 한 번 읽고(GATT 요청은 하나씩), 그다음 심박 알림을 켠다
                val bat = g.getService(Watch.BATTERY_SERVICE)?.getCharacteristic(Watch.BATTERY_LEVEL)
                if (bat != null && runCatching { g.readCharacteristic(bat) }.getOrDefault(false)) pendingCccd = d
                else enableNotify(g, d)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            batteryRead(g, ch, value, status)
        }

        @Deprecated("Android 12 이하용")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") batteryRead(g, ch, ch.value, status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handle(ch, value)
        }

        @Deprecated("Android 12 이하용")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") handle(ch, ch.value)
        }
    }

    private fun enableNotify(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun batteryRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
        if (ch.uuid != Watch.BATTERY_LEVEL) return
        val pct = value?.firstOrNull()?.toInt()?.and(0xFF)
        main.post {
            if (status == BluetoothGatt.GATT_SUCCESS && pct != null && pct in 0..100) onBattery(pct)
            pendingCccd?.let { pendingCccd = null; enableNotify(g, it) }
        }
    }

    private fun handle(ch: BluetoothGattCharacteristic, value: ByteArray?) {
        if (ch.uuid != Watch.HR_MEASUREMENT) return
        val bpm = Watch.parseHr(value) ?: return
        main.post {
            onLink(Watch.Link.CONNECTED)
            if (bpm > 0) onHr(bpm)
        }
    }
}
