package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexEventListener
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSessionService
import com.github.nicwl.codexclijetbrainsplugin.settings.CodexSettingsConfigurable
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToolBar

class CodexToolWindowPanel(private val project: Project) : CodexEventListener {
    private val session = project.getService(CodexSessionService::class.java)
    private val console: ConsoleView = ConsoleViewImpl(project, true).also {
        com.intellij.openapi.util.Disposer.register(project, it)
    }
    private val input = JBTextArea(3, 80)
    private val rootPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
    }

    val component = rootPanel

    init {
        session.addListener(this)

        val toolbar = createToolbar()
        rootPanel.add(toolbar, BorderLayout.NORTH)
        rootPanel.add(console.component, BorderLayout.CENTER)

        // Enable soft wraps for console output to avoid horizontal scrolling
        val editor = (console as ConsoleViewImpl).editor
        editor?.settings?.isUseSoftWraps = true

        input.lineWrap = true
        input.wrapStyleWord = true
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(6)
        }
        val sendButton = JButton("Send").apply {
            addActionListener { sendCurrentInput() }
        }
        inputPanel.add(
            JBScrollPane(
                input, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            ), BorderLayout.CENTER
        )
        inputPanel.add(sendButton, BorderLayout.EAST)
        rootPanel.add(inputPanel, BorderLayout.SOUTH)

        // Key bindings: Enter to send, Shift+Enter to insert newline
        val insertBreak = input.actionMap.get("insert-break")
        input.inputMap.put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message")
        input.actionMap.put("send-message", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sendCurrentInput()
            }
        })
        input.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break"
        )
        if (insertBreak != null) {
            input.actionMap.put("insert-break", insertBreak)
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

        console.print(
            "Codex: ready. Click Start or type and Send.\n",
            com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
        )
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

    private fun sendCurrentInput() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        if (text.startsWith("/")) {
            if (handleSlashCommand(text)) {
                input.text = ""
                return
            }
        }
        console.print("You: $text\n", com.intellij.execution.ui.ConsoleViewContentType.USER_INPUT)
        session.sendUserText(text)
        input.text = ""
    }

    private fun handleSlashCommand(raw: String): Boolean {
        val parts = raw.trim().split(Regex("\\s+"))
        val cmd = parts.firstOrNull()?.removePrefix("/")?.lowercase() ?: return false
        val args = parts.drop(1)
        when (cmd) {
            "compact" -> {
                console.print(
                    "[note] compacting context...\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                session.sendCompact()
                return true
            }

            "status" -> {
                console.print(
                    session.getStatusRich(project.basePath) + "\n",
                    com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                return true
            }

            "diff" -> {
                console.print(
                    "[note] computing git diff...\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                computeGitDiff()
                return true
            }

            "mcp" -> {
                console.print(
                    "[note] listing MCP tools...\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                session.sendListMcpTools()
                return true
            }

            "new" -> {
                console.print(
                    "[note] starting new Codex session...\n",
                    com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                session.restart()
                return true
            }

            "model" -> {
                val slug = args.firstOrNull()
                if (slug.isNullOrBlank()) {
                    console.print(
                        "Usage: /model <slug>\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_WARNING_OUTPUT
                    )
                } else {
                    console.print(
                        "[note] switching model to '$slug'...\n",
                        com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                    )
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
                if (mapped == null) {
                    console.print(
                        "Usage: /approvals <untrusted|on_request|on_failure|never>\n",
                        com.intellij.execution.ui.ConsoleViewContentType.LOG_WARNING_OUTPUT
                    )
                } else {
                    console.print(
                        "[note] setting approval policy to '$mapped'...\n",
                        com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                    )
                    session.sendOverrideApproval(mapped)
                }
                return true
            }

            "logout" -> {
                console.print(
                    "[note] logging out...\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
                runExternalCommand(listOf(CodexSettingsState.getInstance().state.codexPath, "logout"))
                return true
            }

            "quit" -> {
                console.print(
                    "[note] Close the Codex tool window to quit.\n",
                    com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                )
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
                    console.print(
                        "`/diff` — not inside a git repository\n",
                        com.intellij.execution.ui.ConsoleViewContentType.LOG_WARNING_OUTPUT
                    )
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
                if (out.isBlank()) {
                    console.print(
                        "[diff] (no changes)\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                    )
                } else {
                    console.print(
                        "[diff]\n" + out + "\n", com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT
                    )
                }
            } catch (t: Throwable) {
                console.print(
                    "Failed to compute diff: ${t.message}\n",
                    com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
                )
            }
        }
    }

    private fun runExternalCommand(cmd: List<String>) {
        val baseDir = project.basePath
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pb = ProcessBuilder(cmd)
                if (baseDir != null) pb.directory(java.io.File(baseDir))
                pb.redirectErrorStream(true)
                val p = pb.start()
                val text = p.inputStream.bufferedReader().readText()
                p.waitFor()
                if (text.isNotBlank()) {
                    console.print(
                        text + (if (text.endsWith("\n")) "" else "\n"),
                        com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
                    )
                }
            } catch (t: Throwable) {
                console.print(
                    "Command failed: ${t.message}\n", com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT
                )
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
        console.print("Connected to model: $model\n", com.intellij.execution.ui.ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    override fun onAgentMessage(text: String) {
        console.print("$text\n", com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT)
    }

    override fun onAgentMessageDelta(delta: String) {
        console.print(delta, com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT)
    }

    override fun onBackground(message: String) {
        console.print("[note] $message\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_WARNING_OUTPUT)
    }

    override fun onTokenUsage(summary: String) {
        // Could add to status bar; for now, log
        console.print("$summary\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
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

    override fun onTurnDiff(unifiedDiff: String) {
        console.print("[diff]\n$unifiedDiff\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_DEBUG_OUTPUT)
    }

    override fun onError(message: String) {
        console.print("[error] $message\n", com.intellij.execution.ui.ConsoleViewContentType.ERROR_OUTPUT)
    }

    // Reasoning stream
    private var reasoningOpen = false
    override fun onReasoningStart() {
        if (!reasoningOpen) {
            console.print("\n— reasoning —\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
            reasoningOpen = true
        }
    }

    override fun onReasoningDelta(delta: String) {
        console.print(delta, com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
    }

    override fun onReasoningSectionBreak() {
        console.print("\n— reasoning section —\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
    }

    override fun onReasoningEnd() {
        if (reasoningOpen) {
            console.print("\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
            reasoningOpen = false
        }
    }

    override fun onMcpToolsListed(tools: Map<String, String>) {
        if (tools.isEmpty()) {
            console.print(
                "[mcp] No tools configured.\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT
            )
        } else {
            console.print("[mcp] Tools:\n", com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
            tools.forEach { (name, desc) ->
                val line = if (desc.isNotBlank()) " - $name: $desc\n" else " - $name\n"
                console.print(line, com.intellij.execution.ui.ConsoleViewContentType.LOG_INFO_OUTPUT)
            }
        }
    }
}
