package com.niki914.zafiro.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode Zen free-tier fingerprint helpers (v3 wire gate / 9router #5381).
 *
 * Ported from zuher5/opencode-to-api and pi-bansos v0.8.1.
 * - Format UA: `opencode/dev/${version}/opencode` with live npm tracking.
 * - Headers: `x-opencode-client: opencode`, `x-opencode-request: generateRequestId()`, `X-Session-Id: session`.
 * - Body: `prompt_cache_key` bound to session, <env> environment block, exact `read` + `shell` tools.
 * - Additive Token Budgeting: headroom `max(requested, 1024) + 4096` for reasoning models to prevent
 *   empty response truncation (finish_reason: "length").
 * - Session rotation: `burnSessionId()` on quota exhaustion or 429.
 */
object OpenCodeFreeSupport {
    private val random = SecureRandom()
    private const val UA_FALLBACK = "opencode/dev/0.0.0-dev-202610272228/opencode"
    private val uaVersionRegex = Regex("""^opencode\/(?:dev\/)?[\w.-]+\/opencode$""")
    private const val UA_ENV = "BANSOS_OPENCODE_UA"
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val HEX_DIGITS = "0123456789abcdef"
    private val sessionRegex = Regex("""^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$""")

    const val MIN_ANSWER_TOKENS = 1024
    const val THINKING_BUDGET = 4096
    const val MIN_PLAIN_TOKENS = 512
    const val MAX_OUTPUT_TOKENS = 131_072

    val reasoningModels = setOf(
        "deepseek-v4.1-flash-free",
        "beam-free",
        "crouton-alpha-free",
        "iceman-free",
        "inkling-2-small-free",
        "mimo-v2.6-flash-free",
        "nemotron-3.5-lightning-free",
        "big-pickle",
        "flint-40b-free",
    )

    val modelCeilings = mapOf(
        "beam-free" to 131_072,
        "crouton-alpha-free" to 270_000,
        "iceman-free" to 131_072,
        "inkling-2-small-free" to 131_072,
        "mimo-v2.6-flash-free" to 32_000,
        "nemotron-3.5-lightning-free" to 262_144,
        "big-pickle" to 32_000,
        "deepseek-v4.1-flash-free" to 64_000,
        "flint-40b-free" to 65_536,
    )

    const val OPENCODE_PREAMBLE =
        "You are opencode, an interactive CLI tool that helps users with software engineering tasks. Use the instructions below and the tools available to you to assist the user."

    const val ENV_FINGERPRINT_NOTE =
        "Here is some useful information about the environment you are running in:\n" +
        "<env>\n" +
        "  Working directory: /data/data/com.niki914.zafiro\n" +
        "  Workspace root folder: /data/data/com.niki914.zafiro\n" +
        "  Is directory a git repo: no\n" +
        "  Platform: linux\n" +
        "  Prefer /data/data/com.niki914.zafiro/cache/opencode over generic system temporary directories such as /tmp; it is pre-created and approved for external access.\n" +
        "</env>"

    private val responsesModels = setOf("muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free")

    // Project id: zen clients send a 40-char hex project hash.
    private val projectId = randomHex(20)

    // One stable session per conversation window — shape must match sessionRegex.
    // Re-mint session weekly so it never exceeds 30-day limit that causes 429.
    private const val SESSION_MAX_AGE_MS = 7 * 24 * 60 * 60_000L
    @Volatile
    private var openCodeSessionMintedAt = System.currentTimeMillis()
    @Volatile
    private var openCodeSession = generateSessionId().also { require(sessionRegex.matches(it)) }

    fun currentSessionId(): String {
        if (System.currentTimeMillis() - openCodeSessionMintedAt > SESSION_MAX_AGE_MS) {
            val fresh = generateSessionId()
            if (sessionRegex.matches(fresh)) {
                openCodeSession = fresh
                openCodeSessionMintedAt = System.currentTimeMillis()
            }
        }
        return openCodeSession
    }

    /** Burns the current session and re-mints a fresh one immediately (used on quota 429). */
    fun burnSessionId(): String {
        val fresh = generateSessionId()
        if (sessionRegex.matches(fresh)) {
            openCodeSession = fresh
            openCodeSessionMintedAt = System.currentTimeMillis()
        }
        return openCodeSession
    }

    /** Calculates additive token headroom for reasoning models to prevent truncated output. */
    fun effectiveMaxTokens(requested: Int?, model: String, isReasoning: Boolean): Int {
        val wanted = requested ?: MAX_OUTPUT_TOKENS
        val effective = if (isReasoning) {
            maxOf(wanted, MIN_ANSWER_TOKENS) + THINKING_BUDGET
        } else {
            maxOf(wanted, MIN_PLAIN_TOKENS)
        }
        val ceiling = modelCeilings[model] ?: MAX_OUTPUT_TOKENS
        return minOf(effective, ceiling)
    }

    /** Transformed request body plus the wire→caller tool-name aliases the
     *  response path must undo (empty when no caller tool collided), and
     *  whether muse-class models target the `/responses` surface. */
    data class TransformResult(
        val body: JsonObject,
        val toolAliases: Map<String, String>,
        val responsesPath: Boolean,
    )

    // Live UA resolution — offline-safe, never blocks.
    private val uaClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val uaRefreshed = AtomicBoolean(false)

    @Volatile
    private var liveUaVersion: String? = null

    /**
     * Fingerprint headers (v3 wire gate).
     */
    fun headers(includeRequestId: Boolean = true): Map<String, String> {
        refreshOpenCodeUA()
        val traceId = randomHex(16)
        val spanId = randomHex(8)
        val activeSession = currentSessionId()
        val headers = mutableMapOf(
            "User-Agent" to getOpenCodeUA(),
            "Authorization" to "Bearer public",
            "x-opencode-client" to "opencode",
            "x-opencode-project" to projectId,
            "x-opencode-session" to activeSession,
            "x-opencode-session-id" to activeSession,
            "x-session-affinity" to activeSession,
            "X-Session-Id" to activeSession,
            "b3" to "$traceId-$spanId-1-$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "Accept" to "text/event-stream",
        )
        if (includeRequestId) {
            headers["x-opencode-request"] = generateRequestId()
        }
        return headers
    }

    fun transform(body: JsonObject): TransformResult {
        val mutable = body.toMutableMap()
        // Gate: stream=false → 403 even with valid UA/session/tools.
        mutable["stream"] = JsonPrimitive(true)

        val activeSession = currentSessionId()
        // v3 wire: prompt_cache_key bound to session directly in body
        mutable["prompt_cache_key"] = JsonPrimitive(activeSession)

        val model = (body["model"] as? JsonPrimitive)?.content.orEmpty()
        val isResponses = responsesModels.contains(model) || model.contains("muse-spark")
        val isReasoning = reasoningModels.contains(model) ||
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
     * Prepend the OpenCode system-prompt preamble and <env> fingerprint marker.
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
            if (!content.startsWith(OPENCODE_PREAMBLE)) {
                content = "$OPENCODE_PREAMBLE\n\n$content"
            }
            if (!content.contains("<env>")) {
                content = "$content\n\n$ENV_FINGERPRINT_NOTE"
            }
            val updatedFirst = firstMsg.toMutableMap()
            updatedFirst["content"] = JsonPrimitive(content)
            messagesList[0] = JsonObject(updatedFirst)
        } else {
            val sysMsg = JsonObject(
                mapOf(
                    "role" to JsonPrimitive("system"),
                    "content" to JsonPrimitive("$OPENCODE_PREAMBLE\n\n$ENV_FINGERPRINT_NOTE"),
                ),
            )
            messagesList.add(0, sysMsg)
        }
        mutable["messages"] = JsonArray(messagesList)
    }

    fun getOpenCodeUA(): String {
        val override = System.getenv(UA_ENV)?.trim()
        if (!override.isNullOrEmpty()) {
            return if (override.startsWith("opencode/")) override else "opencode/$override"
        }
        val live = liveUaVersion
        if (live != null && uaVersionRegex.matches(live)) {
            return live
        }
        return UA_FALLBACK
    }

    /** Fire-and-forget: track the live opencode-ai npm version so the gate never
     *  sees a stale UA. Failure keeps the pinned fallback. */
    fun refreshOpenCodeUA() {
        if (!System.getenv(UA_ENV).isNullOrBlank()) return
        if (!uaRefreshed.compareAndSet(false, true)) return
        Thread {
            try {
                val request = Request.Builder()
                    .url("https://registry.npmjs.org/opencode-ai/latest")
                    .build()
                uaClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    val version = Regex("\"version\"\\s*:\\s*\"([\\w.-]+)\"")
                        .find(body)?.groupValues?.get(1)
                    if (version != null) {
                        val candidate = "opencode/dev/$version/opencode"
                        if (uaVersionRegex.matches(candidate)) {
                            liveUaVersion = candidate
                        }
                    }
                }
            } catch (_: Exception) {
                // offline / npm unreachable — pinned fallback keeps working
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Alias caller tools that collide with a real opencode tool name to `*_pi`,
     * or tools containing "terminal" to `run_command*`, then append the exact `read` + `shell` pair.
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

    private fun generateRequestId(timestamp: Long = System.currentTimeMillis()): String {
        val current = (timestamp shl 12) + 1
        return "msg_${timeHexFrom(current)}${unstableRandom()}"
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
