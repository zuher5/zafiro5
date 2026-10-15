package com.niki914.zafiro.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenCode Zen free-tier fingerprint helpers.
 *
 * Ported from pi-bansos: upstream gate requires real OpenCode client
 * headers and a request body matching the OpenCode tool fingerprint.
 * Missing any one → 403 FreeTierError.
 */
object OpenCodeFreeSupport {
    private val random = SecureRandom()
    private const val UA_FALLBACK = "1.18.31"
    private val uaVersionRegex = Regex("""^\d+\.\d+\.\d+$""")
    private const val UA_ENV = "BANSOS_OPENCODE_UA"
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val sessionRegex = Regex("""^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$""")

    private val fingerprintTools = listOf("bash", "glob", "grep", "read", "edit", "write")

    private val responsesModels = setOf("muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free")

    // Project id: zen clients send a 40-char hex project hash.
    private val projectId = randomHex(20)
    private val sessionId = generateSessionId().also { require(sessionRegex.matches(it)) }

    // Live UA resolution — offline-safe, never blocks.
    // Precedence: env override > in-process live version > disk cache > pinned.
    // The gate has rejected stale UA versions before; auto-refresh keeps us off that cliff.
    private val uaClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val uaRefreshed = AtomicBoolean(false)

    @Volatile
    private var liveUaVersion: String? = null

    fun headers(): Map<String, String> {
        refreshOpenCodeUA()
        val traceId = randomHex(16)
        val spanId = randomHex(8)
        return mapOf(
            "User-Agent" to getOpenCodeUA(),
            "Authorization" to "Bearer public",
            "x-opencode-client" to "desktop",
            "x-opencode-project" to projectId,
            "x-opencode-session" to sessionId,
            "x-session-affinity" to sessionId,
            "x-opencode-request" to generateRequestId(),
            // Distributed-tracing headers real opencode clients emit.
            "b3" to "$traceId-$spanId-1-$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "Accept" to "text/event-stream",
        )
    }

    fun transform(body: JsonObject): JsonObject {
        val mutable = body.toMutableMap()
        // Gate: stream=false → 403 even with valid UA/session/tools.
        mutable["stream"] = JsonPrimitive(true)
        val model = (body["model"] as? JsonPrimitive)?.content.orEmpty()
        if (responsesModels.contains(model) || model.contains("muse-spark")) {
            val maxOutput = body["max_output_tokens"] as? JsonPrimitive
            if (maxOutput == null) {
                (body["max_completion_tokens"] as? JsonPrimitive)?.let {
                    mutable["max_output_tokens"] = it
                } ?: (body["max_tokens"] as? JsonPrimitive)?.let {
                    mutable["max_output_tokens"] = it
                }
            }
            mutable.remove("max_tokens")
            mutable.remove("max_completion_tokens")
            mutable["store"] = JsonPrimitive(false)
            mutable["tools"] = ensureResponsesFingerprintTools(body)
            mutable["input"] = sanitizeResponsesItems(body)
        } else {
            val existing = (body["tools"] as? JsonArray)
                ?.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }
                ?.toMutableSet()
                ?: mutableSetOf()
            val injected = fingerprintTools.any { it !in existing }
            mutable["tools"] = ensureChatFingerprintTools(body)
            // Injected decoys must never be callable; "none" hides them while
            // caller-declared tools stay callable.
            if (injected && body["tool_choice"] == null) {
                mutable["tool_choice"] = JsonPrimitive("none")
            }
        }
        return JsonObject(mutable)
    }

    fun getOpenCodeUA(): String {
        val override = System.getenv(UA_ENV)?.trim()
        if (!override.isNullOrEmpty()) {
            return if (override.startsWith("opencode/")) override else "opencode/$override"
        }
        val live = liveUaVersion
        if (live != null && uaVersionRegex.matches(live)) return "opencode/$live"
        return "opencode/$UA_FALLBACK"
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
                    val version = Regex("\"version\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)\"")
                        .find(body)?.groupValues?.get(1)
                    if (version != null && uaVersionRegex.matches(version)) {
                        liveUaVersion = version
                    }
                }
            } catch (_: Exception) {
                // offline / npm unreachable — pinned fallback keeps working
            }
        }.apply { isDaemon = true }.start()
    }

    private fun ensureChatFingerprintTools(body: JsonObject): JsonArray {
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val present = existing.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }.toMutableSet()
        for (name in fingerprintTools) {
            if (present.contains(name)) continue
            existing.add(functionToolJson(name))
        }
        return JsonArray(existing)
    }

    private fun ensureResponsesFingerprintTools(body: JsonObject): JsonArray {
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val present = existing.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }.toMutableSet()
        for (name in fingerprintTools) {
            if (present.contains(name)) continue
            existing.add(responsesToolJson(name))
        }
        return JsonArray(existing)
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

    private fun functionToolJson(name: String): JsonObject {
        return JsonObject(mapOf(
            "type" to JsonPrimitive("function"),
            "function" to JsonObject(mapOf(
                "name" to JsonPrimitive(name),
                "description" to JsonPrimitive("OpenCode built-in $name tool"),
                "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            )),
        ))
    }

    private fun responsesToolJson(name: String): JsonObject {
        return JsonObject(mapOf(
            "type" to JsonPrimitive("function"),
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive("OpenCode built-in $name tool"),
            "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
        ))
    }

    // One stable session per process — shape must match sessionRegex.
    private fun generateSessionId(timestamp: Long = System.currentTimeMillis()): String {
        val current = (timestamp shl 12) + 1
        return "ses_${timeHexFrom(current.inv())}${unstableRandom()}"
    }

    private fun generateRequestId(timestamp: Long = System.currentTimeMillis()): String {
        val current = (timestamp shl 12) + 1
        return "msg_${timeHexFrom(current)}${unstableRandom()}"
    }

    private fun timeHexFrom(value: Long): String {
        return (0..5).joinToString("") { i ->
            (((value shr (40 - 8 * i)) and 0xff).toString(16)).padStart(2, '0')
        }
    }

    private fun unstableRandom(): String {
        return (1..14).joinToString("") { BASE62[random.nextInt(BASE62.length)].toString() }
    }

    private fun randomHex(bytes: Int): String {
        val arr = ByteArray(bytes)
        random.nextBytes(arr)
        return arr.joinToString("") { "%02x".format(it) }
    }
}
