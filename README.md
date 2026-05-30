# Web Print Bridge Android

Client Android Kotlin berbasis `ForegroundService` untuk POS web yang polling job print dari endpoint `print-queue`, lalu mencetak ke printer ESC/POS TCP atau Bluetooth tanpa activity harus terbuka di layar.

## Fitur utama

- Service berjalan di background dengan notifikasi tetap.
- Bisa auto-start saat boot selesai atau setelah aplikasi di-update.
- Bisa import konfigurasi langsung dari URL seperti:
  - `http://localhost:8000/print-client.html?...&base_url=...&token=...&outlet_id=...&type=cashier&autostart=1`
- Polling job print dari backend Laravel `GET /api/print-queue/cashier` atau `GET /api/print-queue/kitchen`.
- Mendukung printer ESC/POS via TCP/LAN dan Bluetooth Classic/SPP.
- Menandai job selesai ke `POST /api/print-queue/{id}/done?token=...`.
- Menandai job gagal ke `POST /api/print-queue/{id}/fail?token=...`.
- Mencetak payload receipt dan kitchen ticket langsung dari JSON queue POS, tanpa WebView.

## Import URL Client

Paste URL `print-client.html` lengkap ke field `URL print-client.html lengkap dengan query string`, lalu klik `Import Dari URL Client`.

Parameter yang akan diambil:

- `base_url`
- `token`
- `outlet_id`
- `type`
- `station_id`
- `autostart`

Catatan:

- Jika `base_url=http://localhost:8000`, pada Android `localhost` berarti perangkat Android itu sendiri, bukan laptop/PC Anda.
- Jika server POS berjalan di komputer lain dalam jaringan, gunakan IP LAN server, misalnya `http://192.168.1.10:8000`.

## Struktur endpoint Laravel POS Queue

- `GET /api/print-queue/cashier?token=...&outlet_id=...`
- `GET /api/print-queue/kitchen?token=...&outlet_id=...&station_id=...`
- `POST /api/print-queue/{jobId}/done?token=...`
- `POST /api/print-queue/{jobId}/fail?token=...`

Contoh implementasi backend ada di [laravel-example/README.md](/var/www/html/webprint/laravel-example/README.md).

## Format response `print-queue`

```json
{
  "success": true,
  "jobs": [
    {
      "id": 48,
      "type": "receipt",
      "paper_width": "58mm",
      "layout": {
        "meta_rows": [
          { "label": "No", "value": "INV-001" }
        ]
      }
    }
  ],
  "count": 1
}
```

Jika tidak ada job:

```json
{ "success": true, "jobs": [], "count": 0 }
```

## Catatan build

- Buka folder ini di Android Studio terbaru.
- Sinkronkan Gradle dan build APK.
- Minimum Android 8.0 (`minSdk 26`).
- Untuk mode Bluetooth, printer harus sudah dipairing dulu di Android Settings.
- Untuk Android 12+, izinkan `BLUETOOTH_CONNECT` dan `BLUETOOTH_SCAN` saat memilih printer.
- Untuk device tertentu, matikan battery optimization agar foreground service tidak dibunuh vendor ROM.

## Build Dengan GitHub

Repo ini sudah saya tambahkan workflow GitHub Actions di [.github/workflows/android-build.yml](/var/www/html/webprint/.github/workflows/android-build.yml:1).

Alurnya:

1. Push folder ini ke repository GitHub Anda.
2. Buka tab `Actions`.
3. Jalankan workflow `Android Build` atau push ke branch `main`/`master`.
4. Setelah selesai, download artifact `android-apk`.

Catatan:

- Workflow ini build `debug` dan `release` APK.
- Jika secret signing belum diisi, output `release` akan tetap ter-build tanpa signing khusus.
- Jika secret signing diisi, workflow akan membuat `release` APK yang signed.

## Signing Release Di GitHub

Jika Anda ingin build release yang siap install dari GitHub, tambahkan repository secrets berikut:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Cara isi `ANDROID_KEYSTORE_BASE64`:

```bash
base64 -w 0 your-release-key.jks
```

Lalu copy hasilnya ke GitHub repository:

`Settings` -> `Secrets and variables` -> `Actions` -> `New repository secret`

Catatan:

- File `signing.properties` akan dibuat otomatis saat workflow berjalan.
- Konfigurasi signing Gradle sudah disiapkan di [app/build.gradle.kts](/var/www/html/webprint/app/build.gradle.kts:1).
