# Workflow Zafiro5

Dokumen ini meringkas implementasi saat ini agar AI agent lain bisa langsung lanjut tanpa mempelajari dari nol.

## Repo dan branch

- Remote: `https://github.com/zuher5/zafiro5`
- Branch aktif: `feat/swipe-session-actions`
- Package id: `com.niki914.zafiro5`
- App name: `Zafiro`
- Original package tetap: `com.niki914.zafiro`

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

## Free model presets

Provider yang tersedia di UI:

1. `KiloCode Free`

```text
provider id: kilo-free
endpoint: https://api.kilo.ai/api/gateway/chat/completions
protocol: openai-chat-completions
apiKey: kosong
```

2. `OpenCode Free`

```text
provider id: opencode-zen-free
endpoint: https://opencode.ai/zen/v1/chat/completions
protocol: openai-chat-completions
apiKey: kosong
```

3. `OpenCode PI`

```text
provider id: opencode-pi
endpoint: http://127.0.0.1:18080/v1/chat/completions
protocol: openai-chat-completions
apiKey: kosong
```

`OpenCode PI` mengasumsikan `pi` + `pi-bansos` dijalankan di Termux/device yang sama. Ini fallback bila native OpenCode bermasalah.

## Implementasi native OpenCode

File:

```text
agent-runtime/src/main/java/com/niki914/zafiro/chat/OpenCodeFreeSupport.kt
agent-runtime/src/main/java/com/niki914/zafiro/chat/LLMController.kt
```

Tujuan: request native Zafiro memenuhi gate free-tier OpenCode Zen.

Yang diterapkan:

- Header OpenCode:
  - `User-Agent: opencode/1.18.31`
  - `Authorization: Bearer public`
  - `x-opencode-client: desktop`
  - `x-opencode-project`
  - `x-opencode-session`
  - `x-opencode-request`
  - `b3`
  - `traceparent`
  - `Accept: text/event-stream`

- Transform request body:
  - `stream: true`
  - inject tools fingerprint OpenCode:
    - `bash`
    - `glob`
    - `grep`
    - `read`
    - `edit`
    - `write`
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
