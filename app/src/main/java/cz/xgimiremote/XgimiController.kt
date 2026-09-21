package cz.xgimiremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Ovládání projektoru XGIMI (Elfin) podle integrace
 * manymuch/Xgimi-4-Home-Assistant.
 *
 * Zapnutí: BLE advertisement – manufacturer ID 0x0046 + token z dálku,
 * service UUID 0x1812 (HID), název "Bluetooth 4.0 RC", appearance 961.
 * Vysílá se ~4 s, pak se reklama zastaví.
 *
 * Ostatní příkazy: UDP "KEYPRESSES:<kód>" na port 16735 (funguje jen,
 * když je projektor zapnutý a firmware otevře tyto porty – na některých
 * mezinárodních verzích nejsou otevřené).
 *
 * Stav: TCP spojení na port 554 (RTSP). Projektory v pohotovostním režimu
 * obvykle nereagují, takže "vypnuto" znamená "nedostupný".
 */
object XgimiController {

    const val COMMAND_PORT = 16735
    const val DEFAULT_ALIVE_PORT = 554
    const val DEFAULT_ADVERTISE_MS = 4000L
    const val MANUFACTURER_ID = 0x0046
    const val WAKE_LOCAL_NAME = "Bluetooth 4.0 RC"

    private val WAKE_SERVICE_UUID: UUID =
        UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

    /** Mapování příkazů z pyxgimi.py (manymuch/Xgimi-4-Home-Assistant). */
    private val keyCommands = mapOf(
        "poweroff" to "KEYPRESSES:30",
        "power" to "KEYPRESSES:116",
        "back" to "KEYPRESSES:48",
        "home" to "KEYPRESSES:35",
        "menu" to "KEYPRESSES:139",
        "right" to "KEYPRESSES:37",
        "left" to "KEYPRESSES:50",
        "up" to "KEYPRESSES:36",
        "down" to "KEYPRESSES:38",
        "ok" to "KEYPRESSES:49",
        "volumedown" to "KEYPRESSES:114",
        "volumeup" to "KEYPRESSES:115",
        "volumemute" to "KEYPRESSES:113",
        "autofocus" to "KEYPRESSES:2099",
    )

    /** Převede hex řetězec (s nebo bez mezer, "0x", dvojteček) na token. */
    fun parseToken(hex: String): ByteArray? {
        val cleaned = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0 || cleaned.length > 32) return null
        return ByteArray(cleaned.length / 2) {
            cleaned.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** Zkusí se přes TCP spojit na port projektoru (defaultně 554/RTSP). */
    suspend fun checkAlive(
        ip: String,
        port: Int = DEFAULT_ALIVE_PORT,
        timeoutMs: Int = 2000
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Odešle UDP příkaz na port 16735. */
    suspend fun sendCommand(ip: String, command: String): Boolean = withContext(Dispatchers.IO) {
        val payload = keyCommands[command] ?: return@withContext false
        try {
            DatagramSocket().use { socket ->
                val bytes = payload.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(ip, COMMAND_PORT)))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Zapne projektor z pohotovostního režimu: ~4 s vysílá BLE reklamu
     * stejnou, jakou posílá ovladač při stisku tlačítka napájení.
     */
    @SuppressLint("MissingPermission")
    suspend fun wake(
        context: Context,
        token: ByteArray,
        durationMs: Long = DEFAULT_ADVERTISE_MS
    ): Result<Unit> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
            ?: return Result.failure(IllegalStateException("Bluetooth není k dispozici"))
        if (!adapter.isEnabled) {
            return Result.failure(IllegalStateException("Bluetooth je vypnutý"))
        }
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return Result.failure(IllegalStateException("Toto zařízení nepodporuje BLE advertising"))

        val originalName = adapter.name
        return try {
            // Reklama se musí jmenovat "Bluetooth 4.0 RC" – Android sice
            // neumí nastavit libovolný název do AdvertiseData, ale používá
            // název adaptéru, tak ho dočasně přepíšeme.
            adapter.name = WAKE_LOCAL_NAME
            delay(300) // nechat název propagate do kontroléru
            advertiseOnce(advertiser, token, durationMs)
        } finally {
            adapter.name = originalName
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun advertiseOnce(
        advertiser: BluetoothLeAdvertiser,
        token: ByteArray,
        durationMs: Long
    ): Result<Unit> = withContext(Dispatchers.Main) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPower(false)
            .addServiceUuid(ParcelUuid(WAKE_SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, token)
            .build()

        var callback: AdvertiseCallback? = null
        val startResult = suspendCancellableCoroutine<Result<Unit>> { cont ->
            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }

                override fun onStartFailure(errorCode: Int) {
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException("BLE advertising selhal (kód $errorCode)")))
                    }
                }
            }
            callback = cb
            try {
                advertiser.startAdvertising(settings, data, cb)
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(Result.failure(e))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                runCatching { advertiser.stopAdvertising(cb) }
            }
        }

        if (startResult.isSuccess) {
            try {
                delay(durationMs)
            } finally {
                callback?.let { runCatching { advertiser.stopAdvertising(it) } }
            }
        }
        startResult
    }
}
