package com.example.hk11ultra3.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/**
 * HK11 Ultra3 (Nordic UART Service) BLE protokol sabitleri ve paket oluşturucu.
 *
 * WearFit Pro reverse engineering sonucu elde edilmiştir.
 * Paket adı: com.wakeup.howear
 */
object BleProtocol {

    // ── BLE UUIDs (Nordic UART Service) ──────────────────────────────
    val SERVICE_UUID = java.util.UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val WRITE_CHAR_UUID = java.util.UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val NOTIFY_CHAR_UUID = java.util.UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── Protokol sabitleri ───────────────────────────────────────────
    const val HEADER_AB = 0xAB.toByte()   // Ana komut header'ı
    const val HEADER_EA = 0xEA.toByte()   // Yan komut header'ı
    const val PREFIX = 0xFF.toByte()      // Sabit prefix

    // Komut kodları (datas[4])
    const val CMD_SINGLE_MEASURE = 0x31    // 49  - tek seferlik ölçümler
    const val CMD_ONCE_KEY = 0x32          // 50  - eski protokol
    const val CMD_CURRENT_DATA = 0x51      // 81  - anlık veri (adım, saatlik, ölçüm)
    const val CMD_SLEEP_DATA = 0x52        // 82  - uyku verisi
    const val CMD_SPORT_DATA = 0xB7        // 183 - spor verisi

    // Alt komutlar (datas[5])
    const val SUB_STEP = 8
    const val SUB_HR_MEASURE = 17
    const val SUB_BO_MEASURE = 18
    const val SUB_TEMP_MEASURE = 19
    const val SUB_BP_MEASURE = 20
    const val SUB_HOURLY_1 = 32
    const val SUB_HOURLY_2 = 33
    const val SUB_SLEEP_APNEA = 11
    const val SUB_IMM_MEASURE = 24

    // ── Komut oluşturucular ──────────────────────────────────────────

    /** Paket header'ı + payload uzunluğu + prefix */
    private fun makePacket(command: Byte, subcommand: Byte, payload: ByteArray): ByteArray {
        val totalLen = payload.size + 2  // +1 prefix, +1 command
        return ByteBuffer.allocate(payload.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_AB)
            .putShort(totalLen.toShort())
            .put(PREFIX)
            .put(command)
            .put(subcommand)
            .put(payload)
            .array()
    }

    /** EA header'lı paket */
    private fun makeEaPacket(command: Byte, subcommand: Byte, payload: ByteArray): ByteArray {
        val totalLen = payload.size + 2
        return ByteBuffer.allocate(payload.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_EA)
            .putShort(totalLen.toShort())
            .put(PREFIX)
            .put(command)
            .put(subcommand)
            .put(payload)
            .array()
    }

    // ── Handshake komutları ──────────────────────────────────────────

    /**
     * Telefon sistemini bildir (Android).
     * Paket: AB 00 05 FF 20 80 01 02
     */
    fun sendPhoneSystem(): ByteArray =
        makePacket(0x20.toByte(), 0x80.toByte(), byteArrayOf(0x01, 0x02))

