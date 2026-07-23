package com.example.hk11ultra3.ble

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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.hk11ultra3.model.SleepRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SYNC_TIMEOUT_MS = 3_000L
    }

    interface Callback {
        fun onConnected(mac: String)
        fun onDisconnected()
        fun onSleepRecord(record: SleepRecord)
        fun onSyncComplete(sleepRecords: List<SleepRecord>)
        fun onError(message: String)
    }

    var callback: Callback? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedMac: String? = null

    private val sleepRecords = mutableListOf<SleepRecord>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var syncTimeoutRunnable: Runnable? = null

    private val pendingOperations = ConcurrentLinkedQueue<ByteArray>()
    private var isWriting = false

    fun isConnected(): Boolean = bluetoothGatt?.let {
        bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
    } ?: false

    fun getConnectedMac(): String? = connectedMac

    suspend fun scanAndConnect(targetMac: String? = null): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            callback?.onError("Bluetooth is off")
            return false
        }

        val device: BluetoothDevice = if (targetMac != null) {
            try {
                bluetoothAdapter.getRemoteDevice(targetMac)
            } catch (e: Exception) {
                scanForDevice(targetMac) ?: run {
                    callback?.onError("Device not found: $targetMac")
                    return false
                }
            }
        } else {
            scanForDevice(null) ?: run {
                callback?.onError("HK11 device not found")
                return false
            }
        }

        return connect(device)
    }

    fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedMac = null
    }

    private suspend fun connect(device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Connecting: ${device.address}")
            connectedMac = device.address

            val deferred = CompletableDeferred<Boolean>()

            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "Connection state: status=$status state=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            gatt.discoverServices()
                        } else {
                            deferred.complete(false)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        mainHandler.post { callback?.onDisconnected() }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Service discovery failed")
                        deferred.complete(false)
                        return
                    }
                    val service = gatt.getService(BleProtocol.SERVICE_UUID)
                    if (service == null) {
                        Log.e(TAG, "Nordic UART service not found")
                        deferred.complete(false)
                        return
                    }
                    writeCharacteristic = service.getCharacteristic(BleProtocol.WRITE_CHAR_UUID)
                    notifyCharacteristic = service.getCharacteristic(BleProtocol.NOTIFY_CHAR_UUID)
                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        Log.e(TAG, "Characteristics not found")
                        deferred.complete(false)
                        return
                    }
                    val ok = gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    if (!ok) {
                        Log.e(TAG, "Notify enable failed")
                        deferred.complete(false)
                        return
                    }
                    val desc = notifyCharacteristic!!.getDescriptor(BleProtocol.CCCD_UUID)
                    desc?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                    Log.i(TAG, "Services ready, notify enabled")
                    deferred.complete(true)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
                ) {
                    Log.d(TAG, "CCCD write: status=$status")
                    if (descriptor.uuid == BleProtocol.CCCD_UUID && !deferred.isCompleted) {
                        deferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
                ) {
                    handleNotification(value)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
                ) {
                    Log.d(TAG, "Write complete: status=$status")
                    isWriting = false
                    processWriteQueue()
                }
            })

            deferred.await()
        }

    suspend fun handshake() {
        Log.i(TAG, "Starting handshake")
        writeCharacteristic(BleProtocol.sendPhoneSystem())
        val connectBytes = BleProtocol.sendConnectRequestType(
            connectType = 1,
            deviceModel = android.os.Build.MODEL.take(12)
        )
        writeCharacteristic(connectBytes)
        Log.i(TAG, "Handshake done")
    }

    suspend fun requestSleepData(lastSyncTime: Long = 0L) {
        Log.i(TAG, "Requesting sleep data, lastSync=$lastSyncTime")
        sleepRecords.clear()
        writeCharacteristic(BleProtocol.setSyncDataHr(lastSyncTime))
        kotlinx.coroutines.delay(500)
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        writeCharacteristic(BleProtocol.syncSleepData(yesterday))
        resetSyncTimeout()
    }

    suspend fun sendNotificationToWatch(title: String, message: String) {
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot send notification")
            return
        }
        val packet = BleProtocol.sendMessageNotification(title, message)
        writeCharacteristicSplit(packet, 20)
        Log.i(TAG, "Notification sent to watch: $title - $message")
    }

    private fun writeCharacteristic(data: ByteArray) {
        pendingOperations.add(data)
        processWriteQueue()
    }

    private fun writeCharacteristicSplit(data: ByteArray, mtu: Int) {
        if (data.size <= mtu) {
            writeCharacteristic(data)
            return
        }
        val firstLen = mtu - 3
        pendingOperations.add(data.copyOf(firstLen))
        var offset = firstLen
        var seq = 0
        while (offset < data.size) {
            val chunkSize = minOf(mtu - 1, data.size - offset)
            val chunk = ByteArray(chunkSize + 1)
            chunk[0] = seq.toByte()
            System.arraycopy(data, offset, chunk, 1, chunkSize)
            pendingOperations.add(chunk)
            offset += chunkSize
            seq++
        }
        processWriteQueue()
    }

    private fun processWriteQueue() {
        if (isWriting) return
        val data = pendingOperations.poll() ?: return
        val char = writeCharacteristic ?: return
        isWriting = true
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val success = bluetoothGatt?.writeCharacteristic(char) ?: false
        if (!success) {
            Log.e(TAG, "Write failed")
            isWriting = false
            processWriteQueue()
        }
    }

    private var packageBuffer: ByteArray? = null
    private var packageIndex: Int = 0

    private fun handleNotification(value: ByteArray) {
        try {
            val assembled = assemblePacket(value) ?: return
            val datas = BleProtocol.bytesToArrayList(assembled)
            if (datas.isEmpty()) return
            val hex = assembled.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "Notify: $hex")
            val header = datas[0]
            if (header != 0xAB && header != 0xEA) {
                Log.d(TAG, "Unknown header: 0x${header.toString(16)}, skipped")
                return
            }
            if (datas.size >= 5 && header == 0xAB && datas[4] == BleProtocol.CMD_SLEEP_DATA) {
                val record = SleepDataParser.parse(assembled, datas)
                if (record != null) {
                    sleepRecords.add(record)
                    mainHandler.post { callback?.onSleepRecord(record) }
                    resetSyncTimeout()
                }
            }
            if (datas.size >= 5 && header == 0xEA && datas[4] == 0xE3) {
                val reply = byteArrayOf(
                    0xEA.toByte(), 0x00, 0x04, 0x00.toByte(),
                    0xE3.toByte(), 0x02, 0x00
                )
                writeCharacteristic(reply)
                Log.d(TAG, "Heartbeat replied")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification handling error", e)
        }
    }

    private fun assemblePacket(value: ByteArray): ByteArray? {
        val buf = packageBuffer
        if (buf == null) {
            if (value.size < 3) {
                return value
            }
            val totalLen = BleProtocol.bytesToInt(value, 1, 2) + 3
            if (value.size >= totalLen) {
                return value
            }
            packageBuffer = ByteArray(totalLen)
            System.arraycopy(value, 0, packageBuffer, 0, value.size)
            packageIndex = value.size
            Log.d(TAG, "Multi-packet started: $packageIndex / $totalLen")
            return null
        } else {
            val copyLen = minOf(value.size - 1, buf.size - packageIndex)
            System.arraycopy(value, 1, buf, packageIndex, copyLen)
            packageIndex += copyLen
            Log.d(TAG, "Multi-packet continued: $packageIndex / ${buf.size}")
            if (packageIndex >= buf.size) {
                val result = buf
                packageBuffer = null
                packageIndex = 0
                return result
            }
            return null
        }
    }

    private fun resetSyncTimeout() {
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        syncTimeoutRunnable = Runnable {
            Log.i(TAG, "Sync complete, received ${sleepRecords.size} sleep records")
            mainHandler.post {
                callback?.onSyncComplete(sleepRecords.toList())
            }
        }
        syncTimeoutRunnable?.let { mainHandler.postDelayed(it, SYNC_TIMEOUT_MS) }
    }

    private suspend fun scanForDevice(targetMac: String?): BluetoothDevice? {
        val deferred = CompletableDeferred<BluetoothDevice?>()
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            callback?.onError("BLE scanner unavailable")
            return null
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleProtocol.SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: ""
                val mac = device.address
                Log.d(TAG, "Device found: $name ($mac)")
                if (targetMac != null) {
                    if (mac.equals(targetMac, ignoreCase = true)) {
                        scanner.stopScan(this)
                        deferred.complete(device)
                    }
                } else {
                    if (name.contains("HK11", ignoreCase = true) ||
                        name.contains("Ultra", ignoreCase = true)) {
                        scanner.stopScan(this)
                        deferred.complete(device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                deferred.complete(null)
            }
        }

        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.i(TAG, "Scanning for devices...")

        val result = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                deferred.await()
            }
        }

        scanner.stopScan(scanCallback)
        return result
    }
}
