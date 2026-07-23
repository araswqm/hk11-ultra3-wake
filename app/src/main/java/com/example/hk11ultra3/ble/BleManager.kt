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

/**
 * HK11 Ultra3 BLE yöneticisi.
 *
 * Bağlantı akışı:
 *   1. scanAndConnect(mac)  → cihazı tara ve bağlan
 *   2. onServicesDiscovered → notify'ı enable et
 *   3. handshake()          → sendPhoneSystem + sendConnectRequestType
 *   4. requestSleepData()   → setSyncDataHr → syncSleepData
 *   5. onSleepRecord        → her uyku kaydı için callback
 *   6. onSyncComplete       → 3sn sessizlik = senkronizasyon bitti
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val SYNC_TIMEOUT_MS = 3_000L  // 3 saniye sessizlik = sync bitti
    }

    // ── Callback arayüzü ─────────────────────────────────────────────

    interface Callback {
        fun onConnected(mac: String)
        fun onDisconnected()
        fun onSleepRecord(record: SleepRecord)
        fun onSyncComplete(sleepRecords: List<SleepRecord>)
        fun onError(message: String)
    }

    var callback: Callback? = null

    // ── İç durum ─────────────────────────────────────────────────────

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

    // ── Public API ────────────────────────────────────────────────────

    fun isConnected(): Boolean = bluetoothGatt?.let {
        bluetoothManager.getConnectionState(it.device, BluetoothProfile.GATT) ==
                BluetoothProfile.STATE_CONNECTED
    } ?: false

    fun getConnectedMac(): String? = connectedMac

    /**
     * Hedef cihazı tarar ve bağlanır.
     * Eğer [targetMac] null ise, HK11 Ultra3 adıyla eşleşen ilk cihaza bağlanır.
     */
    suspend fun scanAndConnect(targetMac: String? = null): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            callback?.onError("Bluetooth kapalı")
            return false
        }

        val device: BluetoothDevice = if (targetMac != null) {
            // Doğrudan MAC ile bağlanmayı dene
            try {
                bluetoothAdapter.getRemoteDevice(targetMac)
            } catch (e: Exception) {
                // MAC ile bulunamazsa tara
                scanForDevice(targetMac) ?: run {
                    callback?.onError("Cihaz bulunamadı: $targetMac")
                    return false
                }
            }
        } else {
            // HK11 cihazlarını tara
            scanForDevice(null) ?: run {
                callback?.onError("HK11 cihazı bulunamadı")
                return false
            }
        }

        return connect(device)
    }

    /**
     * BLE bağlantısını kapatır.
     */
    fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedMac = null
    }

    // ── Bağlantı ──────────────────────────────────────────────────────

    private suspend fun connect(device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Bağlanılıyor: ${device.address}")
            connectedMac = device.address

            val deferred = CompletableDeferred<Boolean>()

            bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "Bağlantı durumu: status=$status state=$newState")

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
                        Log.e(TAG, "Servis keşfi başarısız")
                        deferred.complete(false)
                        return
                    }

                    val service = gatt.getService(BleProtocol.SERVICE_UUID)
                    if (service == null) {
                        Log.e(TAG, "Nordic UART servisi bulunamadı")
                        deferred.complete(false)
                        return
                    }

                    writeCharacteristic = service.getCharacteristic(BleProtocol.WRITE_CHAR_UUID)
                    notifyCharacteristic = service.getCharacteristic(BleProtocol.NOTIFY_CHAR_UUID)

                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        Log.e(TAG, "Karakteristikler bulunamadı")
                        deferred.complete(false)
                        return
                    }

                    // Notify'ı enable et
                    val success = gatt.setCharacteristicNotification(notifyCharacteristic, true)
                    if (!success) {
                        Log.e(TAG, "Notify enable başarısız")
                        deferred.complete(false)
                        return
                    }

                    val descriptor = notifyCharacteristic!!
                        .getDescriptor(BleProtocol.CCCD_UUID)
                    descriptor?.let {
                        it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(it)
                    }

                    Log.i(TAG, "Servisler hazır, notify enable edildi")
                    deferred.complete(true)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    // CCCD yazıldı, bağlantı tamam
                    Log.d(TAG, "CCCD descriptor write: status=$status")
                    if (descriptor.uuid == BleProtocol.CCCD_UUID && !deferred.isCompleted) {
                        deferred.complete(status == BluetoothGatt.GATT_SUCCESS)
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    handleNotification(value)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    Log.d(TAG, "Write tamam: status=$status")
                    isWriting = false
                    processWriteQueue()
                }
            })

            deferred.await()
        }

    // ── Handshake ─────────────────────────────────────────────────────

    suspend fun handshake() {
        Log.i(TAG, "Handshake başlatılıyor...")

        // 1. Telefon sistemini bildir
        writeCharacteristic(BleProtocol.sendPhoneSystem())

        // 2. Bağlantı tipini bildir
        val connectBytes = BleProtocol.sendConnectRequestType(
            connectType = 1,
            deviceModel = android.os.Build.MODEL.take(12)
        )
        writeCharacteristic(connectBytes)

        Log.i(TAG, "Handshake tamam")
    }

    // ── Uyku verisi senkronizasyonu ──────────────────────────────────

    suspend fun requestSleepData(lastSyncTime: Long = 0L) {
        Log.i(TAG, "Uyku verisi senkronizasyonu başlatılıyor... lastSync=$lastSyncTime")
        sleepRecords.clear()

        // 1. Genel sync başlat
        writeCharacteristic(BleProtocol.setSyncDataHr(lastSyncTime))

        // 2. Kısa bekle
        kotlinx.coroutines.delay(500)

        // 3. Uyku verisi talep et (dün için)
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        writeCharacteristic(BleProtocol.syncSleepData(yesterday))

        // Sync timeout'u başlat — 3 saniye veri gelmezse tamamlandı say
        resetSyncTimeout()
    }

    /**
     * Saate bildirim mesajı gönderir.
     */
    suspend fun sendNotificationToWatch(title: String, message: String) {
        if (!isConnected()) {
            Log.w(TAG, "Bildirim gönderilemedi: bağlı değil")
            return
        }
        val packet = BleProtocol.sendMessageNotification(title, message)
        // Büyük paketleri böl (MTU sınırı)
        writeCharacteristicSplit(packet, 20)
        Log.i(TAG, "Saate bildirim gönderildi: $title - $message")
    }

    // ── BLE veri gönderme ─────────────────────────────────────────────

    private fun writeCharacteristic(data: ByteArray) {
        pendingOperations.add(data)
        processWriteQueue()
    }

    private fun writeCharacteristicSplit(data: ByteArray, mtu: Int) {
        if (data.size <= mtu) {
            writeCharacteristic(data)
            return
        }
        // İlk paket: header(3) + data[...]
        val firstLen = mtu - 3
        pendingOperations.add(data.copyOf(firstLen))
        // Sonraki paketler: seq byte + data[...]
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
            Log.e(TAG, "Write başarısız")
            isWriting = false
            processWriteQueue()
        }
    }

    // ── BLE notify handler ────────────────────────────────────────────

    /**
     * Çoklu paket birleştirme buffer'ı.
     * DataPackager.java'dan reverse-engineer edildi.
     */
    private var packageBuffer: ByteArray? = null
    private var packageIndex: Int = 0

    private fun handleNotification(value: ByteArray) {
        try {
            // Çoklu paket birleştirme
            val assembled = assemblePacket(value) ?: return
            val datas = BleProtocol.bytesToArrayList(assembled)

            if (datas.isEmpty()) return

            Log.d(TAG, "Notify: ${assembled.joinToString(" ") { "%02X".format(it) }}")

            // Header kontrolü
            val header = datas[0]
            if (header != 0xAB && header != 0xEA) {
                Log.d(TAG, "Bilinmeyen header: 0x${header.toString(16)}, atlanıyor")
                return
            }

            // Sleep verisi kontrolü (AB header, komut 0x52)
            if (datas.size >= 5 && header == 0xAB && datas[4] == BleProtocol.CMD_SLEEP_DATA) {
                val record = SleepDataParser.parse(assembled, datas)
                if (record != null) {
                    sleepRecords.add(record)
                    mainHandler.post { callback?.onSleepRecord(record) }
                    resetSyncTimeout()
                }
            }

            // Heartbeat isteği kontrolü (EA header, komut 0xE3 alt 0x01)
            if (datas.size >= 5 && header == 0xEA.toInt() && datas[4] == 0xE3) {
                // Cevap ver
                val reply = byteArrayOf(
                    0xEA.toByte(), 0x00, 0x04, 0x00.toByte(),
                    0xE3.toByte(), 0x02, 0x00
                )
                writeCharacteristic(reply)
                Log.d(TAG, "Heartbeat cevaplandı")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Notify işleme hatası", e)
        }
    }

    /**
     * Çoklu paket birleştirme.
     * İlk paket: [0xAB] [lenHi] [lenLo] [0xFF] ... — toplam boyut = len + 3
     * Sonraki paket: [seq] [data...] — seq atlanır, kalan buffer'a eklenir
     */
    private fun assemblePacket(value: ByteArray): ByteArray? {
        if (packageBuffer == null) {
            if (value.size < 3) {
                // Paket başlatmak için çok kısa
                return value  // Tek parça paket olarak dene
            }
            val totalLen = BleProtocol.bytesToInt(value, 1, 2) + 3
            if (value.size >= totalLen) {
                return value  // Tek parça, tamam
            }
            // Çok parçalı - buffer başlat
            packageBuffer = ByteArray(totalLen)
            System.arraycopy(value, 0, packageBuffer, 0, value.size)
            packageIndex = value.size
            Log.d(TAG, "Çoklu paket başladı: $packageIndex / $totalLen")
            return null
        } else {
            // Buffer'a ekle (ilk byte atlanır)
            val copyLen = minOf(value.size - 1, packageBuffer!!.size - packageIndex)
            System.arraycopy(value, 1, packageBuffer, packageIndex, copyLen)
            packageIndex += copyLen
            Log.d(TAG, "Çoklu paket devam: $packageIndex / ${packageBuffer!!.size}")

            if (packageIndex >= packageBuffer!!.size) {
                val result = packageBuffer!!
                packageBuffer = null
                packageIndex = 0
                return result
            }
            return null
        }
    }

    // ── Sync timeout ──────────────────────────────────────────────────

    private fun resetSyncTimeout() {
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        syncTimeoutRunnable = Runnable {
            Log.i(TAG, "Sync tamamlandı — ${sleepRecords.size} uyku kaydı alındı")
            mainHandler.post {
                callback?.onSyncComplete(sleepRecords.toList())
            }
        }
        syncTimeoutRunnable?.let { mainHandler.postDelayed(it, SYNC_TIMEOUT_MS) }
    }

    // ── Cihaz tarama ──────────────────────────────────────────────────

    private suspend fun scanForDevice(targetMac: String?): BluetoothDevice? {
        val deferred = CompletableDeferred<BluetoothDevice?>()
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            callback?.onError("BLE tarayıcı kullanılamıyor")
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

                Log.d(TAG, "Cihaz bulundu: $name ($mac)")

                if (targetMac != null) {
                    if (mac.equals(targetMac, ignoreCase = true)) {
                        scanner.stopScan(this)
                        deferred.complete(device)
                    }
                } else {
                    // HK11 cihazlarını isme göre bul
                    if (name.contains("HK11", ignoreCase = true) ||
                        name.contains("Ultra", ignoreCase = true)) {
                        scanner.stopScan(this)
                        deferred.complete(device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Tarama başarısız: $errorCode")
                deferred.complete(null)
            }
        }

        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.i(TAG, "Cihaz taranıyor...")

        // 15 saniye timeout
        val result = withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                deferred.await()
            }
        }

        scanner.stopScan(scanCallback)
        return result
    }
}
