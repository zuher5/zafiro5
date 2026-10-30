# Workflow Zafiro5

Dokumen ini meringkas implementasi saat ini agar AI agent lain bisa langsung lanjut tanpa mempelajari dari nol.

## Repo dan branch

- Remote: `https://github.com/zuher5/zafiro5`
- Branch aktif: `feat/swipe-session-actions`
- Package id / applicationId: `com.niki914.zafiro` (sesuai instruksi maintainer)
- App name: `Zafiro`
- Sinkronisasi upstream: merged upstream `v16-1.9.0` (commit `a6256787`)

## CI

- Debug APK workflow:
  ```text
  .github/workflows/build.yml
  ```
- Jadikan `Zafiro-debug-apk` artifact.
- Build tidak dilakukan lokal; pakai GitHub Actions.

## Swipe session actions

File:

```text
app/src/main/java/com/niki914/zafiro/app/ui/content/ConversationHistoryPageContent.kt
```

Perilaku:

- Swipe kanan → rename session.
- Swipe kiri → delete session.
- Menggunakan `SwipeToDismissBox` dan tetap terintegrasi dengan Search Bar upstream.

## Free model presets (pi-bansos v0.7.2)

Provider yang tersedia di UI:

1. `KiloCode Free`

```text
provider id: kilo-free
endpoint: https://api.kilo.ai/api/gateway/chat/completions
model: kilo-auto/free
protocol: openai-chat-completions
apiKey: kosong
```

2. `OpenCode Free`

```text
provider id: opencode-zen-free
endpoint: https://opencode.ai/zen/v1/chat/completions
model: mimo-v2.6-flash-free
protocol: openai-chat-completions
apiKey: kosong
fingerprint: <env> marker injected + weekly session re-mint
```

3. `OpenCode PI`

```text
provider id: opencode-pi
endpoint: http://127.0.0.1:18080/v1/chat/completions
model: mimo-v2.6-flash-free
protocol: openai-chat-completions
apiKey: kosong
```

`OpenCode PI` mengasumsikan `pi` + `pi-bansos` dijalankan di Termux/device yang sama. Ini fallback bila native OpenCode bermasalah.

## Implementasi native OpenCode (v0.7.2 Gate Alignment)

File:

```text
agent-runtime/src/main/java/com/niki914/zafiro/chat/OpenCodeFreeSupport.kt
```

Tujuan: request native Zafiro memenuhi gate free-tier OpenCode Zen terbaru tanpa perantara.

Yang diterapkan:

- **`<env>` fingerprint marker**:
  - OpenCode gate menolak prompt tanpa blok `<env>` dengan kode error `429 FreeUsageLimitError` (menyamar sebagai limit kuota).
  - Diinjeksi otomatis ke system message / instructions.
- **Session freshness**:
  - `ses_` timestamp divalidasi OpenCode (menolak usia > 30 hari).
  - Otomatis di-remint tiap 7 hari (`SESSION_MAX_AGE_MS = 7d`).
- **Header OpenCode**:
  - `User-Agent: opencode/1.18.34 ...` (auto-fetch versi live npm)
  - `Authorization: Bearer public`
  - `x-opencode-client: cli`
  - `x-opencode-project` (hex hash)
  - `x-opencode-session` / `x-opencode-session-id` / `x-session-affinity`
  - `b3`, `traceparent`
  - `Accept: text/event-stream`
- **Body Transform**:
  - `stream: true`
  - Fingerprint tool schemas: `read` dan `shell`
  - Tool nama bertabrakan di-alias `*_pi` lalu dipulihkan di respons.
  - chat completions: `tool_choice: "none"`
  - Responses API:
    - `store: false`
    - normalisasi `max_output_tokens`
    - sanitasi `encrypted_content`
    - inject tools Responses bentuk OpenAI Responses

- Wrapper protocol:
  ```kotlin
  OpenCodeFreeProtocol
  ```
  membungkus protocol OpenAI chat/responses dan rewrite body sebelum dikirim.

## Mapping dari pi-bansos

Referensi lokal:

```text
.Projects/pi-bansos/extensions/index.ts
```

`pi-bansos` tidak dipasang di Android. Yang dipakai adalah logika fingerprint-nya:

- upstream OpenCode Zen,
- upstream KiloCode gateway,
- OpenCode headers,
- tool fingerprint,
- body rewrite,
- fallback relay.

Untuk KiloCode, Zafiro bisa langsung memanggil gateway tanpa pi-bansos.

## Provider / saved config

File:

```text
app/src/main/java/com/niki914/zafiro/app/ui/model/ProviderSpec.kt
app/src/main/java/com/niki914/zafiro/repo/LlmConfigsSettingsCodec.kt
agent-runtime/src/main/java/com/niki914/zafiro/settings/model/RuntimeSettingsModels.kt
```

`ProviderSpec.allowsEmptyApiKey = true` dipakai untuk free provider agar API key boleh kosong.

## Ikon provider

- `KiloCode Free` → `R.drawable.kilo`, hijau.
- `OpenCode Free` → `R.drawable.opencode_free`, oranye.
- `OpenCode PI` → `R.drawable.opencode_pi`, cappuccino mocha.

## Fallback yang harus tetap ada

Jangan hapus provider fallback:

```text
OpenCode PI
```

Kalau native:

```text
OpenCode Free
```

gagal, user cukup ganti konfigurasi ke:

```text
OpenCode PI
```

dengan `pi` + `pi-bansos` jalan di Termux.

## Langkah menambah fitur berikutnya

1. Baca `workflow.md`.
2. Baca `PRD_FREE_MODELS.md`.
3. Jangan menghapus provider fallback kecuali diminta eksplisit.
4. Build hanya dengan GitHub Actions.
5. Sebelum mengubah `OpenCodeFreeSupport.kt`, cek logic `pi-bansos` di `.Projects/pi-bansos`.
