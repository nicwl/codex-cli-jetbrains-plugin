package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.github.nicwl.codexclijetbrainsplugin.MyBundle
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexEventListener
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSessionService
import com.github.nicwl.codexclijetbrainsplugin.settings.CodexSettingsConfigurable
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.TextFieldWithAutoCompletionListProvider
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.EditorModificationUtil
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.BoxLayout
import javax.swing.KeyStroke
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import javax.swing.SwingUtilities
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator

class CodexToolWindowPanel(private val project: Project) : CodexEventListener, Disposable {
    private val log = Logger.getInstance(CodexToolWindowPanel::class.java)
    private val session = project.getService(CodexSessionService::class.java)
    private val chat = ChatView(project)
    private val workingIndicator = WorkingIndicator { session.sendInterrupt() }
    // Lazily initialize to ensure slashCommands is constructed first
    private val input: TextFieldWithAutoCompletion<String> by lazy { createSlashAutoCompleteField() }
    private val statusLabel = JLabel(MyBundle.message("status.tokens.initial"))
    private val tokenAgg = TokenAggregator()
    private var assistantStreaming = false
    private var lastAssistantFinal: String? = null
    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
    }
    // no manual lookup state; rely on IDE completion

    private data class SlashCommand(val id: String, val title: String, val description: String, val needsArg: Boolean = false)
    private val slashCommands = listOf(
        SlashCommand("compact", "Compact context", "Summarize and compress context for the next turns"),
        SlashCommand("status", "Show status", "Show Codex session status and model info"),
        SlashCommand("diff", "Show git diff", "Display current workspace git diff with colors"),
        SlashCommand("mcp", "List MCP tools", "List configured MCP servers and tools"),
        SlashCommand("new", "New session", "Start a fresh Codex session"),
        SlashCommand("model", "Switch model", "Override the model for next turns", needsArg = true),
        SlashCommand("approvals", "Set approval mode", "Change approvals: untrusted | on_request | on_failure | never", needsArg = true),
        SlashCommand("quit", "Quit", "Close the Codex tool window")
    )

    val component = rootPanel

    init {
        session.addListener(this)

        val toolbar = createToolbar()
        rootPanel.add(toolbar, BorderLayout.NORTH)
        // Install overlay indicator onto the chat view for floating bottom rendering
        chat.setWorkingIndicator(workingIndicator)
        rootPanel.add(chat, BorderLayout.CENTER)

        // Configure chat input (EditorTextField) with soft wraps and register Enter shortcut
        input.addSettingsProvider { ed ->
            (ed as? EditorEx)?.settings?.isUseSoftWraps = true
            installEnterShortcut()
        }
        val inputPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(6) }
        val sendButton = JButton(MyBundle.message("button.send")).apply {
            addActionListener { sendCurrentInput() }
        }
        inputPanel.add(input, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)
        // Stack input above a simple status bar
        val southStack = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val statusPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(statusLabel, BorderLayout.WEST)
        }
        southStack.add(inputPanel)
        southStack.add(statusPanel)
        rootPanel.add(southStack, BorderLayout.SOUTH)

        // Ensure shortcut is registered even if settings provider didn't run yet
        installEnterShortcut()

        // Interrupt keybindings
        val interruptAction = object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                session.sendInterrupt()
            }
        }
        rootPanel.actionMap.put("codex-interrupt", interruptAction)
        val im = rootPanel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val inputIm = input.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
        input.actionMap.put("codex-interrupt", interruptAction)
        // Add Esc to match TUI hint and provide a universal interrupt key
        run {
            val ksEsc = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
            im.put(ksEsc, "codex-interrupt")
            inputIm.put(ksEsc, "codex-interrupt")
        }
        if (SystemInfo.isMac) {
            // macOS: Ctrl+C
            val ks = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)
            im.put(ks, "codex-interrupt")
            inputIm.put(ks, "codex-interrupt")
        } else {
            // Windows/Linux: Ctrl+Break (Pause) and Ctrl+Cancel as fallbacks
            val ksPause = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_PAUSE, InputEvent.CTRL_DOWN_MASK)
            val ksCancel = javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_CANCEL, InputEvent.CTRL_DOWN_MASK)
            im.put(ksPause, "codex-interrupt")
            im.put(ksCancel, "codex-interrupt")
            inputIm.put(ksPause, "codex-interrupt")
            inputIm.put(ksCancel, "codex-interrupt")
        }
        updateTokenStatus()
    }

    private fun createToolbar(): javax.swing.JComponent {
        val settingsAction = object : DumbAwareAction(
            MyBundle.message("action.settings"),
            MyBundle.message("action.settings.description"),
            AllIcons.General.Gear
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexSettingsConfigurable::class.java)
            }
        }
        val group = DefaultActionGroup().apply { add(settingsAction) }
        val toolbar = ActionManager.getInstance().createActionToolbar("CodexToolbar", group, true)
        toolbar.targetComponent = rootPanel
        return toolbar.component
    }

    fun ensureStarted() {
        session.startIfNeeded()
    }

    private fun createSlashAutoCompleteField(): TextFieldWithAutoCompletion<String> {
        // Provide native auto-completion backed by a simple list provider.
        val ids = slashCommands.map { it.id }
        val provider = object : TextFieldWithAutoCompletionListProvider<String>(ids) {
            override fun getLookupString(item: String): String = item

            override fun getPrefix(text: String, offset: Int): String? {
                // Only trigger suggestions when the whole input is a single token starting with '/'
                if (!text.startsWith('/')) return null
                if (text.indexOfFirst { it.isWhitespace() } >= 0) return null
                val end = offset.coerceAtMost(text.length)
                val beforeCaret = text.substring(0, end)
                // Prefix is the part after the leading slash
                return beforeCaret.removePrefix("/")
            }

            override fun createLookupBuilder(item: String): LookupElementBuilder {
                val sc = slashCommands.firstOrNull { it.id == item }
                val builder = LookupElementBuilder.create(item)
                    .withPresentableText("/$item")
                    .withTailText(sc?.let { "  —  ${it.title}" } ?: "", true)
                    .withTypeText(sc?.description ?: "", true)
                return builder.withInsertHandler { ctx, _ ->
                    // Add a trailing space for commands that require an argument.
                    val needsArg = sc?.needsArg == true
                    if (needsArg) {
                        val ed = ctx.editor
                        val doc = ctx.document
                        val pos = ed.caretModel.offset
                        val needsSpace = pos == doc.textLength || doc.charsSequence.getOrNull(pos) != ' '
                        if (needsSpace) {
                            doc.insertString(pos, " ")
                            ed.caretModel.moveToOffset(pos + 1)
                        }
                    }
                }
            }
        }
        val field = TextFieldWithAutoCompletion(project, provider, false, null).apply { setOneLineMode(false) }
        // Ensure auto-popup is enabled on typing (including when prefix is empty after '/')
        TextFieldWithAutoCompletion.installCompletion(field.document, project, provider, true)
        // Also trigger auto-popup immediately after a solitary '/' is typed
        field.addDocumentListener(object : DocumentListener {
            private var fired = false
            override fun documentChanged(event: DocumentEvent) {
                val ed = field.editor ?: return
                val txt = ed.document.text
                if (txt == "/") {
                    if (!fired && LookupManager.getActiveLookup(ed) == null) {
                        fired = true
                        AutoPopupController.getInstance(project).scheduleAutoPopup(ed)
                    }
                } else {
                    fired = false
                }
            }
        })
        return field
    }

    private fun sendCurrentInput() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            if (handleSlashCommand(text)) {
                input.text = ""
                return
            }
        }
        chat.addUserMessage(text)
        // Show working indicator immediately for responsiveness
        if (!workingIndicator.isActive()) workingIndicator.start()
        session.sendUserText(text)
        input.text = ""
    }

    private fun installEnterShortcut() {
        val comp = input as javax.swing.JComponent
        if (comp.getClientProperty("codex-enter-shortcut-installed") == true) return
        comp.putClientProperty("codex-enter-shortcut-installed", true)

        val sendOnEnter = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val ed = input.editor
                if (ed != null) {
                    val lookup = LookupManager.getActiveLookup(ed)
                    if (lookup != null) {
                        val handler = com.intellij.openapi.editor.actionSystem.EditorActionManager
                            .getInstance()
                            .getActionHandler(com.intellij.openapi.actionSystem.IdeActions.ACTION_CHOOSE_LOOKUP_ITEM)
                        handler.execute(ed, null, e.dataContext)
                        return
                    }
                }
                sendCurrentInput()
            }
        }
        val shortcut = com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null)
        sendOnEnter.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(shortcut), comp)
    }

    // Removed global AWT KeyEventDispatcher; rely on per-component shortcuts.

    private fun handleSlashCommand(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"))
        val cmd = parts.firstOrNull()?.removePrefix("/")?.lowercase() ?: return false
        val args = parts.drop(1)
        when (cmd) {
            "compact" -> {
                chat.addNote(MyBundle.message("chat.compact.note"))
                session.sendCompact()
                return true
            }

            "status" -> {
                chat.addStatusBlock(session.getStatusRich(project.basePath))
                return true
            }

            "diff" -> {
                chat.addNote(MyBundle.message("chat.diff.note"))
                computeGitDiff()
                return true
            }

            "mcp" -> {
                chat.addNote(MyBundle.message("chat.mcp.note"))
                session.sendListMcpTools()
                return true
            }

            "new" -> {
                chat.addNote(MyBundle.message("chat.new.note"))
                session.restart()
                tokenAgg.reset()
                updateTokenStatus()
                assistantStreaming = false
                lastAssistantFinal = null
                return true
            }

            "model" -> {
                val slug = args.firstOrNull()
                if (slug.isNullOrBlank()) {
                    chat.addNote(MyBundle.message("chat.model.usage"))
                } else {
                    chat.addNote(MyBundle.message("chat.model.switch.note", slug))
                    session.sendOverrideModel(slug)
                }
                return true
            }

            "approvals" -> {
                val policy = args.firstOrNull()
                val mapped = when (policy?.lowercase()) {
                    "on-request", "on_request" -> "on_request"
                    "on-failure", "on_failure" -> "on_failure"
                    "untrusted", "unless-trusted", "unless_trusted" -> "untrusted"
                    "never" -> "never"
                    else -> null
                }
                if (mapped == null) chat.addNote(MyBundle.message("chat.approvals.usage"))
                else { chat.addNote(MyBundle.message("chat.approvals.set", mapped)); session.sendOverrideApproval(mapped) }
                return true
            }

            "quit" -> {
                chat.addNote(MyBundle.message("chat.quit.note"))
                return true
            }

            else -> return false
        }
    }

    private fun computeGitDiff() {
        val baseDir = project.basePath
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, MyBundle.message("chat.diff.note"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val insideRepo = runCmd(listOf("git", "rev-parse", "--is-inside-work-tree"), baseDir).first
                    if (!insideRepo) {
                        chat.addNote(MyBundle.message("diff.notRepo"))
                        return
                    }
                    val tracked = runCmdCapture(listOf("git", "diff", "--color"), baseDir)
                    val untrackedList = runCmdCapture(listOf("git", "ls-files", "--others", "--exclude-standard"), baseDir)
                    val nullPath = if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null"
                    val untrackedDiff = StringBuilder()
                    untrackedList.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { file ->
                        if (indicator.isCanceled) return
                        val d = runCmdCapture(listOf("git", "diff", "--color", "--no-index", "--", nullPath, file), baseDir)
                        untrackedDiff.append(d)
                    }
                    val out = tracked + untrackedDiff.toString()
                    if (out.isBlank()) chat.addNote(MyBundle.message("diff.noChanges")) else chat.addDiff(out)
                } catch (t: Throwable) {
                    chat.addError(MyBundle.message("diff.failed", t.message ?: ""))
                }
            }
        })
    }

    private fun runCmdCapture(cmd: List<String>, dir: String?): String {
        val pb = ProcessBuilder(cmd)
        if (dir != null) pb.directory(java.io.File(dir))
        pb.redirectErrorStream(false)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun runCmd(cmd: List<String>, dir: String?): Pair<Boolean, String> {
        val pb = ProcessBuilder(cmd)
        if (dir != null) pb.directory(java.io.File(dir))
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor() == 0
        return Pair(ok, out)
    }

    // CodexEventListener implementation
    override fun onConnected(model: String) {
        chat.addNote(MyBundle.message("connected.model", model))
        tokenAgg.reset()
        updateTokenStatus()
        assistantStreaming = false
        lastAssistantFinal = null
    }

    override fun onAgentMessage(text: String) {
        if (text == lastAssistantFinal) {
            assistantStreaming = false
            return
        }
        chat.finalizeAssistantMessage(text)
        lastAssistantFinal = text
        assistantStreaming = false
    }

    override fun onAgentMessageDelta(delta: String) {
        assistantStreaming = true
        lastAssistantFinal = null
        chat.appendAssistantDelta(delta)
    }

    override fun onBackground(message: String) {
        workingIndicator.updateText(message)
        chat.addNote(message)
    }

    override fun onTokenUsage(summary: String) {
        tokenAgg.ingestSummary(summary)
        updateTokenStatus()
    }

    override fun onExecApprovalRequest(submissionId: String, command: List<String>, cwd: String, reason: String?) {
        chat.addExecApprovalPrompt(
            command = command,
            cwd = cwd,
            reason = reason,
            onApprove = { session.sendExecApproval("approved", submissionId) },
            onApproveSession = { session.sendExecApproval("approved_for_session", submissionId) },
            onDeny = { session.sendExecApproval("denied", submissionId) },
            onAbort = { session.sendExecApproval("abort", submissionId) },
        )
    }

    override fun onPatchApprovalRequest(submissionId: String, summary: String) {
        chat.addPatchApprovalPrompt(
            summary = summary,
            onApprove = { session.sendPatchApproval("approved", submissionId) },
            onApproveSession = { session.sendPatchApproval("approved_for_session", submissionId) },
            onDeny = { session.sendPatchApproval("denied", submissionId) },
            onAbort = { session.sendPatchApproval("abort", submissionId) },
        )
    }

    override fun onTurnDiff(unifiedDiff: String) { chat.addDiff(unifiedDiff) }

    override fun onError(message: String) { chat.addError(message) }

    // Reasoning stream
    override fun onReasoningStart() { chat.beginReasoning() }
    override fun onReasoningDelta(delta: String) { chat.appendReasoning(delta) }
    override fun onReasoningSectionBreak() { chat.reasoningSectionBreak() }
    override fun onReasoningEnd() { chat.endReasoning() }

    override fun onMcpToolsListed(tools: Map<String, String>) { chat.addMcpTools(tools) }

    // Exec streaming console bubble
    override fun onExecCommandBegin(callId: String, commandDisplay: String) {
        chat.beginExecConsole(callId, commandDisplay)
    }

    override fun onExecCommandOutputDelta(callId: String, isStdErr: Boolean, bytes: ByteArray) {
        chat.appendExecConsoleDelta(callId, isStdErr, bytes)
    }

    override fun onExecCommandEnd(
        callId: String,
        exitCode: Int,
        durationSecs: Long,
        durationNanos: Int,
        stdout: String,
        stderr: String,
    ) {
        chat.endExecConsole(callId, exitCode, durationSecs, durationNanos)
    }

    // Status bar updater
    private fun updateTokenStatus() {
        statusLabel.text = tokenAgg.formatted()
    }

    override fun dispose() {
        try { session.removeListener(this) } catch (_: Throwable) {}
    }

    // Task lifecycle for working indicator
    override fun onTaskStarted() {
        if (!workingIndicator.isActive()) workingIndicator.start()
    }

    override fun onTaskComplete(lastAgentMessage: String?) {
        workingIndicator.stop()
    }
}

