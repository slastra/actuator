package us.lastra.actuator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Where the connection is, as the UI should describe it. */
enum class Phase { Idle, NeedPermission, BluetoothOff, Searching, NotFound, Pairing, PairFailed, Connecting, Connected }

/** The device's status characteristic, decoded. */
data class DeviceStatus(
    val angleTenths: Int?,  // null until the servo has been positioned
    val us: Int,
    val driving: Boolean,
    val limits: Boolean,
    val locked: Boolean,
    val sweeping: Boolean,
    val heatPct: Int,
    val cooldownS: Int,
)

/**
 * The BLE side of the app. Protocol: firmware/main/ble.h in this repo.
 *
 * Finds the actuator by its service UUID, pairs once (Android asks for the
 * 6-digit PIN), and holds one connection between [start] and [stop]. The
 * firmware takes a single connection, so the activity calls [stop] in onStop to
 * free it.
 *
 * Android allows one GATT operation in flight, so reads and writes go through a
 * queue. Slider moves don't queue: only the newest angle is kept, so a fast drag
 * never builds a backlog.
 *
 * State is Compose snapshot state, written only on the main thread.
 */
@SuppressLint("MissingPermission") // the activity checks permissions before start()
class ActuatorLink(private val context: Context, private val onMessage: (String) -> Unit) {

    var phase by mutableStateOf(Phase.Idle)
        private set
    var status by mutableStateOf<DeviceStatus?>(null)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var pairing: BluetoothDevice? = null
    private var angleChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private val ops = ArrayDeque<() -> Boolean>() // each returns true if it started an op
    private var opBusy = false
    private var pendingAngle: Int? = null         // tenths of a degree

