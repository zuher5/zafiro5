# PRD · Free Model Presets for Zafiro5

Sumber referensi: `https://github.com/mannnrachman/pi-bansos` (lokal di `.Projects/pi-bansos`).

## Temuan pi-bansos

`pi-bansos` adalah extension untuk Pi CLI (`@earendil-works/pi-coding-agent`), bukan app Android. Fungsinya:

- Menyediakan provider lokal `bansos` melalui proxy OpenAI-compatible di `127.0.0.1:18080`.
- Dua upstream free model:
  - **OpenCode Zen**: `https://opencode.ai/zen`
  - **KiloCode Gateway**: `https://api.kilo.ai/api/gateway/chat/completions`
- OpenCode Zen butuh fingerprint header/session; Kilo keyless (200 req/hr/IP).
- Model free dideteksi dari `:free`, `-free`, dan katalog upstream.

## Mapping ke Zafiro

| Pi-bansos upstream | Endpoint langsung / dirubuh | Zafiro `ProviderSpec` | `SavedLlmConfig` | `LlmProtocol.wireId` | Catatan |
|---|---|---|---|---|---|
| KiloCode gateway | `https://api.kilo.ai/api/gateway/chat/completions` | `kilo-free` | provider=`kilo-free`, endpoint=chat/completions, model=`kilo-auto/free` atau `*:free`, apiKey="" | `openai-chat-completions` | Cocok untuk preset langsung; apiKey kosong agar Okia tidak kirim `Authorization` |
| OpenCode Zen chat models | `https://opencode.ai/zen/v1/chat/completions` | `opencode-zen-free` atau lewat proxy `bansos` | provider=`opencode`, endpoint=`http://127.0.0.1:18080/v1/chat/completions`, model=`opencode/mimo-v2.5-free`, apiKey="" | `openai-chat-completions` | Perlu UA/project/session fingerprint; paling aman pakai proxy `pi-bansos` |
| OpenCode Zen responses models | `https://opencode.ai/zen/v1/responses` | `opencode-zen-free-responses` | protocol `openai-responses`, model=`opencode/muse-spark-1.3-contributor-free` | `openai-responses` | Flutter/Android harus handle endpoint responses; via proxy `pi-bansos` langsung bisa |
| Local proxy bansos | `http://127.0.0.1:18080/v1` | `bansos` / `free-proxy` | endpoint=URL lokal, apiKey="" | `openai-chat-completions` | Jalankan `pi-bansos` di laptop/server; Android butuh Akses jarak jauh atau Tailscale |

## Daftar model free yang bisa di-preset

### KiloCode / OpenCode upstream free models

- `kilo-auto/free`
- `stepfun/step-3.7-flash:free`
- `nvidia/nemotron-3-ultra-550b-a55b:free`
- `nvidia/nemotron-3-super-120b-a12b:free`
- `dots-studio/dots-3-note-preview:free`
- `cohere/north-mini-code:free`
- `poolside/laguna-xs-2.1:free`
- `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`
- `openrouter/free`
- `nvidia/nemotron-3.5-lightning:free`
- `nvidia/nemotron-3.5-content-safety:free`
- `inclusionai/ling-3.0-flash-sante:free`
- `apodex/apodex-1.1-mini:free`
- `liquid/lfm-2.5-2.6b:free`
- `poolside/laguna-s-2.1:free`
- `thinkingmachines/inkling-small:free`
- `qwen/qwen3.8-27b:free`
- OpenCode: `mimo-v2.5-free`, `mimo-v2.6-flash-free`, `space-bunny-free`, `nemotron-3-ultra-free`, `nemotron-3.5-lightning-free`, `ling-3.1-flash-free`, `fledge-alpha-free`, `longcat-2.5-preview-free`, `muse-spark-1.2-contributor-free`, `muse-spark-1.3-contributor-free`

## Vercel Relay

Relay Vercel di `pi-bansos` bukan endpoint OpenAI-compatible langsung. Itu adalah **egress proxy pass-through**:

- Client mengirim request ke Vercel relay.
- Header `x-relay-target: https://opencode.ai` atau `https://api.kilo.ai`
- Header `x-relay-path: /v1/chat/completions`
- Vercel meneruskan request ke target tersebut.

Zafiro `SavedLlmConfig.proxy` adalah HTTP proxy biasa, bukan Vercel relay. Untuk memakai relay Vercel langsung dari Zafiro, tambahkan dukungan custom header di konfigurasi LLM, mis.:

```json
{
  "endpoint": "https://your-relay.vercel.app",
  "headers": {
    "x-relay-target": "https://api.kilo.ai",
    "x-relay-path": "/api/gateway/chat/completions"
  }
}
```

