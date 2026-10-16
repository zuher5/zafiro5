package com.niki914.zafiro.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenCode free-tier tool fingerprint (v2 gate, 2026-10-14).
 *
 * The gate requires opencode's own `read` + `shell` tools with the exact
 * description and parameters schema from packages/core/src/tool/plugin/
 * {read,shell}.ts; a stub or partial schema 403s. A caller tool whose name
 * collides with a real opencode tool travels under a `_pi` alias instead.
 * Ported from pi-bansos 0.6.3.
 */
internal object OpenCodeFingerprintData {

    /** Real opencode tool names. A caller tool carrying one of these names is
     *  aliased `*_pi` on the wire so it cannot shadow the exact pair. */
    val opencodeToolNames: Set<String> = setOf(
        "read", "shell", "bash", "glob", "grep", "edit", "write", "patch",
        "webfetch", "websearch", "question", "skill", "subagent", "todowrite",
        "mcp-resource", "opencode",
    )

    private const val READ_DESCRIPTION =
        "Read the contents of a file or directory. Supports text files, images, and PDFs. " +
            "Images and PDFs are presented directly to the model. Each text line is prefixed " +
            "by its 1-based line number as <line>: <content>. The prefix is for reference and " +
            "is not part of the file content. Directory entries are returned one per line. Use " +
            "offset and limit to read large files or directories in sections. Prefer one larger " +
            "read over many small slices, and use grep to find specific content in large files."

    private const val SHELL_DESCRIPTION =
        "Execute a shell command and return its output. Quote file paths containing spaces or " +
            "special characters. Prefer dedicated tools over shell commands when possible. When " +
            "output is large, the full result is saved to a file and a truncated preview is " +
            "returned. Rely on automatic truncation unless filtering the output is more useful. " +
            "Commands accept an optional timeout, background commands have no timeout by default. " +
            "Background commands return immediately, and you will be notified when they complete."

    private val readParameters = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                mapOf(
                    "path" to stringProp("File or directory to read"),
                    "offset" to numberProp("The line or directory entry to start reading from (1-based)"),
                    "limit" to numberProp(
                        "The maximum number of lines or directory entries to read " +
                            "(defaults to and capped at 2000)"
                    ),
                ),
            ),
            "required" to JsonArray(listOf(JsonPrimitive("path"))),
        ),
    )

    private val shellParameters = JsonObject(
        mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(
                mapOf(
                    "command" to stringProp("Shell command string to execute"),
                    "workdir" to stringProp(
                        "Working directory to execute the command in. Defaults to the current " +
                            "working directory. When possible, avoid changing directories in the " +
                            "command and set the working directory here instead."
                    ),
                    "timeout" to numberProp("Timeout in milliseconds. Set to 0 to disable the timeout."),
                    "background" to boolProp(
                        "Run the command in the background and return immediately (useful for dev " +
                            "servers and long-running builds). You do not need to use '&' at the end " +
                            "of the command when using this parameter. You will be notified when it " +
                            "completes. DO NOT poll for completion."
                    ),
                ),
            ),
            "required" to JsonArray(listOf(JsonPrimitive("command"))),
        ),
    )

    /** Chat Completions wire shape: `{type, function:{name, description, parameters}}`. */
    val chatTools: JsonArray = JsonArray(
        listOf(
            chatTool("read", READ_DESCRIPTION, readParameters),
            chatTool("shell", SHELL_DESCRIPTION, shellParameters),
        ),
    )

    /** Responses wire shape: `{type, name, description, parameters}`. */
    val responsesTools: JsonArray = JsonArray(
        listOf(
            responsesTool("read", READ_DESCRIPTION, readParameters),
            responsesTool("shell", SHELL_DESCRIPTION, shellParameters),
        ),
    )

    private fun chatTool(name: String, description: String, parameters: JsonObject): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "description" to JsonPrimitive(description),
                        "parameters" to parameters,
                    ),
                ),
            ),
        )

    private fun responsesTool(name: String, description: String, parameters: JsonObject): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "name" to JsonPrimitive(name),
                "description" to JsonPrimitive(description),
                "parameters" to parameters,
            ),
        )

    private fun stringProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(description)))

    private fun numberProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive(description)))

    private fun boolProp(description: String): JsonObject =
        JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive(description)))
}
