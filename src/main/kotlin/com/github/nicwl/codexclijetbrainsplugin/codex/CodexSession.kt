package com.github.nicwl.codexclijetbrainsplugin.codex

import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Event
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.EventMsg
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Op
import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Submission
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import com.intellij.openapi.Disposable
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

class CodexSession(private val project: Project) : Disposable, CodexTransportListener {
    private val log = Logger.getInstance(CodexSession::class.java)
    private val listeners = CopyOnWriteArrayList<CodexEventListener>()
    private val jsonOut = Json { classDiscriminator = "type"; explicitNulls = false }
    private val transport = CodexProcess(project)

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

    fun addListener(listener: CodexEventListener) { listeners.add(listener) }
    fun removeListener(listener: CodexEventListener) { listeners.remove(listener) }

    fun isRunning(): Boolean = transport.isRunning()

    fun startIfNeeded() { if (!isRunning()) start() }

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
        // Note: codex proto does not accept a `-c cwd=...` override. It derives cwd from process working directory.

        transport.addListener(this)
        transport.start(args, project.basePath)
    }

    override fun onEvent(ev: Event) {
        val id = ev.id
        when (val msg = ev.msg) {
            is EventMsg.TaskComplete -> {
                listeners.forEach { l -> safeSwing { l.onTaskComplete(msg.lastAgentMessage) } }
                try {
                    val toolVisible = try {
                        val tw = ToolWindowManager.getInstance(project).getToolWindow("Codex")
                        tw?.isVisible == true
                    } catch (_: Throwable) { false }

                    val isIdeActive = try {
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
                    } catch (_: Throwable) { true }

                    if (isIdeActive && toolVisible) return

                    val title = MyBundle.message("notification.taskComplete.title")
                    val content = msg.lastAgentMessage?.trim()?.takeIf { it.isNotEmpty() }?.take(300)
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
                } catch (_: Throwable) { }
            }
            is EventMsg.TaskStarted -> {
                listeners.forEach { l -> safeSwing { l.onTaskStarted() } }
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
                project.basePath?.let { cwd -> sendOverrideCwd(cwd) }
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
            }
            is EventMsg.AgentMessageDelta -> {
                val d = msg.delta
                listeners.forEach { l -> safeSwing { l.onAgentMessageDelta(d) } }
                sawTextDelta = true
            }
            is EventMsg.AgentReasoning -> {
                if (reasoningFinalized) return
                val txt = msg.text
                if (txt.isNotEmpty()) {
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
                if (reasoningFinalized) return
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
            is EventMsg.TurnAborted -> {
                listeners.forEach { l -> safeSwing { l.onTaskComplete(null) } }
            }
            else -> { /* ignore */ }
        }
    }

    override fun onStdErr(line: String) {
        listeners.forEach { l -> safeSwing { l.onBackground(line) } }
    }

    fun sendUserText(text: String) {
        val op = Op.UserInput(items = listOf(com.github.nicwl.codexclijetbrainsplugin.codex.protocol.InputItem.Text(text)))
        sendSubmission(op)
    }

    fun sendInterrupt() { sendSubmission(Op.Interrupt) }

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

    private fun sendOverrideCwd(cwd: String) { sendSubmission(Op.OverrideTurnContext(cwd = cwd)) }
    fun sendCompact() { sendSubmission(Op.Compact) }
    fun sendListMcpTools() { sendSubmission(Op.ListMcpTools) }
    fun sendOverrideModel(model: String) { sendSubmission(Op.OverrideTurnContext(model = model)) }

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
                        appendLine("  • Plan: ${it.plan ?: "unknown"}")
                    }
                    "openai" -> {
                        appendLine("  • Using OpenAI API key")
                        appendLine("  • Plan: ${it.plan ?: "unknown"}")
                    }
                    else -> {
                        appendLine("  • Auth: ${it.mode}")
                        if (!it.info.isJsonNull) appendLine("  • Info: ${it.info}")
                    }
                }
                appendLine()
            }

            appendLine("🤖 Model")
            appendLine("  • Slug: $model")
            appendLine("  • Provider: $provider")
            if (blendedTotal > 0) appendLine("  • Last Turn Tokens: $blendedTotal")
            session?.let { appendLine("  • Session: $it") }
        }
    }

    private fun guessProvider(model: String): String = when {
        model.contains("o4", ignoreCase = true) -> "OpenAI"
        model.contains("gpt", ignoreCase = true) -> "OpenAI"
        model.contains("sonnet", ignoreCase = true) -> "Anthropic"
        model.contains("claude", ignoreCase = true) -> "Anthropic"
        model.contains("deepseek", ignoreCase = true) -> "DeepSeek"
        model.contains("mistral", ignoreCase = true) -> "Mistral"
        model.contains("local", ignoreCase = true) -> "Local"
        else -> ""
    }

    private data class AuthInfo(val mode: String, val info: JsonObject, val plan: String?, val email: String?)

    private fun readAuth(): AuthInfo? {
        return try {
            val env = System.getenv("CODEX_AUTH_TOKEN")
            if (env.isNullOrBlank()) return null
            val parts = env.split('.')
            if (parts.size < 2) return null
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
        }?.let { payload ->
            val mode = payload.get("mode")?.asString ?: "unknown"
            val plan = payload.get("plan")?.asString
            val email = payload.get("email")?.asString
            AuthInfo(mode = mode, info = payload, plan = plan, email = email)
        }
    }

    fun restart() { dispose(); start() }

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
        } catch (_: Throwable) {}
    }
}
