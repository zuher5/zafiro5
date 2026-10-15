# Laporan Studi Proyek Zafiro5

Tanggal: 6 Oktober 2026

## 1. Gambaran Umum

- **Nama aplikasi**: Zafiro (fork/modifikasi dari Zafiro asli by niki914)
- **Package id**: `com.niki914.zafiro5` (package asli `com.niki914.zafiro` tetap)
- **Remote repo**: `https://github.com/zuher5/zafiro5`
- **Branch aktif**: `feat/swipe-session-actions`
- **Konsep**: Agent AI open-source di Android — mengerti layar, mengontrol device, Skills, MCP, memory, Shell & Python 3 native. Kontribusi utama lokal: takeover asisten suara via LSPosed (Binder + AgentRuntimeService), plus dukungan model LLM gratis.

## 2. Struktur Modul

| Modul | Fungsi |
|---|---|
| `app/` | Aplikasi utama: Compose UI, AgentRuntimeService, Xposed hooks |
| `business/` | Inti bisnis (kontrak & implementasi terpisah): api, agent, files, notification, permission, application |
| `agent-runtime/` | Runtime LLM: panggil LLM, eksekusi Tool/Skill/MCP, Python runtime |
| `xsettings/`, `store/` | Konfigurasi ringan, persistensi Room + file JSON (XRepo) |
| `ui-kit/`, `remote-view/` | Komponen Compose bersama, RemoteView lintas proses |
| `xposed-api/`, `xposed-runtime/` | Tipe event Xposed & hook base |
| `libs/` | logging, okia (runtime dasar), libterm |

## 3. Isi workflow.md (dokumen implementasi saat ini)

### Repo & CI
- Build hanya via GitHub Actions (`.github/workflows/build.yml`), artifact `Zafiro-debug-apk`. Tidak build lokal.

### Swipe session actions
- File: `app/.../ConversationHistoryPageContent.kt`
- Swipe kanan → rename session; swipe kiri → delete session.

### Free model presets (UI)
1. **KiloCode Free** — `provider id: kilo-free`, endpoint `https://api.kilo.ai/api/gateway/chat/completions`, protokol openai-chat-completions, apiKey kosong.
2. **OpenCode Free** — `provider id: opencode-zen-free`, endpoint `https://opencode.ai/zen/v1/chat/completions`, apiKey kosong.
3. **OpenCode PI** — `provider id: opencode-pi`, endpoint `http://127.0.0.1:18080/v1/chat/completions`, butuh `pi` + `pi-bansos` jalan di Termux.

### Implementasi native OpenCode
- File: `agent-runtime/.../chat/OpenCodeFreeSupport.kt`, `LLMController.kt`
- Tujuan: request native memenuhi gate free-tier OpenCode Zen dengan meniru fingerprint OpenCode resmi:
  - Header: `User-Agent: opencode/1.18.31`, `Authorization: Bearer public`, `x-opencode-client: desktop`, `x-opencode-project`, `x-opencode-session`, `x-opencode-request`, `b3`, `traceparent`, `Accept: text/event-stream`.
  - Body rewrite: `stream: true`, inject tools (bash, glob, grep, read, edit, write), chat completions `tool_choice: "none"`; Responses API: `store: false`, normalisasi `max_output_tokens`, sanitasi `encrypted_content`, tools versi Responses.
  - Wrapper `OpenCodeFreeProtocol` membungkus protokol OpenAI chat/responses.

### Mapping dari pi-bansos
- Referensi logika: `.Projects/pi-bansos/extensions/index.ts` (tidak dipasang di Android; hanya logika fingerprint-nya disalin). KiloCode bisa langsung tanpa pi-bansos.

### Provider / saved config
- File: `ProviderSpec.kt`, `LlmConfigsSettingsCodec.kt`, `RuntimeSettingsModels.kt`.
- `ProviderSpec.allowsEmptyApiKey = true` untuk provider gratis.

### Ikon provider
- `KiloCode Free` → `R.drawable.kilo` (hijau), `OpenCode Free` → `R.drawable.opencode_free` (oranye), `OpenCode PI` → `R.drawable.opencode_pi` (cappuccino mocha).

### Fallback wajib
- Jangan hapus provider `OpenCode PI` — bila native `OpenCode Free` gagal, user tinggal ganti config.

### Langkah fitur berikutnya (dari workflow.md)
1. Baca `workflow.md` → 2. Baca `PRD_FREE_MODELS.md` → 3. Jangan hapus provider fallback → 4. Build hanya GitHub Actions → 5. Sebelum ubah `OpenCodeFreeSupport.kt`, cek logika `pi-bansos`.

## 4. Git

- 5 commit terakhir: `docs: add implementation workflow`, `fix: native opencode free tool_choice and provider icons`, `fix: restore imports in llm controller`, `feat: native opencode free protocol wrapper`, `fix: use state provider spec in catalog fetch`.
- Working tree bersih.

## 5. Aturan kerja (dari AGENTS.md)

- Sulit (>6/10) + ROI rendah → diskusikan dulu; tanpa diminta, jangan commit; tanpa izin, jangan install APK.
- Commit & PR title Inggris, format `feat: did something`, ringkas.
- Arsitektur jangka panjang, tanpa kompatibilitas mundur, implementasi paling sederhana.
- DI via ServiceRegistry (`requireService<>()`), bukan di konstruktor.
- Build/test sinkron, jangan loop edit kecil-compile; `rg` bukan `grep -r`.
- Unit test hanya untuk state machine UI; rewrite test warisan saat refactor.
- Catatan TODO/proyek belum selesai tercantum di bagian akhir AGENTS.md (Replay tool, githooks lint, multi-select lampiran, dsb).
