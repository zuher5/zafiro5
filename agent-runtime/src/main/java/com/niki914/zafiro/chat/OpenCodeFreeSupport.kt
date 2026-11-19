package com.niki914.zafiro.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode free-tier wire identity (ported from azrinaldi/opencode-free-bridge).
 *
 * Semua nilai identitas diturunkan dinamis, tidak pernah di-hardcode sebagai
 * kebenaran: versi/channel/nama CLI dari update API, endpoint chat/responses
 * dan daftar model gratis dari katalog models.opencode.ai. Nilai statis hanya
 * fallback saat jaringan belum menjawab. Anchors yang terukur membentuk 403
 * gate (dari bridge): User-Agent berbentuk `opencode/<channel>/<ver>/<name>`,
 * session headers lengkap, dan minimal ada tool bernama `read` — semuanya
 * dipin di sini supaya perubahan gate tidak bisa lolos begitu saja.
 */
object OpenCodeFreeSupport {
    private const val LOG_TAG = "niki914_zafiro_OpenCodeFreeSupport"

    private val random = SecureRandom()
    private const val UA_ENV = "BANSOS_OPENCODE_UA"
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val HEX_DIGITS = "0123456789abcdef"
    private val sessionRegex = Regex("""^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$""")

    private const val UPDATE_API_URL = "https://opencode.ai/update/api/latest/cli/npm"
    private const val CATALOG_URL = "https://models.opencode.ai/api.json"

    /** Fallback mengikuti state gate saat ini (update API 2026-11: 2.3.2/latest/cli). */
    private const val FALLBACK_UA = "opencode/latest/2.3.2/cli"
    private const val FALLBACK_CLIENT = "cli"
    private const val FALLBACK_INFERENCE_PREFIX = "/inference/openai/v1"

    const val MIN_ANSWER_TOKENS = 1024
    const val THINKING_BUDGET = 4096
    const val MIN_PLAIN_TOKENS = 512
    const val MAX_OUTPUT_TOKENS = 131_072

    const val ENV_FINGERPRINT_NOTE =
        "Here is some useful information about the environment you are running in:\n" +
        "<env>\n" +
        "  Working directory: /data/data/com.niki914.zafiro\n" +
        "  Workspace root folder: /data/data/com.niki914.zafiro\n" +
        "  Is directory a git repo: no\n" +
        "  Platform: linux\n" +
        "  Prefer /data/data/com.niki914.zafiro/cache/opencode over generic system temporary directories such as /tmp; it is pre-created and approved for external access.\n" +
        "</env>"

    private val json = Json { ignoreUnknownKeys = true }

    // ── Identity (update API derivation) ─────────────────────────────────────

    private const val IDENTITY_TTL_MS = 15 * 60 * 1000L

    /** CLI wire identity: UA + client value, diturunkan dari update API. */
    private class Identity(
        val userAgent: String,
        val client: String,
    )

    @Volatile
    private var identity = Identity(userAgent = FALLBACK_UA, client = FALLBACK_CLIENT)
    @Volatile
    private var identityFetchedAtMs = 0L
    @Volatile
    private var identityStale = false
    private val identityFetchStarted = AtomicBoolean(false)

    private val fetchClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun getOpenCodeUA(): String {
        val override = System.getenv(UA_ENV)?.trim()
        if (!override.isNullOrEmpty()) {
            return if (override.startsWith("opencode/")) override else "opencode/$override"
        }
        return identity.userAgent
    }

    private fun clientValue(): String {
        val override = System.getenv(UA_ENV)?.trim()
        if (!override.isNullOrEmpty()) return FALLBACK_CLIENT
        return identity.client
    }

    /** 403 / 426 → identitas bisa usang: tandai stale, request berikutnya refresh. */
    fun markIdentityStale() {
        identityStale = true
    }

