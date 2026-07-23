# HK11 Ultra3 Wake

WearFit Pro'ya bağımlı olmadan, HK11 Ultra3 akıllı saatten BLE üzerinden
uyku verilerini okuyan ve uyanma tespit edince ilaç hatırlatıcısı kuran
Android uygulaması.

## Özellikler

- HK11 Ultra3 saate doğrudan BLE bağlantısı (Nordic UART Service)
- Uyku verilerini saatten okuma ve parse etme
- Uyanma zamanını otomatik tespit etme
- Ayarlanabilir gecikmeyle ilaç hatırlatıcısı
- Telefona bildirim + saate mesaj gönderme
- Webhook desteği (Discord, Slack, ntfy.sh, HTTP POST)
- Boot'ta otomatik senkronizasyon

## Gereksinimler

- Android 8.0+ (API 26)
- HK11 Ultra3 akıllı saat
- BLE desteği

## Kurulum

1. Android Studio'da projeyi açın
2. `app/build.gradle.kts` dosyasındaki bağımlılıkları sync'leyin
3. Uygulamayı telefonunuza yükleyin
4. Saatinizin MAC adresini Ayarlar → Saat Hakkında kısmından bulun
5. Uygulamayı açın, MAC adresini girin
6. "Kaç dakika sonra hatırlatsın" ayarını yapın (varsayılan: 120)
7. İsteğe bağlı olarak webhook URL'sini girin
8. "Senkronize Et" butonuna basın

## Webhook Formatı

Webhook URL'sine göre format otomatik seçilir:

| URL içeriği           | Format               |
|-----------------------|----------------------|
| `discord.com/api/webhooks` | Discord Embed   |
| `hooks.slack.com`     | Slack Block          |
| `ntfy.sh`             | ntfy.sh bildirimi    |
| Diğer                 | Genel JSON POST      |

Genel JSON formatı:
```json
{
  "event": "wake_up_detected",
  "wake_time": 1711724400,
  "wake_time_str": "2026-07-23 07:30",
  "reminder_time": 1711731600,
  "reminder_time_str": "2026-07-23 09:30",
  "total_sleep_minutes": 480,
  "device": "HK11 Ultra3"
}
```

## BLE Protokolü

WearFit Pro (`com.wakeup.howear`) reverse engineering ile çıkarılmıştır.

| Amaç                  | UUID                                     |
|-----------------------|------------------------------------------|
| Service               | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`  |
| Write (TX)            | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`  |
| Notify (RX)           | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`  |

### Uyku verisi okuma akışı

1. Bağlan → servisleri keşfet
2. Notify'ı enable et (CCCD)
3. `AB 00 05 FF 20 80 01 02` → telefon sistemini bildir
4. `AB 00 0E FF CA 80 00 Y M D ...` → sync başlat
5. `AB 00 07 FF AC 80 00 Y M D` → uyku verisi talep et
6. Saatten gelen `datas[0]==0xAB, datas[4]==0x52` paketlerini parse et

### Uyku kaydı formatı

```
datas[0]  = 0xAB (header)
datas[4]  = 0x52 (CMD_SLEEP_DATA)
datas[6]  = yıl - 2000
datas[7]  = ay
datas[8]  = gün
datas[9]  = saat
datas[10] = dakika
datas[11] = sleepType (1=hafif, 2=derin)
bytes[12..13] = sleepDuration (dakika, big-endian)
```

## Lisans

Kişisel kullanım için geliştirilmiştir.
