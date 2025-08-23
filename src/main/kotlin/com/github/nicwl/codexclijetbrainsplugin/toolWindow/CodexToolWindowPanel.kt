package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexEventListener
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSessionService
import com.github.nicwl.codexclijetbrainsplugin.settings.CodexSettingsConfigurable
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
// Console imports removed in favor of ChatView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBUI
import com.github.nicwl.codexclijetbrainsplugin.shared.CodexKeys
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.EditorModificationUtil
// keep single ApplicationManager import above
import com.intellij.openapi.application.ModalityState
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToolBar
import javax.swing.JLabel
import javax.swing.BoxLayout
import java.awt.event.KeyAdapter
import javax.swing.KeyStroke
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import javax.swing.text.BadLocationException
import javax.swing.SwingUtilities

class CodexToolWindowPanel(private val project: Project) : CodexEventListener {
    private val log = Logger.getInstance(CodexToolWindowPanel::class.java)
    private val session = project.getService(CodexSessionService::class.java)
    private val chat = ChatView(project)
    private val input = EditorTextField("", project, CodexChatFileType)
    private val statusLabel = JLabel("Tokens: total=0 input=0 (+ 0 cached) output=0 (reasoning 0)")
    private val tokenAgg = TokenAggregator()
    private var assistantStreaming = false
    private var lastAssistantFinal: String? = null
    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
    }
    // no manual lookup state; rely on IDE completion

    private data class SlashCommand(val id: String, val title: String, val description: String, val needsArg: Boolean = false)
    private var slashLookupRequested = false
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
        rootPanel.add(chat, BorderLayout.CENTER)

        // Configure chat input (EditorTextField) with soft wraps and keybindings
        input.addSettingsProvider { ed ->
            (ed as? EditorEx)?.settings?.isUseSoftWraps = true
            ed.putUserData(CodexKeys.CHAT_INPUT, true)
        }
        installSlashCompletion()
        val inputPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.emptyTop(6) }
        val sendButton = JButton("Send").apply {
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

        // Key bindings: Enter to send, Shift+Enter to insert newline
        // Add Enter/Shift+Enter bindings to the editor component once it's created
        SwingUtilities.invokeLater {
            input.editor?.let { installEditorKeyBindings(it) }
        }

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

    private fun createToolbar(): JToolBar {
        val toolbar = JToolBar().apply {
            isFloatable = false
        }

        fun addAction(text: String, icon: javax.swing.Icon?, action: () -> Unit) {
            val btn = JButton(text, icon)
            btn.addActionListener { action() }
            toolbar.add(btn)
        }

        addAction("Settings", AllIcons.General.Gear) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexSettingsConfigurable::class.java)
        }
        return toolbar
    }

    fun ensureStarted() {
        session.startIfNeeded()
    }

    private fun installSlashCompletion() {
        // Show a Lookup with our slash commands when text is a single token starting with '/'
        input.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val ed = input.editor ?: return
                val txt = ed.document.text
                val singleTokenSlash = txt.startsWith('/') && txt.indexOfFirst { it.isWhitespace() } < 0
                log.warn("[slash] docChanged text='${txt.replace("\n", "\\n")}' singleToken=$singleTokenSlash")
                val active = LookupManager.getActiveLookup(ed)
                if (singleTokenSlash) {
                    val prefix = txt.drop(1).lowercase()
                    val items = if (prefix.isEmpty()) slashCommands else slashCommands.filter { it.id.startsWith(prefix) }
                    if (items.isEmpty()) {
                        if (active != null) LookupManager.getInstance(project).hideActiveLookup()
                        slashLookupRequested = false
                        return
                    }
                    if (!slashLookupRequested) {
                        slashLookupRequested = true
                        log.warn("[slash] scheduling lookup open after write; prefix='$prefix' items=${items.size}")
                        ApplicationManager.getApplication().invokeLater({
                            try {
                                val stillEd = input.editor ?: return@invokeLater
                                val current = stillEd.document.text
                                val stillSingle = current.startsWith('/') && current.indexOfFirst { it.isWhitespace() } < 0
                                val pfx = current.drop(1).lowercase()
                                val list = if (pfx.isEmpty()) slashCommands else slashCommands.filter { it.id.startsWith(pfx) }
                                val hasActive = LookupManager.getActiveLookup(stillEd) != null
                                log.warn("[slash] invokeLater: stillSingle=$stillSingle hasActive=$hasActive filtered=${list.size}")
                                if (stillSingle && list.isNotEmpty()) {
                                    // Close any stale lookup then open a filtered one
                                    if (hasActive) LookupManager.getInstance(project).hideActiveLookup()
                                    showSlashLookup(stillEd, list)
                                }
                            } finally {
                                slashLookupRequested = false
                            }
                        }, ModalityState.current())
                    }
                } else {
                    if (active != null) {
                        log.warn("[slash] hiding active lookup (not single token)")
                        LookupManager.getInstance(project).hideActiveLookup()
                    }
                    slashLookupRequested = false
                }
            }
        })
    }

    private fun showSlashLookup(editor: Editor, items: List<SlashCommand>) {
        val elements = items.map { sc ->
            val insert = "/${sc.id}" + if (sc.needsArg) " " else ""
            // Use id as lookup string so typing filters by id; keep '/' only in presentation
            LookupElementBuilder
                .create(sc.id)
                .withPresentableText("/${sc.id}")
                .withTailText("  —  ${sc.title}", true)
                .withTypeText(sc.description, true)
                .withInsertHandler { ctx, _ ->
                    // Default insertion replaces the prefix (e.g., 'sta' -> 'status'); prepend '/' if missing and add space for args
                    val ed = ctx.editor
                    val doc = ctx.document
                    val caret = ed.caretModel.offset
                    val text = doc.text
                    val hasSlash = text.startsWith('/')
                    // Ensure a leading slash exists
                    if (!hasSlash) {
                        doc.insertString(0, "/")
                        ed.caretModel.moveToOffset(caret + 1)
                    }
                    // Add trailing space if needed
                    if (sc.needsArg) {
                        val pos = ed.caretModel.offset
                        val needsSpace = pos == doc.textLength || doc.charsSequence.getOrNull(pos) != ' '
                        if (needsSpace) {
                            doc.insertString(pos, " ")
                            ed.caretModel.moveToOffset(pos + 1)
                        }
                    }
                }
        }.toTypedArray()
        LookupManager.getInstance(project).showLookup(editor, *elements)
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
        session.sendUserText(text)
        input.text = ""
    }

    private fun installEditorKeyBindings(editor: Editor) {
        val comp = editor.contentComponent
        // Enter: send message
        comp.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "codex-send")
        comp.actionMap.put("codex-send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (LookupManager.getActiveLookup(editor) != null) return
                sendCurrentInput()
            }
        })
        // Shift+Enter: insert newline
        comp.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "codex-insert-break")
        comp.actionMap.put("codex-insert-break", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { EditorModificationUtil.insertStringAtCaret(editor, "\n") }
        })
    }

    private fun handleSlashCommand(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"))
        val cmd = parts.firstOrNull()?.removePrefix("/")?.lowercase() ?: return false
        val args = parts.drop(1)
        when (cmd) {
            "compact" -> {
                chat.addNote("Compacting context…")
                session.sendCompact()
                return true
            }

            "status" -> {
                chat.addStatusBlock(session.getStatusRich(project.basePath))
                return true
            }

            "diff" -> {
                chat.addNote("Computing git diff…")
                computeGitDiff()
                return true
            }

            "mcp" -> {
                chat.addNote("Listing MCP tools…")
                session.sendListMcpTools()
                return true
            }

            "new" -> {
                chat.addNote("Starting new Codex session…")
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
                    chat.addNote("Usage: /model <slug>")
                } else {
                    chat.addNote("Switching model to ‘$slug’…")
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
                if (mapped == null) chat.addNote("Usage: /approvals <untrusted|on_request|on_failure|never>")
                else { chat.addNote("Setting approval policy to ‘$mapped’…"); session.sendOverrideApproval(mapped) }
                return true
            }

            "quit" -> {
                chat.addNote("Close the Codex tool window to quit.")
                return true
            }

            else -> return false
        }
    }

    private fun computeGitDiff() {
        val baseDir = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val insideRepo = runCmd(listOf("git", "rev-parse", "--is-inside-work-tree"), baseDir).first
                if (!insideRepo) {
                    chat.addNote("`/diff` — not inside a git repository")
                    return@executeOnPooledThread
                }
                val tracked = runCmdCapture(listOf("git", "diff", "--color"), baseDir)
                val untrackedList = runCmdCapture(listOf("git", "ls-files", "--others", "--exclude-standard"), baseDir)
                val nullPath = if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null"
                val untrackedDiff = StringBuilder()
                untrackedList.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { file ->
                    val d = runCmdCapture(listOf("git", "diff", "--color", "--no-index", "--", nullPath, file), baseDir)
                    untrackedDiff.append(d)
                }
                val out = tracked + untrackedDiff.toString()
                if (out.isBlank()) chat.addNote("[diff] (no changes)") else chat.addDiff(out)
            } catch (t: Throwable) {
                chat.addError("Failed to compute diff: ${t.message}")
            }
        }
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
        chat.addNote("Connected to model: $model")
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

    override fun onBackground(message: String) { chat.addNote(message) }

    override fun onTokenUsage(summary: String) {
        tokenAgg.ingestSummary(summary)
        updateTokenStatus()
    }

    override fun onExecApprovalRequest(submissionId: String, command: List<String>, cwd: String, reason: String?) {
        val options = arrayOf("Approve", "Approve for Session", "Deny", "Abort")
        val choice = com.intellij.openapi.ui.Messages.showIdeaMessageDialog(
            project,
            "Command:\n${command.joinToString(" ")}\nCWD: $cwd${reason?.let { "\nReason: $it" } ?: ""}",
            "Codex Command Approval",
            options,
            0,
            null,
            null)
        when (choice) {
            0 -> session.sendExecApproval("approved", submissionId)
            1 -> session.sendExecApproval("approved_for_session", submissionId)
            2 -> session.sendExecApproval("denied", submissionId)
            3 -> session.sendExecApproval("abort", submissionId)
        }
    }

    override fun onPatchApprovalRequest(submissionId: String, summary: String) {
        val options = arrayOf("Approve", "Approve for Session", "Deny", "Abort")
        val choice = com.intellij.openapi.ui.Messages.showIdeaMessageDialog(
            project, "Proposed changes:\n$summary", "Codex Patch Approval", options, 0, null, null
        )
        when (choice) {
            0 -> session.sendPatchApproval("approved", submissionId)
            1 -> session.sendPatchApproval("approved_for_session", submissionId)
            2 -> session.sendPatchApproval("denied", submissionId)
            3 -> session.sendPatchApproval("abort", submissionId)
        }
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
