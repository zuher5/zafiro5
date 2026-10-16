package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.ImageSaver
import com.niki914.okia.Okia
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.AnthropicMessagesProtocol
import com.niki914.okia.protocol.ChatProtocol
import com.niki914.okia.protocol.Compat
import com.niki914.okia.protocol.GoogleOpenAiCompat
import com.niki914.okia.protocol.OpenAIChatCompletionCompat
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.protocol.OpenAIResponsesProtocol
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.chat.agentic.AndroidImageLoader
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 孤儿工具调用兜底文案：只说结果缺失与疑似异常中断，不断言进程被杀。 */
private const val TOOL_RESULT_MISSING =
    "Tool result missing: execution may have been interrupted abnormally."

internal class LlmConfigRequiredException : IllegalStateException("LLM config is required")

/**
 * Okia 实例装配器与生命周期持有者。
 *
 * 负责协议解析、OkiaConfig 组装、Hooks 挂载以及会话流转发。
 */
internal class OkiaSessionAssembler(
    private val toolRegistry: ToolRegistry,
    private val imageLoader: AndroidImageLoader?,
    private val ensureImageSaver: suspend () -> ImageSaver?,
) {
    private companion object {
        const val LOG_TAG = "niki914_zafiro_OkiaSessionAssembler"
    }

    var activeSession: Okia? = null
    var sessionProtocol: LlmProtocol? = null
        private set
    var sessionOpenCodeFree: Boolean? = null
        private set

    private val conversationForwardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationFlow = MutableStateFlow<Conversation?>(null)
    private var sessionForwardJob: Job? = null

    val currentConversation: StateFlow<Conversation?> get() = conversationFlow

    var okiaFactory: LLMController.OkiaFactory =
        LLMController.OkiaFactory { protocol, restore, config ->
            openOkiaWithDefaultProtocol(protocol, restore, config)
        }

    fun resetForTest() {
        kotlinx.coroutines.runBlocking { activeSession?.close() }
        activeSession = null
        sessionProtocol = null
        sessionOpenCodeFree = null
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        conversationFlow.value = null
        okiaFactory = LLMController.OkiaFactory { protocol, restore, config ->
            openOkiaWithDefaultProtocol(protocol, restore, config)
        }
    }

    suspend fun obtainSession(
        protocol: LlmProtocol?,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot? = null,
        forceNew: Boolean = false,
    ): Okia {
        val effectiveEndpoint = config.endpoint.ifBlank {
            protocolDefaultEndpointFallback(protocol ?: LlmProtocol.Default)
        }
        val isOpenCodeFree = isOpenCodeFreeEndpoint(effectiveEndpoint)
        if (!forceNew && restore == null) {
            activeSession?.takeIf { sessionProtocol == protocol && sessionOpenCodeFree == isOpenCodeFree }
                ?.let { return it }
        }
        val carried = restore ?: activeSession?.takeIf {
            sessionProtocol != protocol || sessionOpenCodeFree != isOpenCodeFree
        }?.export()
        activeSession?.close()
        return openSession(protocol ?: LlmProtocol.Default, config, carried).also {
            activeSession = it
            sessionProtocol = protocol
            sessionOpenCodeFree = isOpenCodeFree
            forwardConversation(it)
        }
    }

    private suspend fun openSession(
        protocol: LlmProtocol,
        config: ResolvedLlmConfig,
        restore: SessionSnapshot?,
    ): Okia = okiaFactory.create(protocol, restore, config)

    private fun forwardConversation(session: Okia) {
        sessionForwardJob?.cancel()
        sessionForwardJob = conversationForwardScope.launch {
            session.conversation.collect { conversationFlow.value = it }
        }
    }

    suspend fun closeSession() {
        activeSession?.close()
        activeSession = null
        sessionProtocol = null
        sessionOpenCodeFree = null
        sessionForwardJob?.cancel()
        sessionForwardJob = null
        conversationFlow.value = null
    }

    suspend fun resetConversation() {
        PyRuntime.kill()
        TerminalSessionPool.closeAll()
        SessionRegistry.clear()
        closeSession()
    }

    suspend fun stopCurrentRound() {
        activeSession?.stop()
    }

    private suspend fun openOkiaWithDefaultProtocol(
        protocol: LlmProtocol,
        restore: SessionSnapshot?,
        config: ResolvedLlmConfig,
    ): Okia {
        val endpoint = config.endpoint.ifBlank { protocolDefaultEndpointFallback(protocol) }
        val baseProtocol = wireProtocolFor(protocol)
        val isOpenCodeFree = isOpenCodeFreeEndpoint(endpoint)
        val wireProtocol = if (isOpenCodeFree) OpenCodeFreeProtocol(baseProtocol) else baseProtocol
        val saver = ensureImageSaver()
        return Okia.open(wireProtocol, restore) {
            this.endpoint = endpoint
            apiKey = config.apiKey
            model = config.model
            hooks += killToolResourcesHook
            hooks += fixIncompleteToolCallsHook
            idleTimeoutSeconds = config.idleTimeoutSeconds
            retryPolicy = RetryPolicy(maxAttempts = config.retryMaxAttempts)
            maxTokens = config.maxTokens
            toolRegistry = this@OkiaSessionAssembler.toolRegistry
            imageLoader = this@OkiaSessionAssembler.imageLoader
            imageSaver = saver
            supportsImages = imageLoader != null && config.supportsImages
            thinkingLevel = config.thinkingLevel
            proxy = config.proxy
            if (isOpenCodeFree) {
                headers = OpenCodeFreeSupport.headers()
            }
        }
    }

    private fun isOpenCodeFreeEndpoint(endpoint: String): Boolean =
        endpoint.contains("opencode.ai/zen", ignoreCase = true) ||
            endpoint.contains("zen/v1", ignoreCase = true) ||
            endpoint.contains("opencode.ai/inference", ignoreCase = true) ||
            endpoint.contains("inference/v1", ignoreCase = true)

    private class OpenCodeFreeProtocol(private val base: ChatProtocol) : ChatProtocol {
        @Volatile
        private var toolAliases: Map<String, String> = emptyMap()

        override val id: String get() = base.id
        override val defaultEndpoint: String? get() = base.defaultEndpoint
        override fun withCodec(codec: Json): ChatProtocol = base.withCodec(codec)
        override fun useApiKey(apiKey: String): Map<String, String> = emptyMap()
        override suspend fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest {
            val request = base.buildRequest(snapshot, history)
            val body = request.body ?: return request
            val result = OpenCodeFreeSupport.transform(Json.parseToJsonElement(body) as JsonObject)
            toolAliases = result.toolAliases
            val freshHeaders = request.headers.toMutableMap()
            freshHeaders.putAll(OpenCodeFreeSupport.headers())
            return request.copy(
                url = rewriteUrl(request.url, result.responsesPath),
                headers = freshHeaders,
                body = result.body.toString(),
            )
        }

        override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> =
            base.parseStream(rawSseLines).map(::restoreToolAlias)

        /** Undo the `*_pi` aliasing so the loop sees Zafiro's own tool names. */
        private fun restoreToolAlias(event: ProtocolEvent): ProtocolEvent {
            val aliases = toolAliases
            if (aliases.isEmpty()) return event
            return when (event) {
                is ProtocolEvent.ToolCallStarted ->
                    event.copy(toolName = aliases[event.toolName] ?: event.toolName)

                is ProtocolEvent.ToolCallDelta ->
                    event.copy(toolName = aliases[event.toolName] ?: event.toolName)

                is ProtocolEvent.ToolCallReady ->
                    event.copy(toolName = aliases[event.toolName] ?: event.toolName)

                else -> event
            }
        }

        // Free-tier chat lives on /inference/v1 (the /zen/v1 path 403s);
        // muse-class models use the sibling /responses surface.
        private fun rewriteUrl(url: String, responsesPath: Boolean): String {
            val inference = if (url.contains("zen/v1", ignoreCase = true)) {
                url.replace("zen/v1", "inference/v1", ignoreCase = true)
            } else {
                url
            }
            if (!responsesPath) return inference
            return if (inference.contains("/responses", ignoreCase = true)) {
                inference
            } else {
                inference.replace("/chat/completions", "/responses", ignoreCase = true)
            }
        }

        override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
            base.encodeToolResult(call, outcome)
        override val compat: Compat get() = base.compat
    }

    internal fun isImageSupported(configSupportsImages: Boolean): Boolean {
        return imageLoader != null && configSupportsImages
    }

    internal fun protocolDefaultEndpointFallback(protocol: LlmProtocol): String {
        return when (protocol) {
            LlmProtocol.DeepSeek -> "https://api.deepseek.com/chat/completions"
            LlmProtocol.OpenAiChatCompletions -> "https://api.openai.com/v1/chat/completions"
            LlmProtocol.GoogleOpenAi ->
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

            LlmProtocol.OpenAiResponses -> "https://api.openai.com/v1/responses"
            LlmProtocol.AnthropicMessages -> "https://api.anthropic.com/v1/messages"
        }
    }

    private fun wireProtocolFor(protocol: LlmProtocol): ChatProtocol = when (protocol) {
        LlmProtocol.DeepSeek -> OpenAIChatCompletionProtocol()
        LlmProtocol.OpenAiChatCompletions ->
            OpenAIChatCompletionProtocol(Json, OpenAIChatCompletionCompat())

        LlmProtocol.GoogleOpenAi ->
            OpenAIChatCompletionProtocol(Json, GoogleOpenAiCompat())

        LlmProtocol.OpenAiResponses -> OpenAIResponsesProtocol()
        LlmProtocol.AnthropicMessages -> AnthropicMessagesProtocol()
    }

    fun validateLlmConfig(config: RuntimeLlmConfig) {
        if (config.endpoint.isBlank() || config.model.isBlank()) {
            throw LlmConfigRequiredException()
        }
    }

    private val fixIncompleteToolCallsHook = object : Hooks {
        override suspend fun beforeSerialization(request: SerializationHolder) {
            val history = request.history
            val missing = countMissingToolResults(history)
            if (missing > 0) {
                Logger.i(LOG_TAG, "fixIncompleteToolCalls history=${history.size} missing=$missing")
            }
            val fixed = withMissingToolResultsFilled(history)
            if (fixed != history) {
                request.write(request.snapshot, fixed, "fix_incomplete_tool_calls")
                Logger.i(
                    LOG_TAG,
                    "fixIncompleteToolCalls patched ${fixed.size - history.size} results"
                )
            }
        }
    }

    private fun countMissingToolResults(history: List<Message>): Int {
        val answered =
            history.filterIsInstance<Message.ToolResult>().mapTo(mutableSetOf()) { it.callId }
        var missing = 0
        for (message in history) {
            if (message !is Message.Assistant) continue
            missing += message.message.content
                .filterIsInstance<ContentBlock.ToolCall>()
                .count { it.id !in answered }
        }
        return missing
    }

    private fun withMissingToolResultsFilled(history: List<Message>): List<Message> {
        val answered =
            history.filterIsInstance<Message.ToolResult>().mapTo(mutableSetOf()) { it.callId }
        if (answered.isEmpty() && history.none { it is Message.Assistant }) return history
        val patched = mutableListOf<Message>()
        var changed = false
        for (message in history) {
            patched += message
            if (message !is Message.Assistant) continue
            val missing = message.message.content
                .filterIsInstance<ContentBlock.ToolCall>()
                .filter { it.id !in answered }
            if (missing.isEmpty()) continue
            changed = true
            missing.forEach { call ->
                patched += Message.ToolResult(
                    callId = call.id,
                    toolName = call.name,
                    outcome = ToolCallOutcome.Failure(
                        message = TOOL_RESULT_MISSING,
                        content = TOOL_RESULT_MISSING,
                    ),
                )
            }
        }
        return if (changed) patched else history
    }

    private val killToolResourcesHook = object : Hooks {
        override suspend fun beforeStop(calls: List<ContentBlock.ToolCall>) {
            Logger.i(LOG_TAG, "beforeStop killing tool resources dispatchedCalls=${calls.size}")
            PyRuntime.kill()
            TerminalSessionPool.closeAll()
            SessionRegistry.clear()
        }
    }
}