Sedangkan `proxy` hanya untuk HTTP proxy, tidak bisa otomatis menaruh `x-relay-target`.

## Cara pakai di Zafiro (setelah preset ada)

### 1. KiloCode free langsung

Tambah/pilih provider `kilo-free`, isi seperti ini:

```json
{
  "name": "KiloCode Free",
  "provider": "kilo-free",
  "endpoint": "https://api.kilo.ai/api/gateway/chat/completions",
  "apiKey": "",
  "model": "kilo-auto/free",
  "protocol": "openai-chat-completions",
  "supportsImages": false,
  "proxy": ""
}
```

Contoh model lain: `qwen/qwen3.8-27b:free`, `thinkingmachines/inkling-small:free`, `stepfun/step-3.7-flash:free`.

### 2. OpenCode free via proxy lokal/Termux

Jalankan `pi-bansos` di device yang sama dengan Zafiro:

```bash
pi install git:github.com/mannnrachman/pi-bansos
pi
```

Konfigurasi Zafiro:

```json
{
  "name": "OpenCode Free via bansos",
  "provider": "bansos-proxy",
  "endpoint": "http://127.0.0.1:18080/v1/chat/completions",
  "apiKey": "",
  "model": "mimo-v2.5-free",
  "protocol": "openai-chat-completions",
  "supportsImages": false,
  "proxy": ""
}
```

Untuk model Responses seperti `muse-spark-1.3-contributor-free`:

```json
{
  "endpoint": "http://127.0.0.1:18080/v1/responses",
  "model": "muse-spark-1.3-contributor-free",
  "protocol": "openai-responses"
}
```

### 3. OpenCode free via Vercel relay

Butuh dukungan custom headers di Zafiro. Setelah ada:

```json
{
  "name": "OpenCode Free via Vercel relay",
  "provider": "opencode-zen-free",
  "endpoint": "https://YOUR-RELAY.vercel.app",
  "apiKey": "",
  "model": "mimo-v2.5-free",
  "protocol": "openai-chat-completions",
  "headers": {
    "x-relay-target": "https://opencode.ai",
    "x-relay-path": "/v1/chat/completions"
  }
}
```

Untuk KiloCode via relay:

```json
{
  "x-relay-target": "https://api.kilo.ai",
  "x-relay-path": "/api/gateway/chat/completions"
}
```

### 4. Verifikasi

- Pilih konfigurasi baru.
- Kirim prompt `Reply with exactly OK.`
- Kalau 403/429, kemungkinan quota/relay fingerprint; coba ganti model atau toggle relay.

## Perubahan di Zafiro

1. Tambah `ProviderSpec`:
   - `KiloFreeSpec`: `id="kilo-free"`, officialEndpoint=`https://api.kilo.ai/api/gateway/chat/completions`, defaultProtocol=`openai-chat-completions`, supportsImages=false, allowsCustomEndpoint=true
   - `OpenCodeFreeSpec`: `id="opencode-zen-free"`, officialEndpoint=`http://127.0.0.1:18080/v1/chat/completions` (default bila proxy lokal), atau upstream langsung jika sudah support fingerprint headers
   - `BansosProxySpec`: `id="bansos-proxy"`, endpoint=`http://127.0.0.1:18080/v1/chat/completions`

2. `SavedLlmConfig` sudah cukup, tetapi perlu contoh model/preset:
   ```kotlin
   SavedLlmConfig(
     id = generated,
     name = "KiloCode Free",
     provider = "kilo-free",
     endpoint = "https://api.kilo.ai/api/gateway/chat/completions",
     apiKey = "",
     model = "kilo-auto/free",
     protocol = "openai-chat-completions",
     supportsImages = false,
     proxy = "",
   )
   ```

3. `OkiaConfig.headers` sudah mendukung custom header; bila ingin OpenCode Zen langsung, tambah `headers` map ke `SavedLlmConfig` (mis. `headersJson`) dengan UA/project/session. Jika tidak, gunakan proxy `pi-bansos`.

4. Tambah `freeModelPresets` di `ProviderSpec` atau konstanta terpisah:
   ```kotlin
   data class FreeModelPreset(
     val providerId: String,
     val modelId: String,
     val label: String,
     val free: Boolean,
     val quotaNote: String? = null,
   )
   ```

5. UI: tambah opsi satu ketuk “Tambah konfigurasi free model” pada Configure page.

## Batasan

- OpenCode free models tidak langsung kompatibel tanpa proxy fingerprint.
- KiloCode free cukup menambah endpoint + model saja.
- Cache/quota berubah cepat; preset hanya contoh.