    /**
     * Bağlantı istek tipini bildir.
     * @param connectType 1 = normal bağlantı
     * @param deviceModel telefon modeli (max 12 karakter)
     * @param uid kullanıcı ID (opsiyonel)
     */
    fun sendConnectRequestType(
        connectType: Int = 1,
        deviceModel: String = android.os.Build.MODEL.take(12),
        uid: Int = 0
    ): ByteArray {
        val modelBytes = deviceModel.toByteArray(Charsets.UTF_8)
        val uidStr = uid.toString()
        val uidBytes = uidStr.toByteArray(Charsets.UTF_8)

        val payload = ByteBuffer.allocate(3 + modelBytes.size + 1 + uidBytes.size)
            .put(0xE0.toByte())
            .put(0x80.toByte())
            .put(connectType.toByte())
            .put(modelBytes.size.toByte())
            .put(modelBytes)
            .put(uidBytes.size.toByte())
            .put(uidBytes)
            .array()

        val totalLen = payload.size + 1  // +1 prefix
        return ByteBuffer.allocate(payload.size + 3)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_AB)
            .putShort(totalLen.toShort())
            .put(PREFIX)
            .put(payload)
            .array()
    }

    // ── Veri senkronizasyon komutları ────────────────────────────────

    /**
     * Genel veri senkronizasyonunu başlat (SYNC_DATA_ORDER2).
     * @param timeInMillis başlangıç zamanı, 0 = tüm veriyi al
     */
    fun setSyncDataHr(timeInMillis: Long = 0L): ByteArray {
        val cal = Calendar.getInstance()
        val payload = ByteArray(13)
        payload[0] = 0xCA.toByte()
        payload[1] = 0x80.toByte()

        if (timeInMillis == 0L) {
            payload[3] = (cal.get(Calendar.YEAR) - 2000).toByte()
            payload[4] = (cal.get(Calendar.MONTH) + 1).toByte()
            payload[5] = cal.get(Calendar.DAY_OF_MONTH).toByte()
            // saat/dakika/saniye 0
        } else {
            cal.timeInMillis = timeInMillis
            val cal2 = Calendar.getInstance().also { it.timeInMillis = timeInMillis }
            // Başlangıç aralığı
            payload[3] = (cal.get(Calendar.YEAR) - 2000).toByte()
            payload[4] = (cal.get(Calendar.MONTH) + 1).toByte()
            payload[5] = cal.get(Calendar.DAY_OF_MONTH).toByte()
            // Bitiş aralığı
            payload[8] = (cal2.get(Calendar.YEAR) - 2000).toByte()
            payload[9] = (cal2.get(Calendar.MONTH) + 1).toByte()
            payload[10] = cal2.get(Calendar.DAY_OF_MONTH).toByte()
            payload[11] = cal2.get(Calendar.HOUR_OF_DAY).toByte()
            payload[12] = cal2.get(Calendar.MINUTE).toByte()
        }
        return makePacket(0xCA.toByte(), 0x80.toByte(), payload)
    }

    /**
     * Uyku verisi talebi (SYNC_SLEEP_DATA_ORDER).
     * @param timeInMillis hangi gunun uyku verisi (genelde dun)
     */
    fun syncSleepData(timeInMillis: Long): ByteArray {
        val cal = Calendar.getInstance().also { it.timeInMillis = timeInMillis }
        val payload = byteArrayOf(
            0xAC.toByte(),
            0x80.toByte(),
            0x00,
            (cal.get(Calendar.YEAR) - 2000).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte()
        )
        return makePacket(0xAC.toByte(), 0x80.toByte(), payload)
    }

    /**
     * Uyku apnesi verisi talebi.
     * WearFit Pro DataSyncMgr.startSyncData() icinde cagrilir.
     */
    fun syncSleepApnea(timeInMillis: Long): ByteArray {
        val cal = Calendar.getInstance().also { it.timeInMillis = timeInMillis }
        val payload = byteArrayOf(
            0xAD.toByte(),
            0x80.toByte(),
            0x00,
            (cal.get(Calendar.YEAR) - 2000).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte()
        )
        return makePacket(0xAD.toByte(), 0x80.toByte(), payload)
    }

    /**
     * GPS verisi talebi.
     * WearFit Pro DataSyncMgr.startSyncData() icinde cagrilir.
     */
    fun syncGpsData(time: Int = 0): ByteArray {
        val payload = byteArrayOf(
            0xCB.toByte(),
            0x80.toByte(),
            0x00,
            (time shr 24).toByte(),
            (time shr 16).toByte(),
            (time shr 8).toByte(),
            (time and 0xFF).toByte()
        )
        return makePacket(0xCB.toByte(), 0x80.toByte(), payload)
    }

    // ── Saate bildirim gönderme ──────────────────────────────────────

    /**
     * Saate metin mesajı/bildirim gönderir.
     * @param title başlık (kısa)
     * @param content içerik
     */
    fun sendMessageNotification(title: String, content: String): ByteArray {
        val fullText = title + content
        val fullBytes = fullText.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val titleLen = titleBytes.size

        // Payload: [0xE2] [0x80] [0x02] [titleLen] [title+content UTF-8]
        val innerPayload = ByteBuffer.allocate(3 + 1 + fullBytes.size)
            .put(0xE2.toByte())
            .put(0x80.toByte())
            .put(0x02.toByte())
            .put(titleLen.toByte())
            .put(fullBytes)
            .array()

        val totalLen = innerPayload.size + 1  // +1 prefix
        return ByteBuffer.allocate(innerPayload.size + 3)
            .order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_AB)
            .putShort(totalLen.toShort())
            .put(PREFIX)
            .put(innerPayload)
            .array()
    }

    // ── Paket parse yardımcıları ─────────────────────────────────────

    /**
     * 2 byte'lık big-endian unsigned integer okur.
     */
    fun bytesToInt(bytes: ByteArray, offset: Int, length: Int = 2): Int {
        var result = 0
        for (i in 0 until length) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    /**
     * Byte dizisini unsigned integer listesine çevirir.
     */
    fun bytesToArrayList(bytes: ByteArray): List<Int> =
        bytes.map { it.toInt() and 0xFF }

    /**
     * datas listesinden timestamp oluşturur.
     * datas[6]=yıl, datas[7]=ay, datas[8]=gün, datas[9]=saat, datas[10]=dakika
     */
    fun datasToTimestamp(datas: List<Int>): Long {
        val year = datas[6] + 2000
        val month = datas[7]
        val day = datas[8]
        val hour = datas[9]
        val minute = datas[10]
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
