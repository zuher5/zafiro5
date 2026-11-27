# Diagnostics & Usage

English | [中文](TELEMETRY_CN.md)

Zafiro collects anonymous diagnostic and usage data to monitor stability, errors, and model availability.

## Collected Data

All collected events are statically defined in [`telemetry-contract/contract.yaml`](../telemetry-contract/contract.yaml):

* **Environment & Hardware**: OS version, ABI, device model, memory tier
* **Lifecycle**: Cold start counts and launch types
* **Reliability Metrics**: Model call outcome categories, foreground usage frequency

## Privacy Boundaries

* No personal identity data
* No conversation content, prompts, or model outputs
* No API keys, tokens, or credentials
* No local files or device identifiers (IMEI, Android ID)

## Source Code

* Contract definition: [`telemetry-contract/contract.yaml`](../telemetry-contract/contract.yaml)
* Client implementation: [`business/remote/`](../business/remote/src/main/kotlin/com/niki914/zafiro/business/remote/)

## Builds from this fork

Fork builds ship with `TELEMETRY_BASE_URL` empty, which disables reporting entirely (`baseUrl.isBlank()` short-circuits the sender, the activity tracker and the remote-config client). Set `-Pzafiro.telemetryBaseUrl=https://<your-worker>` to point at your own endpoint; the upstream Cloudflare worker is not used.