    /**
     * Refresh identitas + katalog dari update API / katalog upstream.
     * Fire-and-forget: tidak pernah memblokir pemanggil; gagal jaringan
     * mempertahankan nilai terakhir (fallback statis saat proses muda).
     */
    fun refreshIdentityIfNeeded() {
        if (!System.getenv(UA_ENV).isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val fresh = now - identityFetchedAtMs < IDENTITY_TTL_MS
        if (!identityStale && fresh) return
        if (!identityFetchStarted.compareAndSet(false, true)) return
        identityStale = false
        Thread {
            try {
                refreshFromUpdateApi()
                refreshFromCatalog()
            } catch (_: Exception) {
                // offline / upstream unreachable — keep last known identity
            } finally {
                identityFetchedAtMs = System.currentTimeMillis()
                identityFetchStarted.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun refreshFromUpdateApi() {
        val body = fetchText(UPDATE_API_URL) ?: return
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        val version = root.stringOf("version")?.takeIf { Regex("""^\d+\.\d+\.\d+$""").matches(it) }
        val channel = root.stringOf("channel")?.takeIf(String::isNotBlank) ?: "latest"
        val name = root.stringOf("name")?.takeIf(String::isNotBlank) ?: "cli"
        if (version != null) {
            identity = Identity(
                userAgent = "opencode/$channel/$version/$name",
                client = name,
            )
        }
    }

    // ── Catalog (endpoints + free model facts) ───────────────────────────────

    private class Catalog(
        /** Path prefix permukaan inference, mis. `/inference/openai/v1`. */
        val inferencePrefix: String,
        val modelCeilings: Map<String, Int>,
        val reasoningModels: Set<String>,
    )

    // Fallback model facts (katalog 2026-11; dikoreksi otomatis saat fetch).
    // Dideklarasikan sebelum `catalog` — inisialisasi properti object berurutan.
    private val FALLBACK_REASONING_MODELS: Set<String> = setOf(
        "deepseek-v4.1-flash-free",
        "beam-free",
        "iceman-free",
        "mimo-v2.6-flash-free",
        "nemotron-3.5-lightning-free",
        "big-pickle",
        "flint-40b-free",
        "hy4-flash-free",
        "longcat-2.5-free",
        "tidepool-alpha-free",
        "nemotron-3.5-super-vl-free",
    )

    private val FALLBACK_MODEL_CEILINGS: Map<String, Int> = mapOf(
        "beam-free" to 131_072,
        "iceman-free" to 131_072,
        "mimo-v2.6-flash-free" to 32_000,
        "nemotron-3.5-lightning-free" to 262_144,
        "big-pickle" to 32_000,
        "deepseek-v4.1-flash-free" to 32_000,
        "flint-40b-free" to 65_536,
        "hy4-flash-free" to 65_536,
        "longcat-2.5-free" to 262_144,
        "tidepool-alpha-free" to 131_072,
        "nemotron-3.5-super-vl-free" to 131_072,
    )

    @Volatile
    private var catalog = Catalog(
        inferencePrefix = FALLBACK_INFERENCE_PREFIX,
        modelCeilings = FALLBACK_MODEL_CEILINGS,
        reasoningModels = FALLBACK_REASONING_MODELS,
    )

    /** Path prefix endpoint chat/responses aktif di upstream (dinamis dari katalog). */
    fun inferencePathPrefix(): String = catalog.inferencePrefix

    fun currentModelCeilings(): Map<String, Int> = catalog.modelCeilings

    fun currentReasoningModels(): Set<String> = catalog.reasoningModels

    private fun refreshFromCatalog() {
        val body = fetchText(CATALOG_URL) ?: return
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        val oc = root["opencode"] as? JsonObject ?: return
        val ceilings = HashMap<String, Int>()
        val reasoning = HashSet<String>()
        val models = oc["models"] as? JsonObject ?: return
        for ((id, element) in models) {
            val model = element as? JsonObject ?: continue
            val cost = model["cost"] as? JsonObject
            val inputCost = (cost?.get("input") as? JsonPrimitive)?.intOrNull
            if (inputCost != null && inputCost != 0) continue
            if (model.stringOf("status") == "deprecated") continue
            (model["limit"] as? JsonObject)?.let { limit ->
                (limit["output"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                    ?.let { ceilings[id] = it }
            }
            if (model["reasoning"] == JsonPrimitive(true)) reasoning += id
        }
        val api = oc.stringOf("api")?.takeIf(String::isNotBlank)
        val prefix = api
            ?.takeIf { it.startsWith("https://") && it.removeSuffix("/").substringAfter("//").substringBefore('/').endsWith("opencode.ai") }
            ?.let { "/" + it.removeSuffix("/").substringAfter("//").substringAfter('/').trim('/') }
            ?.takeIf { it.isNotBlank() }
        catalog = Catalog(
            inferencePrefix = prefix ?: FALLBACK_INFERENCE_PREFIX,
            modelCeilings = if (ceilings.isEmpty()) FALLBACK_MODEL_CEILINGS else ceilings,
            reasoningModels = if (reasoning.isEmpty()) FALLBACK_REASONING_MODELS else reasoning,
        )
    }

    private fun fetchText(url: String): String? = runCatching {
        fetchClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ── Session ──────────────────────────────────────────────────────────────

    // Project id: 40-char hex, satu per proses (stabil seperti CLI per project).
    private val projectId = randomHex(20)

    // Re-mint mingguan supaya tidak melewati batas umur session 30 hari (429).
    private const val SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    @Volatile
    private var openCodeSessionMintedAt = System.currentTimeMillis()
    @Volatile
    private var openCodeSession = generateSessionId().also { require(sessionRegex.matches(it)) }

    fun currentSessionId(): String {
        if (System.currentTimeMillis() - openCodeSessionMintedAt > SESSION_MAX_AGE_MS) {
            mintSession()
        }
        return openCodeSession
    }

    /** Burns the current session and re-mints a fresh one (quota / gate 403). */
    fun burnSessionId(): String {
        mintSession()
        return openCodeSession
    }

    private fun mintSession() {
        val fresh = generateSessionId()
        if (sessionRegex.matches(fresh)) {
            openCodeSession = fresh
            openCodeSessionMintedAt = System.currentTimeMillis()
        }
    }

    // ── Gate headers ─────────────────────────────────────────────────────────

    /**
     * The 7 gate headers upstream's own request builder sets, exactly. Header
     * di luar daftar ini tidak pernah dikirim CLI — `x-opencode-request` cuma
     * untuk surface katalog lama, `Connection: close` merusak keep-alive.
     */
    fun headers(): Map<String, String> {
        refreshIdentityIfNeeded()
        val activeSession = currentSessionId()
        return linkedMapOf(
            "User-Agent" to getOpenCodeUA(),
            "Authorization" to "Bearer public",
            "x-opencode-client" to clientValue(),
            "x-opencode-project" to projectId,
            "x-opencode-session" to activeSession,
            "x-opencode-session-id" to activeSession,
            "x-session-affinity" to activeSession,
            "X-Session-Id" to activeSession,
        )
    }

    // ── Token budgeting ──────────────────────────────────────────────────────

    /** Additive headroom untuk reasoning model agar tidak kosong finish_reason=length. */
    fun effectiveMaxTokens(requested: Int?, model: String, isReasoning: Boolean): Int {
        val wanted = requested ?: MAX_OUTPUT_TOKENS
        val effective = if (isReasoning) {
            maxOf(wanted, MIN_ANSWER_TOKENS) + THINKING_BUDGET
        } else {
            maxOf(wanted, MIN_PLAIN_TOKENS)
        }
        val ceiling = currentModelCeilings()[model] ?: MAX_OUTPUT_TOKENS
        return minOf(effective, ceiling)
    }

    // ── Body transform ───────────────────────────────────────────────────────

    /** Transformed request body plus the wire→caller tool-name aliases the
     *  response path must undo (empty when no caller tool collided), and
     *  whether this model targets the `/responses` surface. */
    data class TransformResult(
        val body: JsonObject,
        val toolAliases: Map<String, String>,
        val responsesPath: Boolean,
    )

    fun transform(body: JsonObject): TransformResult {
        val mutable = body.toMutableMap()
        // Gate: stream=false → 403 even with valid UA/session/tools.
        mutable["stream"] = JsonPrimitive(true)

        val activeSession = currentSessionId()
        // v3 wire: prompt_cache_key bound to session directly in body
        mutable["prompt_cache_key"] = JsonPrimitive(activeSession)

        val model = (body["model"] as? JsonPrimitive)?.content.orEmpty()
        val isResponses = model.contains("muse-spark")
        val isReasoning = currentReasoningModels().contains(model) ||
            (body["reasoning_effort"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "none" }

        val requestedTokens = (body["max_tokens"] as? JsonPrimitive)?.intOrNull
            ?: (body["max_completion_tokens"] as? JsonPrimitive)?.intOrNull
            ?: (body["max_output_tokens"] as? JsonPrimitive)?.intOrNull
        val budgetedTokens = effectiveMaxTokens(requestedTokens, model, isReasoning)

        injectPreambleAndEnv(body, mutable, isResponses)

        if (isResponses) {
            mutable["max_output_tokens"] = JsonPrimitive(budgetedTokens)
            mutable.remove("max_tokens")
            mutable.remove("max_completion_tokens")
            mutable["store"] = JsonPrimitive(false)
            mutable["tools"] = ensureResponsesFingerprintTools(body)
            mutable["input"] = sanitizeResponsesItems(body)
            return TransformResult(JsonObject(mutable), emptyMap(), responsesPath = true)
        }

        mutable["max_tokens"] = JsonPrimitive(budgetedTokens)
        val (tools, aliases) = ensureChatFingerprintTools(body)
        mutable["tools"] = tools
        return TransformResult(JsonObject(mutable), aliases, responsesPath = false)
    }

    /**
     * Ensure the <env> fingerprint marker is present in system prompt (matches opencode-to-api).
     */
    private fun injectPreambleAndEnv(
        body: JsonObject,
        mutable: MutableMap<String, JsonElement>,
        responses: Boolean,
    ) {
        if (responses) {
            val instructions = (body["instructions"] as? JsonPrimitive)?.content.orEmpty()
            if (!instructions.contains("<env>")) {
                val updatedInstructions = if (instructions.isNotBlank()) {
                    "$instructions\n\n$ENV_FINGERPRINT_NOTE"
                } else {
                    ENV_FINGERPRINT_NOTE
                }
                mutable["instructions"] = JsonPrimitive(updatedInstructions)
            }
            return
        }

        val rawMessages = body["messages"] as? JsonArray ?: return
        val messagesList = rawMessages.toMutableList()
        val firstMsg = messagesList.firstOrNull() as? JsonObject
        val firstRole = (firstMsg?.get("role") as? JsonPrimitive)?.content
        if (firstRole == "system") {
            var content = (firstMsg["content"] as? JsonPrimitive)?.content.orEmpty()
            if (!content.contains("<env>")) {
                content = if (content.isNotBlank()) "$content\n\n$ENV_FINGERPRINT_NOTE" else ENV_FINGERPRINT_NOTE
            }
            val updatedFirst = firstMsg.toMutableMap()
            updatedFirst["content"] = JsonPrimitive(content)
            messagesList[0] = JsonObject(updatedFirst)
        } else {
            val sysMsg = JsonObject(
                mapOf(
                    "role" to JsonPrimitive("system"),
                    "content" to JsonPrimitive(ENV_FINGERPRINT_NOTE),
                ),
            )
            messagesList.add(0, sysMsg)
        }
        mutable["messages"] = JsonArray(messagesList)
    }

    /**
     * Alias caller tools that collide with a real opencode tool name to `*_pi`,
     * or tools containing "terminal" to `run_command*`, then append the exact
     * `read` + `shell` pair (gate anchor: minimal `read` harus ada).
     */
    private fun ensureChatFingerprintTools(body: JsonObject): Pair<JsonArray, Map<String, String>> {
        val existing = body["tools"] as? JsonArray
        if (existing.isNullOrEmpty()) {
            return OpenCodeFingerprintData.chatTools to emptyMap()
        }
        val aliases = mutableMapOf<String, String>()
        var runCommandCount = 0
        val tools = existing.map { tool ->
            val name = toolNameOf(tool)
            val lower = name.lowercase()
            if (name.isEmpty()) {
                tool
            } else if (lower in OpenCodeFingerprintData.opencodeToolNames) {
                val alias = "${name}_pi"
                aliases[alias] = name
                renameTool(tool, alias)
            } else if (lower.contains("terminal")) {
                val alias = if (runCommandCount++ == 0) "run_command" else "run_command_$runCommandCount"
                aliases[alias] = name
                renameTool(tool, alias)
            } else {
                tool
            }
        }.toMutableList()

        val present = tools.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }.toSet()
        for (tool in OpenCodeFingerprintData.chatTools) {
            val name = toolNameOf(tool)
            if (name !in present) tools.add(tool)
        }
        return JsonArray(tools) to aliases
    }

    private fun ensureResponsesFingerprintTools(body: JsonObject): JsonArray {
        val existing = body["tools"] as? JsonArray
        if (existing.isNullOrEmpty()) {
            return OpenCodeFingerprintData.responsesTools
        }
        val tools = existing.toMutableList()
        val present = tools.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }.toSet()
        for (tool in OpenCodeFingerprintData.responsesTools) {
            val name = toolNameOf(tool)
            if (name !in present) tools.add(tool)
        }
        return JsonArray(tools)
    }

    /** Rename a tool in either the chat (`function.name`) or responses (`name`) shape. */
    private fun renameTool(tool: JsonElement, alias: String): JsonElement {
        val obj = tool as? JsonObject ?: return tool
        val fn = obj["function"] as? JsonObject
        return if (fn != null) {
            val updatedFn = fn.toMutableMap().apply { this["name"] = JsonPrimitive(alias) }
            JsonObject(obj.toMutableMap().apply { this["function"] = JsonObject(updatedFn) })
        } else {
            JsonObject(obj.toMutableMap().apply { this["name"] = JsonPrimitive(alias) })
        }
    }

    private fun sanitizeResponsesItems(body: JsonObject): JsonArray {
        val input = body["input"] as? JsonArray ?: return JsonArray(emptyList())
        return JsonArray(input.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull item
            if ((obj["type"] as? JsonPrimitive)?.content == "reasoning") return@mapNotNull null
            val mutable = obj.toMutableMap()
            mutable.remove("encrypted_content")
            mutable.remove("reasoning_encrypted_content")
            JsonObject(mutable)
        })
    }

    private fun toolNameOf(element: JsonElement): String {
        val obj = element as? JsonObject ?: return ""
        (obj["name"] as? JsonPrimitive)?.contentOrNull?.let { return it.trim() }
        val fn = obj["function"] as? JsonObject ?: return ""
        return (fn["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }

    private fun generateSessionId(timestamp: Long = System.currentTimeMillis()): String {
        val current = (timestamp shl 12) + 1
        return "ses_${timeHexFrom(current.inv())}${unstableRandom()}"
    }

    private fun timeHexFrom(value: Long): String {
        val chars = CharArray(12)
        for (i in 0..5) {
            val b = ((value ushr (40 - 8 * i)) and 0xff).toInt()
            chars[i * 2] = HEX_DIGITS[b ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
        }
        return String(chars)
    }

    private fun unstableRandom(): String {
        val chars = CharArray(14)
        for (i in 0 until 14) {
            chars[i] = BASE62[random.nextInt(BASE62.length)]
        }
        return String(chars)
    }

    private fun randomHex(bytes: Int): String {
        val arr = ByteArray(bytes)
        random.nextBytes(arr)
        val chars = CharArray(bytes * 2)
        for (i in 0 until bytes) {
            val b = arr[i].toInt() and 0xff
            chars[i * 2] = HEX_DIGITS[b ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[b and 0x0f]
        }
        return String(chars)
    }
}