// Aggregates token usage across turns; robust to streaming updates by computing deltas.
private class TokenAggregator {
    private var totalInput = 0
    private var totalCached = 0
    private var totalOutput = 0
    private var totalReasoning = 0

    private var lastInput = 0
    private var lastCached = 0
    private var lastOutput = 0
    private var lastReasoning = 0

    fun reset() {
        totalInput = 0; totalCached = 0; totalOutput = 0; totalReasoning = 0
        lastInput = 0; lastCached = 0; lastOutput = 0; lastReasoning = 0
    }

    fun ingestSummary(summary: String) {
        val input = Regex("input=\\s*(\\d+)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val cached = Regex("\\(\\+\\s*(\\d+)\\s*cached\\)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val output = Regex("output=\\s*(\\d+)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val reasoning = Regex("reasoning\\s*(\\d+)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val reset = input < lastInput || cached < lastCached || output < lastOutput || reasoning < lastReasoning
        if (reset) { lastInput = 0; lastCached = 0; lastOutput = 0; lastReasoning = 0 }

        val dInput = (input - lastInput).coerceAtLeast(0)
        val dCached = (cached - lastCached).coerceAtLeast(0)
        val dOutput = (output - lastOutput).coerceAtLeast(0)
        val dReasoning = (reasoning - lastReasoning).coerceAtLeast(0)

        totalInput += dInput
        totalCached += dCached
        totalOutput += dOutput
        totalReasoning += dReasoning

        lastInput = input
        lastCached = cached
        lastOutput = output
        lastReasoning = reasoning
    }

    fun formatted(): String {
        val total = totalInput + totalCached + totalOutput
        return "Tokens: total=$total input=$totalInput (+ $totalCached cached) output=$totalOutput (reasoning $totalReasoning)"
    }
}
