# Build Android Dengan GitHub Actions

Panduan ini menjelaskan langkah lengkap dari awal sampai APK berhasil diunduh dari GitHub Actions.

## 1. Siapkan Project Di GitHub

1. Buat repository baru di GitHub.
2. Upload atau push source code project ini ke repository tersebut.
3. Pastikan file workflow sudah ada:
   - `.github/workflows/android-build.yml`

Setelah source code masuk ke GitHub, Anda bisa build APK lewat tab `Actions`.

## 2. Tentukan Jenis Build

Ada 2 kemungkinan:

- `Debug APK`
  Cocok untuk testing cepat.
  Tidak perlu signing release sendiri.

- `Release APK Signed`
  Cocok untuk distribusi yang lebih rapi.
  Perlu keystore dan GitHub Secrets.

Jika Anda hanya ingin coba build dulu, Anda bisa langsung ke langkah `5. Jalankan Workflow`.

Jika Anda ingin `release APK` yang signed, ikuti langkah `3` dan `4`.

## 3. Buat Keystore Android

Keystore adalah file untuk menandatangani APK release.

Jalankan di komputer Anda:

```bash
keytool -genkeypair \
  -v \
  -keystore your-release-key.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias webprintkey
```

Lalu Anda akan diminta mengisi:

- password keystore
- nama
- organisasi
- kota
- provinsi
- kode negara

Hasilnya adalah file:

```text
your-release-key.jks
```

Simpan file ini baik-baik. File ini penting untuk update aplikasi di masa depan.

## 4. Isi GitHub Secrets Untuk Signing

Workflow ini mendukung signing release lewat 4 secret berikut:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

### 4.1 Ubah file keystore ke Base64

Di Linux:

```bash
base64 -w 0 your-release-key.jks
```

Di macOS:

```bash
base64 your-release-key.jks | tr -d '\n'
```

Di Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your-release-key.jks"))
```

Output-nya akan sangat panjang. Copy semua hasil itu. Nilai itulah yang dipakai untuk:

```text
ANDROID_KEYSTORE_BASE64
```

### 4.2 Arti masing-masing secret

`ANDROID_KEYSTORE_BASE64`

- isi dengan hasil encode base64 dari file `your-release-key.jks`

`ANDROID_KEYSTORE_PASSWORD`

- isi dengan password saat Anda membuat file keystore

`ANDROID_KEY_ALIAS`

- isi dengan alias key saat membuat keystore
- contoh pada command di atas: `webprintkey`

`ANDROID_KEY_PASSWORD`

- isi dengan password key
- sering kali sama dengan password keystore, tapi bisa juga berbeda

### 4.3 Cara memasukkan secret ke GitHub

1. Buka repository GitHub Anda.
2. Klik `Settings`.
3. Klik `Secrets and variables`.
4. Klik `Actions`.
5. Klik `New repository secret`.
6. Tambahkan satu per satu:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## 5. Jalankan Workflow Build

1. Buka repository GitHub.
2. Klik tab `Actions`.
3. Pilih workflow `Android Build`.
4. Klik `Run workflow`.
5. Pilih branch yang ingin dibuild.
6. Klik `Run workflow`.

Atau cukup push ke branch `main` atau `master`, karena workflow juga otomatis jalan pada `push`.

## 6. Tunggu Proses Build

GitHub Actions akan menjalankan langkah berikut:

1. checkout source code
2. install Java 17
3. install Gradle 8.7
4. decode keystore jika secrets tersedia
5. build `debug APK`
6. build `release APK`
7. upload artifact

Jika signing secret tidak diisi:

- `debug APK` tetap bisa dibuild
- `release APK` akan terbentuk tanpa signing khusus

Jika signing secret diisi benar:

- `release APK` akan signed otomatis

## 7. Download APK Hasil Build

1. Buka tab `Actions`.
2. Klik workflow run yang sudah selesai.
3. Scroll ke bagian `Artifacts`.
4. Klik artifact bernama:

```text
android-apk
```

5. Download file zip artifact.
6. Extract file zip tersebut.

Biasanya hasilnya ada di:

```text
app-debug.apk
app-release.apk
```

## 8. Install APK Ke Android

Untuk testing:

- install `app-debug.apk`, atau
- install `app-release.apk` jika sudah signed

Jika install manual:

1. pindahkan APK ke device Android
2. buka file APK
3. izinkan install dari sumber tersebut jika diminta

## 9. Jika Build Gagal

Beberapa penyebab umum:

`ANDROID_KEYSTORE_BASE64` salah

- biasanya karena hasil base64 terpotong
- solusi: encode ulang dan paste ulang

`ANDROID_KEY_ALIAS` salah

- alias harus sama persis dengan alias saat membuat keystore

`ANDROID_KEYSTORE_PASSWORD` atau `ANDROID_KEY_PASSWORD` salah

- jika password salah, signing release akan gagal

`base_url` masih `localhost`

- ini tidak memblok build, tapi aplikasi di Android nanti tidak akan terhubung ke server di laptop/PC
- gunakan IP LAN server saat konfigurasi aplikasi

## 10. Rekomendasi Praktis

- Untuk uji awal, build dulu tanpa signing dan ambil `debug APK`.
- Setelah alur aplikasi benar, baru siapkan keystore dan secrets untuk `release APK`.
- Simpan file `.jks`, alias, dan password di tempat aman. Jangan hilang, karena akan dipakai untuk update aplikasi berikutnya.
