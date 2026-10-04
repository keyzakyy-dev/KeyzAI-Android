# KeyzAI — Aplikasi Android Native

Aplikasi Android native (Kotlin + Jetpack Compose) untuk KeyzAI (https://chat.keyzakyy.com).
UI ditulis ulang dari nol; berbicara langsung ke Cloudflare Worker yang sama
dengan versi web — **tanpa perubahan backend**.

## Fitur

- **Login Google** via Credential Manager → ID token ditukar ke session JWT
  worker (`POST /api/auth/google`), persis seperti web.
- **Daftar percakapan**: cari, pin, ubah nama, hapus, chat baru.
- **Layar chat**: streaming jawaban real-time (SSE), render markdown
  (code block + tombol salin, list, tabel, quote, link), kartu pilihan
  interaktif KeyzAI, indikator "Berpikir…", tombol Stop.
- **Pohon pesan** (port dari `src/state/tree.js` web): edit pesan dan
  "buat ulang" membuat cabang baru — navigasi ‹ 1 / 2 ›, riwayat lama
  tidak hilang dan tetap sinkron dengan web.
- **Pilih model**: Qwen 3.8 Flash, DeepSeek V4 Flash, Atria Dawn Preview.
- **Judul otomatis** untuk percakapan baru (seperti web).
- **Pengaturan**: model default, nama tampilan, hapus semua percakapan, keluar.
- Riwayat tersimpan di server → ganti perangkat/web tetap sinkron.

## Struktur

```
app/src/main/java/com/keyzai/app/
├── MainActivity.kt            # entry point
├── KeyzApplication.kt         # AppContainer (DI sederhana)
├── data/
│   ├── Models.kt              # User, Conversation, ChatMessage (kontrak JSON worker)
│   ├── ChatTree.kt            # logika message tree (port tree.js)
│   ├── KeyzApi.kt             # OkHttp client + SSE streaming
│   ├── ChatRepository.kt      # orkestrasi kirim/sync (port useChatStream)
│   ├── SessionStore.kt        # DataStore: token, user, model
│   └── GoogleAuth.kt          # Credential Manager (Google ID token)
└── ui/
    ├── theme/Theme.kt
    ├── markdown/MarkdownText.kt  # renderer markdown + kartu opsi
    ├── login/LoginScreen.kt
    ├── home/HomeScreen.kt
    ├── chat/ChatScreen.kt
    ├── settings/SettingsScreen.kt
    └── AppNav.kt
```

## Cara build

Butuh: JDK 17, Android SDK (platform 35, build-tools 35), Gradle 8.10+.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk   # atau ANDROID_SDK_ROOT
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

# (sekali saja) install platform & build-tools:
sdkmanager "platforms;android-35" "build-tools;35.0.0"

cd keyzai-android
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Untuk rilis Play Store: buat keystore, isi `keystore.properties`,
lalu `gradle bundleRelease` (AAB).

## Catatan penting

1. **Login Google di Android**: app meminta Google ID token dengan
   audience = Web OAuth client ID KeyzAI
   (`227191802214-klcplrl89607it7obq9ne3ren73o9th9.apps.googleusercontent.com`),
   sehingga worker menerimanya tanpa perubahan. Jika Google melempar
   error developer saat login, daftarkan SHA-1 sertifikat signing aplikasi
   di Google Cloud Console → Credentials → buat OAuth client ID tipe
   **Android** dengan package name `com.keyzai.app`.
   Ambil SHA-1 debug: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`.

2. **Backend**: base URL di `KeyzApi.BASE_URL`. Tidak ada endpoint baru
   yang dibutuhkan — semua sudah ada di worker.

3. **Belum di-port** (bisa menyusul): PRD builder, grafik token,
   export/backup percakapan, pengumuman. Fokus v1 = chat inti.
