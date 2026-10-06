# Laporan: OpenCode Free, KiloCode Free, dan Proxy

Tanggal: 2026-10-06

## Ringkasan

- `KiloCode Free` dapat dipakai langsung:
  - endpoint: `https://api.kilo.ai/api/gateway/chat/completions`
  - model: `kilo-auto/free`
  - hasil tes: `HTTP 200 OK`, membalas normal.

- `OpenCode Free` membutuhkan model dan fingerprint OpenCode terbaru:
  - endpoint: `https://opencode.ai/zen/v1/chat/completions`
  - model lama `mimo-v2.5-free` sudah deprecated
  - model yang dipakai: `mimo-v2.6-flash-free`
  - dengan header OpenCode yang benar, endpoint balas `HTTP 200 OK`.
  - jika fingerprint salah atau model deprecated, endpoint balas `403 FreeTierError`/`410 ModelDeprecated`.

- Menu `proxy` di Zafiro tidak untuk URL Vercel relay.
  - Field `proxy` saat ini membaca HTTP/SOCKS proxy normal.
  - `https://vercel-relay-xcihuy.vercel.app/` butuh header khusus `x-relay-target`.
  - Jangan isi URL Vercel relay di field proxy.

## Perubahan repository

- Commit: `bcff0e05 fix: update opencode free user agent and model ids`
- GitHub Actions run: `37478995657`
- Status: `success`
- APK debug: artifact run tersebut.

## Rekomendasi

1. Untuk demo stabil: gunakan `KiloCode Free`.
2. Untuk `OpenCode Free`, gunakan build commit `bcff0e05` atau lebih baru, lalu pilih model `mimo-v2.6-flash-free`.
3. Jika OpenCode Free masih 403, gunakan `OpenCode PI` di Termux.
4. Vercel relay membutuhkan dukungan header khusus pada transport Zafiro sebelum bisa dipakai.
