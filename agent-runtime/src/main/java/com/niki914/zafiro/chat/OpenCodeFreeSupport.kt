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
 * Ported from pi-bansos 0.6.3. The v2 gate (2026-10-14) requires real OpenCode
 * client headers and opencode's own `read` + `shell` tool schemas; the inference
 * surface rejects `x-opencode-request`. Missing any one → 403 FreeTierError. A
 * caller tool that shadows a real opencode tool name is aliased `*_pi` on the
 * wire and restored from the response.
 */
object OpenCodeFreeSupport {
    private val random = SecureRandom()
    private const val UA_FALLBACK = "1.19.0"
    private val uaVersionRegex = Regex("""^\d+\.\d+\.\d+$""")
    private const val UA_ENV = "BANSOS_OPENCODE_UA"
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val sessionRegex = Regex("""^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$""")

    const val OPENCODE_PREAMBLE =
        "You are opencode, an interactive CLI tool that helps users with software engineering tasks. Use the instructions below and the tools available to you to assist the user."

    private val responsesModels = setOf("muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free")

    // Project id: zen clients send a 40-char hex project hash.
    private val projectId = randomHex(20)
    private val sessionId = generateSessionId().also { require(sessionRegex.matches(it)) }

    /** Transformed request body plus the wire→caller tool-name aliases the
     *  response path must undo (empty when no caller tool collided), and
     *  whether muse-class models target the `/responses` surface. */
    data class TransformResult(
        val body: JsonObject,
        val toolAliases: Map<String, String>,
        val responsesPath: Boolean,
    )

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

    /**
     * Fingerprint headers. [includeRequestId] is only true for the `/zen/v1`
     * catalog call: the inference surface rejects `x-opencode-request`.
     */
    fun headers(includeRequestId: Boolean = false): Map<String, String> {
        refreshOpenCodeUA()
        val traceId = randomHex(16)
        val spanId = randomHex(8)
        val headers = mutableMapOf(
            "User-Agent" to getOpenCodeUA(),
            "Authorization" to "Bearer public",
            "x-opencode-client" to "cli",
            "x-opencode-project" to projectId,
            "x-opencode-session" to sessionId,
            "x-opencode-session-id" to sessionId,
            "x-session-affinity" to sessionId,
            // Distributed-tracing headers real opencode clients emit.
            "b3" to "$traceId-$spanId-1-$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "Accept" to "text/event-stream",
        )
        if (includeRequestId) headers["x-opencode-request"] = generateRequestId()
        return headers
    }

    fun transform(body: JsonObject): TransformResult {
        val mutable = body.toMutableMap()
        // Gate: stream=false → 403 even with valid UA/session/tools.
        mutable["stream"] = JsonPrimitive(true)

        injectPreamble(body, mutable)

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
            return TransformResult(JsonObject(mutable), emptyMap(), responsesPath = true)
        }

        val (tools, aliases) = ensureChatFingerprintTools(body)
        mutable["tools"] = tools
        return TransformResult(JsonObject(mutable), aliases, responsesPath = false)
    }

    /**
     * Prepend the OpenCode system-prompt preamble the edge inspects. Existing
     * first system message is prefixed in place; otherwise a system message is
     * inserted at the head.
     */
    private fun injectPreamble(body: JsonObject, mutable: MutableMap<String, JsonElement>) {
        val rawMessages = body["messages"] as? JsonArray ?: return
        val messagesList = rawMessages.toMutableList()
        val firstMsg = messagesList.firstOrNull() as? JsonObject
        val firstRole = (firstMsg?.get("role") as? JsonPrimitive)?.content
        if (firstRole == "system") {
            val existingContent = (firstMsg["content"] as? JsonPrimitive)?.content.orEmpty()
            if (!existingContent.startsWith(OPENCODE_PREAMBLE)) {
                val combinedContent = "$OPENCODE_PREAMBLE\n\n$existingContent"
                val updatedFirst = firstMsg.toMutableMap()
                updatedFirst["content"] = JsonPrimitive(combinedContent)
                messagesList[0] = JsonObject(updatedFirst)
            }
        } else {
            val sysMsg = JsonObject(
                mapOf(
                    "role" to JsonPrimitive("system"),
                    "content" to JsonPrimitive(OPENCODE_PREAMBLE),
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
        val version = liveUaVersion?.takeIf { uaVersionRegex.matches(it) } ?: UA_FALLBACK
        return "opencode/$version ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14"
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

    /**
     * Alias caller tools that collide with a real opencode tool name to `*_pi`,
     * then append the exact `read` + `shell` pair the gate requires. Returns the
     * aliased array plus the wire→caller map for restoring names downstream.
     */
    private fun ensureChatFingerprintTools(body: JsonObject): Pair<JsonArray, Map<String, String>> {
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val aliases = mutableMapOf<String, String>()
        val tools = existing.map { tool ->
            val name = toolNameOf(tool)
            if (name.isEmpty() || name.lowercase() !in OpenCodeFingerprintData.opencodeToolNames) {
                tool
            } else {
                val alias = "${name}_pi"
                aliases[alias] = name
                renameTool(tool, alias)
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
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val present = existing.mapNotNull { toolNameOf(it).takeIf(String::isNotBlank) }.toSet()
        for (tool in OpenCodeFingerprintData.responsesTools) {
            val name = toolNameOf(tool)
            if (name !in present) existing.add(tool)
        }
        return JsonArray(existing)
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
