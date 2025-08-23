package com.github.nicwl.codexclijetbrainsplugin.codex

import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Event
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.EventMsg
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.serialization.SerializationException
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

interface CodexEventListener {
    fun onConnected(model: String) {}
    fun onAgentMessage(text: String) {}
    fun onAgentMessageDelta(delta: String) {}
    fun onBackground(message: String) {}
    fun onTokenUsage(summary: String) {}
    fun onExecApprovalRequest(submissionId: String, command: List<String>, cwd: String, reason: String?) {}
    fun onPatchApprovalRequest(submissionId: String, summary: String) {}
    fun onTurnDiff(unifiedDiff: String) {}
    fun onError(message: String) {}
    // Reasoning stream
    fun onReasoningStart() {}
    fun onReasoningDelta(delta: String) {}
    fun onReasoningSectionBreak() {}
    fun onReasoningEnd() {}
    // MCP tools
    fun onMcpToolsListed(tools: Map<String, String>) {}
}

@Service(Service.Level.PROJECT)
class CodexSessionService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(CodexSessionService::class.java)
    private val listeners = CopyOnWriteArrayList<CodexEventListener>()
    private val jsonCodec = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Volatile private var processHandler: KillableColoredProcessHandler? = null
    @Volatile private var stdin: BufferedWriter? = null

    // Tracks the current model from SessionConfigured
    @Volatile private var modelSlug: String? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var currentApprovalPolicy: String = "on-request"
    @Volatile private var currentSandboxPolicy: String = "workspace-write"

    // Streaming state trackers to avoid duplicate prints
    @Volatile private var sawTextDelta: Boolean = false
    @Volatile private var sawReasoningDelta: Boolean = false
    @Volatile private var reasoningActive: Boolean = false
    @Volatile private var reasoningFinalized: Boolean = false
    @Volatile private var lastFullAgentMessage: String? = null

    data class TokenUsageState(
        var input: Long = 0L,
        var cached: Long = 0L,
        var output: Long = 0L,
        var reasoning: Long = 0L,
        var total: Long = 0L,
    )
    @Volatile private var lastTokenUsage: TokenUsageState = TokenUsageState()

    fun addListener(listener: CodexEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CodexEventListener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean = processHandler?.isProcessTerminated == false

    fun startIfNeeded() {
        if (isRunning()) return
        start()
    }

    @Synchronized
    fun start() {
        if (isRunning()) return
        val settings = CodexSettingsState.getInstance().state
        val args = mutableListOf<String>()
        args += settings.codexPath
        args += "proto"
        // Pass approval/sandbox overrides via -c so the child reflects our settings
        val approvalCli = when (settings.approvalPolicy.lowercase()) {
            "on_request" -> "on-request"
            "on_failure" -> "on-failure"
            else -> settings.approvalPolicy.lowercase()
        }
        args += listOf("-c", "approval_policy=\"$approvalCli\"")
        args += listOf("-c", "sandbox_mode=\"${settings.sandboxMode}\"")
        val cmd = GeneralCommandLine(args)
            .withCharset(StandardCharsets.UTF_8)
        project.basePath?.let { cmd.withWorkDirectory(it) }
        // Use environment similar to user's shell
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        cmd.environment.remove("OPENAI_API_KEY")

        val handler = try {
            log.warn("Starting codex process: ${args.joinToString(" ")} (cwd=${cmd.workDirectory?.path ?: ""})")
            KillableColoredProcessHandler(cmd)
        } catch (t: Throwable) {
            listeners.forEach { it.onError("Failed to start codex: ${t.message}") }
            log.warn("Failed to start codex", t)
            return
        }
        processHandler = handler

        // Connect stdin
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                stdin = BufferedWriter(OutputStreamWriter(handler.processInput, StandardCharsets.UTF_8))
            } catch (t: Throwable) {
                log.warn("Failed to attach stdin", t)
            }
        }

        // Buffer and process lines from stdout
        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}

            override fun processTerminated(event: ProcessEvent) {
                log.warn("codex exited with ${event.exitCode}")
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                when (outputType) {
                    ProcessOutputType.STDOUT -> synchronized(stdoutBuffer) {
                        stdoutBuffer.append(text)
                        var idx: Int
                        while (true) {
                            idx = stdoutBuffer.indexOf("\n")
                            if (idx < 0) break
                            val line = stdoutBuffer.substring(0, idx).trim()
                            stdoutBuffer.delete(0, idx + 1)
                            if (line.isNotEmpty()) {
                                log.warn("[codex stdout line] $line")
                                handleStdoutLine(line)
                            }
                        }
                    }
                    ProcessOutputType.STDERR -> synchronized(stderrBuffer) {
                        stderrBuffer.append(text)
                        var idx: Int
                        while (true) {
                            idx = stderrBuffer.indexOf("\n")
                            if (idx < 0) break
                            val line = stderrBuffer.substring(0, idx).trim()
                            stderrBuffer.delete(0, idx + 1)
                            if (line.isNotEmpty()) {
                                log.warn("[codex stderr line] $line")
                                listeners.forEach { l -> safeSwing { l.onBackground(line) } }
                            }
                        }
                    }
                    else -> { /* ignore other streams */ }
                }
            }
        })

        handler.startNotify()
    }

    private fun handleStdoutLine(line: String) {
        val ev: Event
        try {
            ev = jsonCodec.decodeFromString(Event.serializer(), line)
        } catch (e: SerializationException) {
            log.warn("Could not deserialize codex event from line: $line", e)
            return
        }
        val id = ev.id
        when (val msg = ev.msg) {
            is EventMsg.TaskStarted -> {
                sawTextDelta = false
                sawReasoningDelta = false
                lastFullAgentMessage = null
                if (reasoningActive) {
                    listeners.forEach { l -> safeSwing { l.onReasoningEnd() } }
                    reasoningActive = false
                }
                reasoningFinalized = false
            }

            is EventMsg.SessionConfigured -> {
                modelSlug = msg.model
                sessionId = msg.sessionId
                val model = modelSlug ?: "unknown"
                listeners.forEach { l -> safeSwing { l.onConnected(model) } }
                // Immediately set cwd for subsequent turns
                project.basePath?.let { cwd ->
                    sendOverrideCwd(cwd)
                }
                sawTextDelta = false
                sawReasoningDelta = false
                if (reasoningActive) {
                    listeners.forEach { l -> safeSwing { l.onReasoningEnd() } }
                    reasoningActive = false
                }
                reasoningFinalized = false
            }

            is EventMsg.AgentMessage -> {
                val txt = msg.message
                if (sawTextDelta) {
                    // Finalize the streamed line with a newline, but avoid duplicate full print
                    listeners.forEach { l -> safeSwing { l.onAgentMessage("") } }
                } else {
                    val previous = lastFullAgentMessage
                    if (previous == null || previous != txt) {
                        listeners.forEach { l -> safeSwing { l.onAgentMessage(txt) } }
                        lastFullAgentMessage = txt
                    }
                }
                sawTextDelta = false
                if (reasoningActive) {
                    listeners.forEach { l -> safeSwing { l.onReasoningEnd() } }
                    reasoningActive = false
                }
                // Do not reset reasoningFinalized here; late deltas may still arrive out-of-order.
            }

            is EventMsg.AgentMessageDelta -> {
                val d = msg.delta
                listeners.forEach { l -> safeSwing { l.onAgentMessageDelta(d) } }
                sawTextDelta = true
            }

            is EventMsg.AgentReasoning -> {
                val txt = msg.text
                if (sawReasoningDelta) {
                    // We already streamed deltas; treat this as finalization only.
                    if (reasoningActive) {
                        listeners.forEach { l -> safeSwing { l.onReasoningEnd() } }
                        reasoningActive = false
                    }
                } else if (txt.isNotEmpty()) {
                    if (!reasoningActive) {
                        listeners.forEach { l -> safeSwing { l.onReasoningStart() } }
                        reasoningActive = true
                    }
                    listeners.forEach { l -> safeSwing { l.onReasoningDelta(txt) } }
                    listeners.forEach { l -> safeSwing { l.onReasoningEnd() } }
                    reasoningActive = false
                }
                reasoningFinalized = true
            }

            is EventMsg.AgentReasoningDelta -> {
                if (reasoningFinalized) {
                    // Ignore late deltas after a full reasoning event
                    return
                }
                val d = msg.delta
                if (d.isNotEmpty()) {
                    if (!reasoningActive) {
                        listeners.forEach { l -> safeSwing { l.onReasoningStart() } }
                        reasoningActive = true
                    }
                    listeners.forEach { l -> safeSwing { l.onReasoningDelta(d) } }
                }
                sawReasoningDelta = true
            }

            is EventMsg.AgentReasoningRawContent -> { /* ignored */ }
            is EventMsg.AgentReasoningRawContentDelta -> { /* ignored */ }

            is EventMsg.AgentReasoningSectionBreak -> {
                if (!reasoningFinalized) listeners.forEach { l -> safeSwing { l.onReasoningSectionBreak() } }
            }

            is EventMsg.TokenCount -> {
                val input = msg.inputTokens
                val output = msg.outputTokens
                val cached = msg.cachedInputTokens ?: 0L
                val reasoning = msg.reasoningOutputTokens ?: 0L
                val total = msg.totalTokens
                lastTokenUsage = TokenUsageState(input, cached, output, reasoning, total)
                val summary = buildString {
                    append("Tokens: total=").append(total)
                    append(" input=").append(input - cached)
                    if (cached > 0) append(" (+ ").append(cached).append(" cached)")
                    append(" output=").append(output)
                    if (reasoning > 0) append(" (reasoning ").append(reasoning).append(")")
                }
                listeners.forEach { l -> safeSwing { l.onTokenUsage(summary) } }
            }

            is EventMsg.McpListToolsResponse -> {
                val m = msg.tools.mapValues { it.value.description ?: "" }
                listeners.forEach { l -> safeSwing { l.onMcpToolsListed(m) } }
            }

            is EventMsg.BackgroundEvent -> {
                listeners.forEach { l -> safeSwing { l.onBackground(msg.message) } }
            }

            is EventMsg.ExecApprovalRequest -> {
                listeners.forEach { l -> safeSwing { l.onExecApprovalRequest(id, msg.command, msg.cwd, msg.reason) } }
            }

            is EventMsg.ApplyPatchApprovalRequest -> {
                val summary = buildString {
                    msg.changes.forEach { (path, change) ->
                        val typ = when (change) {
                            is EventMsg.FileChange.Add -> "add"
                            is EventMsg.FileChange.Delete -> "delete"
                            is EventMsg.FileChange.Update -> "update"
                        }
                        append("- ").append(path).append(" -> ").append(typ).append('\n')
                    }
                }
                listeners.forEach { l -> safeSwing { l.onPatchApprovalRequest(id, summary) } }
            }

            is EventMsg.TurnDiff -> {
                listeners.forEach { l -> safeSwing { l.onTurnDiff(msg.unifiedDiff) } }
            }

            is EventMsg.Error -> {
                listeners.forEach { l -> safeSwing { l.onError(msg.message) } }
            }
            else -> {
                // Unhandled event types are ignored for now
            }
        }
    }

    fun sendUserText(text: String) {
        val items = listOf(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        })
        val op = JsonObject().apply {
            addProperty("type", "user_input")
            add("items", Gson().toJsonTree(items))
        }
        sendSubmission(op)
    }

    fun sendInterrupt() {
        val op = JsonObject().apply { addProperty("type", "interrupt") }
        sendSubmission(op)
    }

    fun sendExecApproval(approved: String, submissionIdToApprove: String) {
        val op = JsonObject().apply {
            addProperty("type", "exec_approval")
            addProperty("id", submissionIdToApprove)
            addProperty("decision", approved) // approved|approved_for_session|denied|abort
        }
        sendSubmission(op)
    }

    fun sendPatchApproval(approved: String, submissionIdToApprove: String) {
        val op = JsonObject().apply {
            addProperty("type", "patch_approval")
            addProperty("id", submissionIdToApprove)
            addProperty("decision", approved)
        }
        sendSubmission(op)
    }

    private fun sendOverrideCwd(cwd: String) {
        val op = JsonObject().apply {
            addProperty("type", "override_turn_context")
            addProperty("cwd", cwd)
        }
        sendSubmission(op)
    }

    // Slash commands
    fun sendCompact() {
        val op = JsonObject().apply { addProperty("type", "compact") }
        sendSubmission(op)
    }

    fun sendListMcpTools() {
        val op = JsonObject().apply { addProperty("type", "list_mcp_tools") }
        sendSubmission(op)
    }

    fun sendOverrideModel(model: String) {
        val op = JsonObject().apply {
            addProperty("type", "override_turn_context")
            addProperty("model", model)
        }
        sendSubmission(op)
    }

    fun sendOverrideApproval(policy: String) {
        val op = JsonObject().apply {
            addProperty("type", "override_turn_context")
            addProperty("approval_policy", policy)
        }
        currentApprovalPolicy = when (policy) {
            "on_request" -> "on-request"
            "on-failure", "on_failure" -> "on-failure"
            "untrusted" -> "untrusted"
            "never" -> "never"
            else -> policy
        }
        sendSubmission(op)
    }

    fun getStatusSummary(): String = getStatusRich(null)

    fun getStatusRich(cwd: String?): String {
        val model = modelSlug ?: "unknown"
        val provider = guessProvider(model)
        val t = lastTokenUsage
        val blendedTotal = (t.input - t.cached) + t.output
        val session = sessionId
        val home = System.getProperty("user.home") ?: ""
        val path = (cwd ?: project.basePath ?: "").let { p ->
            if (home.isNotEmpty() && p.startsWith(home)) {
                val suffix = p.removePrefix(home)
                if (suffix.isEmpty()) "~" else "~$suffix"
            } else {
                p
            }
        }
        val auth = readAuth()
        return buildString {
            appendLine("📂 Workspace")
            appendLine("  • Path: ${path.ifBlank { "~" }}")
            appendLine("  • Approval Mode: $currentApprovalPolicy")
            appendLine("  • Sandbox: $currentSandboxPolicy")
            appendLine()

            auth?.let {
                appendLine("👤 Account")
                when (it.mode) {
                    "chatgpt" -> {
                        appendLine("  • Signed in with ChatGPT")
                        it.email?.let { e -> appendLine("  • Login: $e") }
                        appendLine("  • Plan: ${it.plan ?: "Unknown"}")
                        appendLine()
                    }
                    "api_key" -> {
                        appendLine("  • Using API key. Run codex login to use ChatGPT plan")
                        appendLine()
                    }
                }
            }

            appendLine("🧠 Model")
            appendLine("  • Name: $model")
            appendLine("  • Provider: $provider")
            // We don't yet track reasoning effort/summary precisely; omit if unknown
            appendLine()

            appendLine("📊 Token Usage")
            session?.let { appendLine("  • Session ID: $it") }
            append("  • Input: ").append(t.input - t.cached)
            if (t.cached > 0) append(" (+ ").append(t.cached).append(" cached)")
            appendLine()
            appendLine("  • Output: ${t.output}")
            appendLine("  • Total: $blendedTotal")
        }
    }

    private fun guessProvider(model: String): String {
        return when {
            model.startsWith("gpt-") || model.startsWith("gpt") -> "OpenAI"
            model.startsWith("gpt-oss") || model.contains(":") -> "OSS"
            else -> "OpenAI"
        }
    }

    private data class AuthInfo(val mode: String, val email: String?, val plan: String?)

    private fun readAuth(): AuthInfo? {
        return try {
            val home = System.getProperty("user.home") ?: return null
            val f = java.nio.file.Paths.get(home, ".codex", "auth.json").toFile()
            if (!f.isFile) return null
            val json = f.readText()
            val obj = JsonParser.parseString(json).asJsonObject
            val apiKey = obj.get("OPENAI_API_KEY")?.asString
            val tokens = obj.get("tokens")?.asJsonObject
            if (!apiKey.isNullOrBlank()) {
                return AuthInfo("api_key", null, null)
            }
            if (tokens != null) {
                val idToken = tokens.get("id_token")?.asString
                val info = parseJwtClaims(idToken)
                val email = info?.get("email")?.asString
                val plan = info?.getAsJsonObject("https://api.openai.com/auth")
                    ?.get("chatgpt_plan_type")?.asString
                val planTitle = plan?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                return AuthInfo("chatgpt", email, planTitle)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseJwtClaims(jwt: String?): JsonObject? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            val decoder = java.util.Base64.getUrlDecoder()
            val payloadBytes = decoder.decode(parts[1])
            val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
            JsonParser.parseString(payloadStr).asJsonObject
        } catch (_: Throwable) {
            null
        }
    }

    fun restart() {
        dispose()
        start()
    }

    private fun sendSubmission(op: JsonObject) {
        val payload = JsonObject().apply {
            addProperty("id", UUID.randomUUID().toString())
            add("op", op)
        }
        val line = payload.toString()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val w = stdin
                if (w == null) {
                    listeners.forEach { it.onError("Codex is not running.") }
                } else {
                    w.append(line)
                    w.append('\n')
                    w.flush()
                }
            } catch (t: Throwable) {
                log.warn("Failed to write to codex stdin", t)
                listeners.forEach { it.onError("Failed to send to codex: ${t.message}") }
            }
        }
    }

    override fun dispose() {
        try {
            processHandler?.destroyProcess()
        } catch (_: Throwable) {
        }
        processHandler = null
        stdin = null
    }

    private inline fun safeSwing(crossinline r: () -> Unit) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r() else SwingUtilities.invokeLater { r() }
        } catch (_: Throwable) {
        }
    }

}