    // ---- lifecycle -------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        })
        connect()
    }

    fun stop() {
        if (!running) return
        running = false
        context.unregisterReceiver(receiver)
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        reset()
        pairing = null
        phase = Phase.Idle
    }

    fun needsPermission() {
        phase = Phase.NeedPermission
    }

    /** Find, pair if needed, connect. Safe to call again after a failure. */
    fun connect() {
        if (!running || scanning || gatt != null || pairing != null) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            phase = Phase.BluetoothOff
            return
        }
        val scanner = bt.bluetoothLeScanner ?: return
        scanning = true
        phase = Phase.Searching
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private val scanTimeout = Runnable {
        stopScan()
        phase = Phase.NotFound
    }

    // ---- commands ----------------------------------------------------------

    /** Slider: coalesced, no response. */
    fun drag(tenths: Int) {
        pendingAngle = tenths
        pump()
    }

    /** Presets: with a response, so a refusal (cooling, busy) is reported. */
    fun moveTo(tenths: Int) {
        pendingAngle = null
        enqueue { write(angleChar, le16(tenths), withResponse = true) }
    }

    fun sweep() = enqueue { write(commandChar, byteArrayOf(1), withResponse = true) }

    fun release() = enqueue { write(commandChar, byteArrayOf(0), withResponse = true) }

    // ---- finding and pairing --------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            onFound(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            phase = Phase.NotFound
            onMessage("Bluetooth scan failed ($errorCode)")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        // Or a stale timeout from this scan would cut short the next one.
        main.removeCallbacks(scanTimeout)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun onFound(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            open(device)
        } else {
            pairing = device
            phase = Phase.Pairing
            device.createBond()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> connect()
                        BluetoothAdapter.STATE_OFF -> {
                            stopScan()
                            gatt?.close()
                            reset()
                            pairing = null
                            phase = Phase.BluetoothOff
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (device == null || device.address != pairing?.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> {
                            pairing = null
                            open(device)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            pairing = null
                            phase = Phase.PairFailed
                        }
                    }
                }
            }
        }
    }

    // ---- connection ------------------------------------------------------

    private fun open(device: BluetoothDevice) {
        phase = Phase.Connecting
        deviceName = device.name
        gatt = if (Build.VERSION.SDK_INT >= 37) {
            val settings = BluetoothGattConnectionSettings.Builder()
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            device.connectGatt(settings, context.mainExecutor, gattCallback)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private fun reset() {
        gatt = null
        angleChar = null
        commandChar = null
        statusChar = null
        ops.clear()
        opBusy = false
        pendingAngle = null
        status = null
    }

    // Everything is handled on main. On API 37 the callbacks already arrive
    // there (connectGatt with mainExecutor) and the post is just a hop; on
    // 33-36 they come on a binder thread and it's needed.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                } else {
                    g.close()
                    reset()
                    if (running) {
                        phase = Phase.Searching
                        main.postDelayed({ connect() }, 1500)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (g != gatt) return@post
                val svc = g.getService(SVC)
                if (st != BluetoothGatt.GATT_SUCCESS || svc == null) {
                    onMessage("That device isn't an actuator")
                    g.disconnect()
                    return@post
                }
                angleChar = svc.getCharacteristic(ANGLE)
                commandChar = svc.getCharacteristic(COMMAND)
                statusChar = svc.getCharacteristic(STATUS)
                enqueue { subscribe(statusChar) }
                enqueue { statusChar?.let { g.readCharacteristic(it) } ?: false }
                phase = Phase.Connected
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int,
        ) {
            main.post {
                if (st == BluetoothGatt.GATT_SUCCESS && c.uuid == STATUS) status = decode(value)
                opDone()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                if (st != BluetoothGatt.GATT_SUCCESS) onMessage(describe(st))
                opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { opDone() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (c.uuid == STATUS) status = decode(value) }
        }
    }

    // ---- GATT op queue ---------------------------------------------------

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        pump()
    }

    private fun pump() {
        while (!opBusy && gatt != null) {
            val op: () -> Boolean = ops.removeFirstOrNull()
                ?: pendingAngle?.let { tenths ->
                    pendingAngle = null
                    { write(angleChar, le16(tenths), withResponse = false) }
                }
                ?: return
            if (op()) opBusy = true
        }
    }

    private fun opDone() {
        opBusy = false
        pump()
    }

    private fun write(c: BluetoothGattCharacteristic?, value: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        if (c == null) return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
    }

    private fun subscribe(c: BluetoothGattCharacteristic?): Boolean {
        val g = gatt ?: return false
        val cccd = c?.getDescriptor(CCCD) ?: return false
        g.setCharacteristicNotification(c, true)
        return g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
            BluetoothStatusCodes.SUCCESS
    }

    companion object {
        private fun uuid(nn: Int): UUID =
            UUID.fromString("c175482b-6776-4dfd-9299-a5b850b379%02x".format(nn))

        val SVC: UUID = uuid(0x00)
        val ANGLE: UUID = uuid(0x01)
        val COMMAND: UUID = uuid(0x02)
        val STATUS: UUID = uuid(0x03)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 20_000L

        /** u16 angle (tenths, 0xFFFF unknown), u16 us, u8 flags, u8 heat %, u8 cooldown s. */
        fun decode(v: ByteArray): DeviceStatus? {
            if (v.size < 7) return null
            val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
            val angle = b.short.toInt() and 0xffff
            val us = b.short.toInt() and 0xffff
            val flags = b.get().toInt() and 0xff
            return DeviceStatus(
                angleTenths = angle.takeIf { it != 0xffff },
                us = us,
                driving = flags and 1 != 0,
                limits = flags and 2 != 0,
                locked = flags and 4 != 0,
                sweeping = flags and 8 != 0,
                heatPct = b.get().toInt() and 0xff,
                cooldownS = b.get().toInt() and 0xff,
            )
        }

        fun describe(status: Int) = when (status) {
            0x80 -> "Out of range"
            0x81 -> "Cooling down: moves are paused for a moment"
            0x82 -> "Busy: wait for the sweep or update to finish"
            5, 15 -> "Not paired. Forget it in Bluetooth settings and reconnect."
            else -> "Command failed (status $status)"
        }

        private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
    }
}
