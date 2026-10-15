package com.niki914.zafiro.chat.agentic.python

import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.util.ToolOutputTruncator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * CustomPyTool 执行器：把 LLM 的参数 JSON 经 [CustomPyToolHarness.buildRunner] 拼接后
 * 交给 [PyRuntime.exec]（:python 进程）。输出即 stdout（截断+导出由
 * [ToolOutputTruncator.filterForAgent] 统一处理）。结果用 {"ok":...} JSON 约定，
 * 与 BuiltinToolResult 对齐，由 LocalToolResultClassifier 拆 Success/Failure。
 *
 * 执行前过 [ToolExecutionPreflight]（规则 + 路径权限）：保存期校验过的不算数，
 * 规则可能事后新增；参数里的路径更是只有执行期才看得到。
 */
class CustomPyToolExecutor(
    private val exec: suspend (code: String, timeoutMs: Long) -> PyExecOutput = PyRuntime::exec,
    private val preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
    /** 截断导出目录，测试可注入临时目录；默认 filesDir/tool_output。 */
    private val exportDir: File? = ToolOutputTruncator.defaultExportDir(),
) {
    suspend fun execute(tool: LocalTool.Py, argumentsJson: String): String {
        val args = parseArguments(argumentsJson)
        val decision = preflight.evaluate(tool.code + "\n" + argumentsJson, toolName = tool.name)
        if (!decision.allowed) {
            return failureJson(
                tool.name,
                "COMMAND_BLOCKED",
                decision.reason.ifBlank { "Code blocked by execution rule." },
            )
        }
        preflight.ensurePathAccess(tool.code + "\n" + argumentsJson)
        return try {
            val result = exec(CustomPyToolHarness.buildRunner(tool.code, args), tool.timeoutMs)
            val stdout = ToolOutputTruncator.filterForAgent(
                fullContent = result.output,
                existingFile = result.file,
                exportDir = exportDir,
            )
            // stdout 空 = 什么都没产出。Python 层的异常只会落到 stderr，
            // 归到这里就成「成功但无内容」，模型拿不到失败信号只能瞎猜。
            // 工具若自己打了结构化错误（{"error":{...}}），那里非空，不走这条。
            if (stdout.isBlank()) {
                return failureJson(
                    tool.name,
                    "NO_OUTPUT",
                    "Tool produced no output. " + stderrTail(result.output),
                )
            }
            // 工具自己打 {"error":{...}} 时要提到外层，否则它被裹进 stdout 字符串，
            // 外层 ok=true 会让分类器判成成功——错误就永远到不了模型。
            structuredError(tool.name, stdout)?.let { return it }
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tool" to JsonPrimitive(tool.name),
                    "stdout" to JsonPrimitive(stdout),
                )
            ).toString()
        } catch (e: TimeoutCancellationException) {
            failureJson(
                tool.name,
                "TIMEOUT",
                "Execution timed out after ${tool.timeoutMs / 1000}s."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failureJson(tool.name, "PYTHON_ERROR", t.message ?: "Python execution failed.")
        }
    }

    private fun parseArguments(argumentsJson: String): String {
        if (argumentsJson.isBlank()) return "{}"
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return "{}"
        }
        return if (element is JsonObject) argumentsJson else "{}"
    }

    private fun failureJson(name: String, code: String, message: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "tool" to JsonPrimitive(name),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
            )
        ).toString()
    }

    /**
     * runtime.py 把 stderr 拼在 stdout 之后（`\n--- stderr ---\n`）。
     * 空输出时把那段捞出来附在错误里，否则模型只知道"没输出"，猜不到真实异常。
     */
    private fun stderrTail(output: String): String {
        val marker = output.lastIndexOf(STDERR_MARKER)
        if (marker < 0) return ""
        return output.substring(marker + STDERR_MARKER.length).trim().takeLast(STDERR_TAIL_CHARS)
    }

    /** stdout 本身是 {"error":{"code","message"}} 时转成 failureJson，否则返回 null 走成功分支。 */
    private fun structuredError(name: String, stdout: String): String? {
        val error = runCatching {
            (Json.parseToJsonElement(stdout.trim()) as? JsonObject)?.get("error") as? JsonObject
        }.getOrNull() ?: return null
        val code = error["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "TOOL_ERROR"
        val message = error["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: code
        return failureJson(name, code, message)
    }
}

private const val STDERR_MARKER = "\n--- stderr ---\n"
private const val STDERR_TAIL_CHARS = 400
