package com.example.hk11ultra3.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Bluetooth kapali")
            callback?.onError("Bluetooth is off")
            return false
        }
        com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Taranıyor: targetMac=$targetMac")

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

    private suspend fun connect(device: BluetoothDevice): Boolean {
        // Try up to 3 times, cleanup between attempts
        repeat(3) { attempt ->
            val result = connectInternal(device, attempt + 1)
            if (result) return true
            // Cleanup before retry
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            if (attempt < 2) {
                com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Baglanti deneme ${attempt + 1} basarisiz, 2sn bekleniyor...")
                kotlinx.coroutines.delay(2000)
            }
        }
        return false
    }

    private suspend fun connectInternal(device: BluetoothDevice, attempt: Int): Boolean =
        withContext(Dispatchers.IO) {
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Baglaniyor (deneme $attempt): ${device.name} (${device.address})")
            connectedMac = device.address

            val deferred = CompletableDeferred<Boolean>()

            // TRANSPORT_LE: WearFit Pro ile ayni, sadece BLE (HK11 dual-mode oldugu icin kritik!)
            val gattCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "Connection state: status=$status state=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        context.getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
                            .edit().putBoolean("ble_connected", true).apply()
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Baglandi, 1sn bekleniyor...")
                            // WearFit Pro: requestConnectionPriority HIGH
                            try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                            // WearFit Pro: PHY 2M
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                try { gatt.setPreferredPhy(2, 2, 0) } catch (_: Exception) {}
                            }
                            // WearFit Pro: 1000ms delay before discoverServices
                            mainHandler.postDelayed({
                                gatt.discoverServices()
                            }, 1000L)
                        } else {
                            deferred.complete(false)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        context.getSharedPreferences("hk11_settings", Context.MODE_PRIVATE)
                            .edit().putBoolean("ble_connected", false).apply()
                        bluetoothGatt?.close()
                        bluetoothGatt = null
                        mainHandler.post { callback?.onDisconnected() }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    com.example.hk11ultra3.service.AppLogger.log(context, TAG, "onServicesDiscovered: status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Servis kesfi basarisiz (status=$status)")
                        deferred.complete(false)
                        return
                    }
                    val service = gatt.getService(BleProtocol.SERVICE_UUID)
                    if (service == null) {
                        com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Nordic UART servisi BULUNAMADI - TRANSPORT_LE dogru mu?")
                        deferred.complete(false)
                        return
                    }
                    writeCharacteristic = service.getCharacteristic(BleProtocol.WRITE_CHAR_UUID)
                    notifyCharacteristic = service.getCharacteristic(BleProtocol.NOTIFY_CHAR_UUID)
                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Karakteristikler bulunamadi")
                        deferred.complete(false)
                        return
                    }
                    val ok = gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    if (!ok) {
                        com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Notify enable basarisiz")
                        deferred.complete(false)
                        return
                    }
                    val desc = notifyCharacteristic!!.getDescriptor(BleProtocol.CCCD_UUID)
                    desc?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }
                    com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Servisler hazir, notify aktif")
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
            }

            // WearFit Pro: TRANSPORT_LE = 2 (dual-mode cihazda BLE'ye zorla)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (bluetoothGatt == null) {
                com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: connectGatt null dondu")
                return@withContext false
            }
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "connectGatt basarili, servis kesfi bekleniyor...")

            val success = deferred.await()
            if (!success) {
                com.example.hk11ultra3.service.AppLogger.log(context, TAG, "Baglanti basarisiz (deneme $attempt)")
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
            success
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
        // Request from 7 days ago to get all stored sleep records
        val fromTime = if (lastSyncTime > 0) lastSyncTime
            else System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000

        // Match WearFit Pro's DataSyncMgr.startSyncData() flow:
        // 1. setSyncDataHr - general data sync
        writeCharacteristic(BleProtocol.setSyncDataHr(fromTime))
        kotlinx.coroutines.delay(300)
        // 2. syncSleepApnea - sleep apnea data
        writeCharacteristic(BleProtocol.syncSleepApnea(fromTime))
        kotlinx.coroutines.delay(300)
        // 3. syncGpsData - GPS data (required by protocol)
        writeCharacteristic(BleProtocol.syncGpsData(0))

        // 4. Request yesterday's detailed sleep records
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        kotlinx.coroutines.delay(300)
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
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: BLE tarayici yok")
            return null
        }

        val scanSettings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val foundDevices = mutableListOf<String>()
        val scanCallback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "(isimsiz)"
                val mac = device.address
                foundDevices.add("$name ($mac) RSSI:${result.rssi}")
                if (targetMac != null) {
                    if (mac.equals(targetMac, ignoreCase = true)) {
                        scanner.stopScan(this)
                        deferred.complete(device)
                    }
                } else if (name.contains("HK11", ignoreCase = true) || name.contains("Ultra", ignoreCase = true)) {
                    scanner.stopScan(this)
                    deferred.complete(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Tarama basarisiz, kod=$errorCode")
                deferred.complete(null)
            }
        }

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "BLE tarama basladi (10sn)")
        } catch (e: SecurityException) {
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: Tarama izni yok")
            return null
        }

        val result = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { deferred.await() }
        }

        scanner.stopScan(scanCallback)
        if (result == null) {
            val summary = if (foundDevices.isEmpty()) "HIC CIHAZ BULUNAMADI"
                else "Bulunan: ${foundDevices.take(5).joinToString(" | ")}"
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HATA: $summary. Aranan: $targetMac")
        } else {
            com.example.hk11ultra3.service.AppLogger.log(context, TAG, "HEDEF BULUNDU: ${result.name} (${result.address})")
        }
        return result
    }
}
