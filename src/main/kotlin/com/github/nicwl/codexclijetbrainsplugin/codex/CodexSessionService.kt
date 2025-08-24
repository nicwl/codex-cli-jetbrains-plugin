package com.github.nicwl.codexclijetbrainsplugin.codex

import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Event
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.EventMsg
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Op
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Submission
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.wm.ToolWindowManager
import com.github.nicwl.codexclijetbrainsplugin.MyBundle
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

    // Exec command streaming (chat bubble console)
    fun onExecCommandBegin(callId: String, commandDisplay: String) {}
    fun onExecCommandOutputDelta(callId: String, isStdErr: Boolean, bytes: ByteArray) {}
    fun onExecCommandEnd(
        callId: String,
        exitCode: Int,
        durationSecs: Long,
        durationNanos: Int,
        stdout: String,
        stderr: String,
    ) {}
}

@Service(Service.Level.PROJECT)
class CodexSessionService(private val project: Project) : Disposable, CodexTransportListener {
    private val log = Logger.getInstance(CodexSessionService::class.java)
    private val listeners = CopyOnWriteArrayList<CodexEventListener>()
    private val jsonOut = Json { classDiscriminator = "type"; explicitNulls = false }
    private val transport = project.getService(CodexProcessService::class.java)

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

    fun isRunning(): Boolean = transport.isRunning()

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
        // Ensure the agent does not spawn external notifications; we surface them via the IDE
        args += listOf("-c", "notify=[]")
        // Note: codex proto does not accept a `-c cwd=...` override. It derives cwd
        // from the process working directory, which we set via CodexProcessService.
        // Register as transport listener and start the process
        transport.addListener(this)
        transport.start(args, project.basePath)
    }

    // Invoked by CodexProcessService
    override fun onEvent(ev: Event) {
        val id = ev.id
        when (val msg = ev.msg) {
            is EventMsg.TaskComplete -> {
                // Notify the user appropriately:
                // - If IDE is focused and the Codex tool window is visible, do nothing (the UI already shows it)
                // - If IDE is focused but tool window is hidden, show an in-IDE balloon
                // - If IDE is not focused, send a system notification
                try {
                    val toolVisible = try {
                        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Codex")
                        tw?.isVisible == true
                    } catch (_: Throwable) { false }

                    val isIdeActive = try {
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
                    } catch (_: Throwable) { true }

                    if (isIdeActive && toolVisible) {
                        return
                    }

                    val title = MyBundle.message("notification.taskComplete.title")
                    val content = msg.lastAgentMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(300)

                    // Show a notification when IDE not focused or tool window is hidden.
                    // The IDE will use system notifications if enabled in settings for this group.
                    if (!isIdeActive || !toolVisible) {
                        val group = NotificationGroupManager.getInstance().getNotificationGroup("Codex Notifications")
                        val n = if (content == null) {
                            group.createNotification(title, NotificationType.INFORMATION)
                        } else {
                            group.createNotification(title, content, NotificationType.INFORMATION)
                        }
                        n.addAction(NotificationAction.createSimple(MyBundle.message("notification.openToolWindow.action")) {
                            ToolWindowManager.getInstance(project).getToolWindow("Codex")?.activate(null, true)
                        })
                        n.notify(project)
                    }
                } catch (_: Throwable) {
                    // Best-effort: avoid breaking the event loop due to notifications
                }
            }
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

            is EventMsg.ExecCommandBegin -> {
                val display = runCatching {
                    // Prefer parsed_cmd (first element's `cmd`), otherwise join raw command list
                    val first = msg.parsedCmd.firstOrNull()
                    when (first) {
                        is EventMsg.ParsedCommand.Read -> first.cmd
                        is EventMsg.ParsedCommand.ListFiles -> first.cmd
                        is EventMsg.ParsedCommand.Search -> first.cmd
                        is EventMsg.ParsedCommand.Format -> first.cmd
                        is EventMsg.ParsedCommand.Test -> first.cmd
                        is EventMsg.ParsedCommand.Lint -> first.cmd
                        is EventMsg.ParsedCommand.Noop -> first.cmd
                        is EventMsg.ParsedCommand.Unknown -> first.cmd
                        null -> msg.command.joinToString(" ")
                    }
                }.getOrElse { msg.command.joinToString(" ") }
                listeners.forEach { l -> safeSwing { l.onExecCommandBegin(msg.callId, display) } }
            }

            is EventMsg.ExecCommandOutputDelta -> {
                val isErr = msg.stream == EventMsg.ExecOutputStream.Stderr
                val bytes = msg.chunk.toByteArray()
                listeners.forEach { l -> safeSwing { l.onExecCommandOutputDelta(msg.callId, isErr, bytes) } }
            }

            is EventMsg.ExecCommandEnd -> {
                listeners.forEach { l -> safeSwing {
                    l.onExecCommandEnd(
                        msg.callId,
                        msg.exitCode,
                        msg.duration.secs,
                        msg.duration.nanos,
                        msg.stdout,
                        msg.stderr,
                    )
                } }
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
            else -> { /* ignore unhandled */ }
        }
    }

    override fun onStdErr(line: String) {
        listeners.forEach { l -> safeSwing { l.onBackground(line) } }
    }

    fun sendUserText(text: String) {
        val op = Op.UserInput(items = listOf(com.github.nicwl.codexclijetbrainsplugin.codex.protocol.InputItem.Text(text)))
        sendSubmission(op)
    }

    fun sendInterrupt() {
        sendSubmission(Op.Interrupt)
    }

    fun sendExecApproval(approved: String, submissionIdToApprove: String) {
        val decision = when (approved) {
            "approved" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Approved
            "approved_for_session" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.ApprovedForSession
            "denied" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Denied
            else -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Abort
        }
        sendSubmission(Op.ExecApproval(id = submissionIdToApprove, decision = decision))
    }

    fun sendPatchApproval(approved: String, submissionIdToApprove: String) {
        val decision = when (approved) {
            "approved" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Approved
            "approved_for_session" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.ApprovedForSession
            "denied" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Denied
            else -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.ReviewDecision.Abort
        }
        sendSubmission(Op.PatchApproval(id = submissionIdToApprove, decision = decision))
    }

    private fun sendOverrideCwd(cwd: String) {
        sendSubmission(Op.OverrideTurnContext(cwd = cwd))
    }

    // Slash commands
    fun sendCompact() {
        sendSubmission(Op.Compact)
    }

    fun sendListMcpTools() {
        sendSubmission(Op.ListMcpTools)
    }

    fun sendOverrideModel(model: String) {
        sendSubmission(Op.OverrideTurnContext(model = model))
    }

    fun sendOverrideApproval(policy: String) {
        val op = Op.OverrideTurnContext(approvalPolicy = when (policy) {
            "on_request" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.AskForApproval.OnRequest
            "on-failure", "on_failure" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.AskForApproval.OnFailure
            "untrusted" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.AskForApproval.UnlessTrusted
            "never" -> com.github.nicwl.codexclijetbrainsplugin.codex.protocol.AskForApproval.Never
            else -> null
        })
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
            // Respect CODEX_HOME if set; otherwise default to ~/.codex
            val codexHome = System.getenv("CODEX_HOME")
                ?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home")?.let { java.nio.file.Paths.get(it, ".codex").toString() }
                    ?: return null)
            val f = java.nio.file.Paths.get(codexHome, "auth.json").toFile()
            if (!f.isFile) {
                // Fall back to env var if no auth.json exists, matching server behavior
                val envKey = System.getenv("OPENAI_API_KEY")
                return if (!envKey.isNullOrBlank()) AuthInfo("api_key", null, null) else null
            }
            val json = f.readText()
            val obj = JsonParser.parseString(json).asJsonObject
            val apiKey = obj.get("OPENAI_API_KEY").let { el ->
                if (el == null || el.isJsonNull) null else runCatching { el.asString }.getOrNull()
            }
            val tokensObj = obj.get("tokens").let { el ->
                if (el == null || el.isJsonNull || !el.isJsonObject) null else el.asJsonObject
            }

            // Prefer ChatGPT tokens when present; otherwise fall back to API key.
            if (tokensObj != null) {
                val idToken = tokensObj.get("id_token").let { el ->
                    if (el == null || el.isJsonNull) null else runCatching { el.asString }.getOrNull()
                }
                val info = parseJwtClaims(idToken)
                val email = info?.get("email").let { el ->
                    if (el == null || el.isJsonNull) null else runCatching { el.asString }.getOrNull()
                }
                val authObj = info?.get("https://api.openai.com/auth").let { el ->
                    if (el == null || el.isJsonNull || !el.isJsonObject) null else el.asJsonObject
                }
                val plan = authObj?.get("chatgpt_plan_type").let { el ->
                    if (el == null || el.isJsonNull) null else runCatching { el.asString }.getOrNull()
                }
                val planTitle = plan?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                return AuthInfo("chatgpt", email, planTitle)
            }
            if (!apiKey.isNullOrBlank()) return AuthInfo("api_key", null, null)
            null
        } catch (t: Throwable) {
            log.error("Could not read auth info", t)
            null
        }
    }

    private fun parseJwtClaims(jwt: String?): JsonObject? {
        if (jwt.isNullOrBlank()) return null
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return try {
            val payloadB64 = parts[1]
            // Base64 URL-safe without padding; add '=' padding to make length a multiple of 4
            val padLen = (4 - payloadB64.length % 4) % 4
            val padded = payloadB64 + "=".repeat(padLen)
            val decoder = java.util.Base64.getUrlDecoder()
            val payloadBytes = decoder.decode(padded)
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

    private fun sendSubmission(op: Op) {
        val submission = Submission(id = UUID.randomUUID().toString(), op = op)
        val line = jsonOut.encodeToString(Submission.serializer(), submission)
        transport.sendRaw(line)
    }

    override fun dispose() {
        transport.removeListener(this)
        try { transport.dispose() } catch (_: Throwable) {}
    }

    private inline fun safeSwing(crossinline r: () -> Unit) {
        try {
            if (SwingUtilities.isEventDispatchThread()) r() else SwingUtilities.invokeLater { r() }
        } catch (_: Throwable) {
        }
    }

}
