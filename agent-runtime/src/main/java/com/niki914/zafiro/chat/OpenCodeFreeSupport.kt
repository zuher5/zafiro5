package com.niki914.zafiro.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom

/**
 * OpenCode Zen free-tier fingerprint helpers.
 *
 * Ported from pi-bansos: upstream gate requires real OpenCode client
 * headers and a request body matching the OpenCode tool fingerprint.
 */
object OpenCodeFreeSupport {
    private val random = SecureRandom()
    private val fingerprintTools = listOf("bash", "glob", "grep", "read", "edit", "write")

    private val responsesModels = setOf("muse-spark-1.2-contributor-free", "muse-spark-1.3-contributor-free")

    private val projectId = randomHex(20)
    private val sessionId = generateSessionId()

    fun headers(): Map<String, String> {
        val traceId = randomHex(16)
        val spanId = randomHex(8)
        return mapOf(
            "User-Agent" to "opencode/1.18.31",
            "Authorization" to "Bearer public",
            "x-opencode-client" to "desktop",
            "x-opencode-project" to projectId,
            "x-opencode-session" to sessionId,
            "x-session-affinity" to sessionId,
            "x-opencode-request" to "msg_${System.currentTimeMillis().toString(16)}${randomBase62(14)}",
            "b3" to "$traceId-$spanId-1-$spanId",
            "traceparent" to "00-$traceId-$spanId-01",
            "Accept" to "text/event-stream",
        )
    }

    fun transform(body: JsonObject): JsonObject {
        val mutable = body.toMutableMap()
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
            mutable["tools"] = ensureChatFingerprintTools(body)
            if (body["tool_choice"] == null) {
                mutable["tool_choice"] = JsonPrimitive("none")
            }
        }
        return JsonObject(mutable)
    }

    private fun hasFingerprintTools(body: JsonObject): Boolean {
        val tools = body["tools"] as? JsonArray ?: return false
        return tools.any { tool ->
            val name = toolNameOf(tool)
            fingerprintTools.contains(name)
        }
    }

    private fun ensureChatFingerprintTools(body: JsonObject): JsonArray {
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val present = existing.mapNotNull { toolNameOf(it).takeIf { name -> name.isNotBlank() } }.toMutableSet()
        var injected = false
        for (name in fingerprintTools) {
            if (present.contains(name)) continue
            existing.add(functionToolJson(name))
            injected = true
        }
        return JsonArray(existing)
    }

    private fun ensureResponsesFingerprintTools(body: JsonObject): JsonArray {
        val existing = (body["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val present = existing.mapNotNull { toolNameOf(it).takeIf { name -> name.isNotBlank() } }.toMutableSet()
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

    private fun toolNameOf(element: kotlinx.serialization.json.JsonElement): String {
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

    private fun generateSessionId(): String {
        val hex = randomHex(6)
        val base62 = randomBase62(14)
        return "ses_$hex$base62"
    }

    private fun randomHex(bytes: Int): String {
        val arr = ByteArray(bytes)
        random.nextBytes(arr)
        return arr.joinToString("") { "%02x".format(it) }
    }

    private fun randomBase62(length: Int): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        return (1..length).joinToString("") { chars[random.nextInt(chars.length)].toString() }
    }
}
