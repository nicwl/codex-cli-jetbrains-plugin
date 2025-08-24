package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.project.Project
import com.github.nicwl.codexclijetbrainsplugin.MyBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBScrollBar
import com.intellij.util.ui.JBUI
import javax.swing.plaf.LayerUI
import javax.swing.JLayer
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*

class ChatView(private val project: Project) : JPanel(BorderLayout()) {
    private val content = JPanel()
    private val scroll = JBScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    private val overlayUi = object : LayerUI<JComponent>() {
        override fun paint(g: Graphics, c: JComponent) {
            super.paint(g, c)
            val indicator = overlayIndicator ?: return
            if (!indicator.isActive()) return

            val g2 = g.create() as Graphics2D
            try {
            val margin = JBUI.scale(8)
            val vsb = scroll.verticalScrollBar
            val sbw = if (vsb != null && vsb.isVisible) vsb.width else 0
            val maxWidth = (c.width - margin * 2 - sbw).coerceAtLeast(100)
            val pref = indicator.preferredSize
            // Use full available width within margins so inner padding/insets are respected
            val w = maxWidth
            // Height should include indicator padding/insets
            val h = pref.height
            val x = margin
            val y = c.height - margin - h
            val clip = g2.create(x, y, w, h)
            indicator.setSize(w, h)
            indicator.paint(clip)
                clip.dispose()
            } finally {
                g2.dispose()
            }
        }
    }
    private val layered = JLayer<JComponent>(scroll, overlayUi)
    private var overlayIndicator: WorkingIndicator? = null

    private var currentAssistant: MessageBubble? = null
    private var currentReasoning: MessageBubble? = null
    private val execConsoles = mutableMapOf<String, ConsoleBubble>()
    private val approvals = mutableListOf<ApprovalBubble>()
    private val bubbles = mutableListOf<MessageBubble>()

    init {
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(8)
        add(layered, BorderLayout.CENTER)

        // Use custom ScrollBar UI to keep thumbs always visible (avoid macOS overlay auto-hide)
        scroll.verticalScrollBar = JBScrollBar(Adjustable.VERTICAL).apply {
            isOpaque = false
            unitIncrement = JBUI.scale(16)
            ui = AlwaysPaintThumbUI()
        }
        scroll.horizontalScrollBar = JBScrollBar(Adjustable.HORIZONTAL).apply {
            isOpaque = false
            unitIncrement = JBUI.scale(16)
            ui = AlwaysPaintThumbUI()
        }

        // Recompute bubble widths on resize
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateBubbleWidths()
            }
        })
    }

    fun setWorkingIndicator(indicator: WorkingIndicator) {
        overlayIndicator = indicator
        indicator.setRepaintTarget(layered)
        layered.repaint()
    }

    fun addUserMessage(text: String) {
        currentAssistant = null
        val bubble = MessageBubble(text, role = Role.User)
        content.add(bubble.row)
        bubbles.add(bubble)
        revalidateAndScroll()
    }

    fun beginAssistantMessage() {
        if (currentAssistant == null) {
            currentAssistant = MessageBubble("", role = Role.Assistant)
            content.add(currentAssistant!!.row)
            bubbles.add(currentAssistant!!)
            revalidateAndScroll()
        }
    }

    fun appendAssistantDelta(delta: String) {
        beginAssistantMessage()
        currentAssistant?.append(delta)
        // Immediately recompute this bubble's size to reflect new lines
        currentAssistant?.let { refreshBubble(it) }
        revalidateAndScroll()
    }

    fun finalizeAssistantMessage(fullText: String?) {
        if (fullText != null) {
            if (currentAssistant == null) {
                // No streaming occurred; avoid duplicating an identical previous assistant bubble
                val last = bubbles.lastOrNull()
                if (last != null && last.getRole() == Role.Assistant && last.getText() == fullText) {
                    // Do nothing, it's already rendered
                } else {
                    val bubble = MessageBubble(fullText, role = Role.Assistant)
                    content.add(bubble.row)
                    bubbles.add(bubble)
                }
            } else {
                // Streaming occurred; service may send empty string to signal finalize-only
                if (fullText.isEmpty()) {
                    currentAssistant?.append("\n")
                } else {
                    // Replace streamed content with authoritative final text
                    currentAssistant?.setText(fullText)
                }
            }
        } else {
            // Just finalize formatting of the streamed bubble
            currentAssistant?.append("\n")
        }
        currentAssistant = null
        revalidateAndScroll()
    }

    fun addNote(text: String) {
        val bubble = MessageBubble(text, role = Role.Note)
        content.add(bubble.row)
        bubbles.add(bubble)
        revalidateAndScroll()
    }

    fun addError(text: String) {
        val bubble = MessageBubble(text, role = Role.Error)
        content.add(bubble.row)
        bubbles.add(bubble)
        revalidateAndScroll()
    }

    fun addDiff(diffText: String) {
        val bubble = MessageBubble(diffText, role = Role.Diff, mono = true)
        content.add(bubble.row)
        bubbles.add(bubble)
        revalidateAndScroll()
    }

    fun addStatusBlock(text: String) {
        val bubble = MessageBubble(text.trimEnd(), role = Role.Status, mono = false)
        content.add(bubble.row)
        bubbles.add(bubble)
        revalidateAndScroll()
    }

    fun beginReasoning() {
        if (currentReasoning == null) {
            val b = MessageBubble("", role = Role.Reasoning)
            content.add(b.row)
            bubbles.add(b)
            currentReasoning = b
            revalidateAndScroll()
        }
    }

    fun appendReasoning(delta: String) {
        beginReasoning()
        currentReasoning?.append(delta)
        // Immediately recompute this bubble's size to reflect new lines
        currentReasoning?.let { refreshBubble(it) }
        revalidateAndScroll()
    }

    fun reasoningSectionBreak() {
        // Close the current reasoning bubble so the next delta starts a new bubble.
        if (currentReasoning != null) {
            currentReasoning?.append("\n")
            currentReasoning = null
            revalidateAndScroll()
        }
    }

    fun endReasoning() {
        currentReasoning?.append("\n")
        currentReasoning = null
        revalidateAndScroll()
    }

    fun addMcpTools(tools: Map<String, String>) {
        if (tools.isEmpty()) {
            addNote(MyBundle.message("mcp.tools.none"))
            return
        }
        val sb = StringBuilder()
        sb.append(MyBundle.message("mcp.tools.header")).append('\n')
        tools.forEach { (name, desc) ->
            if (desc.isNotBlank()) sb.append(" - ").append(name).append(": ").append(desc).append('\n')
            else sb.append(" - ").append(name).append('\n')
        }
        addNote(sb.toString())
    }

    // ───────── Exec command console bubble (ANSI-aware) ─────────
    fun beginExecConsole(callId: String, commandDisplay: String) {
        if (execConsoles.containsKey(callId)) return
        val console = ConsoleBubble(commandDisplay, project)
        execConsoles[callId] = console
        content.add(console.row)
        refreshConsole(console)
        revalidateAndScroll()
    }

    fun appendExecConsoleDelta(callId: String, isStdErr: Boolean, bytes: ByteArray) {
        val c = execConsoles[callId] ?: run {
            // If we somehow get output before begin, create a generic console
            val fallback = ConsoleBubble(MyBundle.message("exec.fallback.title"), project)
            execConsoles[callId] = fallback
            content.add(fallback.row)
            fallback
        }
        c.appendBytes(bytes, isStdErr)
        refreshConsole(c)
        revalidateAndScroll()
    }

    fun endExecConsole(callId: String, exitCode: Int, durationSecs: Long, durationNanos: Int) {
        val c = execConsoles.remove(callId) ?: return
        c.appendFooter(MyBundle.message("exec.footer", exitCode, durationSecs, durationNanos))
        refreshConsole(c)
        revalidateAndScroll()
    }

    private fun revalidateAndScroll() {
        // Compute bubble sizes immediately to avoid initial oversized height
        updateBubbleWidths()
        revalidate()
        repaint()
        SwingUtilities.invokeLater {
            val max = scroll.verticalScrollBar.maximum
            scroll.verticalScrollBar.value = max
        }
    }

    private fun updateBubbleWidths() {
        val containerW = scroll.viewport.extentSize.width.takeIf { it > 0 } ?: content.width
        val available = (containerW * 0.9).toInt().coerceAtLeast(200)
        bubbles.forEach { it.setMaxWidth(available) }
        execConsoles.values.forEach { it.setMaxWidth(available) }
        approvals.forEach { it.setMaxWidth(available) }
        content.revalidate()
        content.repaint()
    }

    private fun refreshBubble(b: MessageBubble) {
        val containerW = scroll.viewport.extentSize.width.takeIf { it > 0 } ?: content.width
        val available = (containerW * 0.9).toInt().coerceAtLeast(200)
        b.setMaxWidth(available)
    }

    // ───────── Approval prompt bubble ─────────
    fun addExecApprovalPrompt(
        command: List<String>,
        cwd: String,
        reason: String?,
        onApprove: () -> Unit,
        onApproveSession: () -> Unit,
        onDeny: () -> Unit,
        onAbort: () -> Unit,
    ) {
        val message = buildString {
            append("Command: ")
            append(command.joinToString(" "))
            append('\n')
            append("CWD: ")
            append(cwd)
            if (!reason.isNullOrBlank()) {
                append('\n')
                append("Reason: ")
                append(reason)
            }
        }
        val bubble = ApprovalBubble("Codex Command Approval", message, onApprove, onApproveSession, onDeny, onAbort)
        approvals.add(bubble)
        content.add(bubble.row)
        revalidateAndScroll()
    }

    fun addPatchApprovalPrompt(
        summary: String,
        onApprove: () -> Unit,
        onApproveSession: () -> Unit,
        onDeny: () -> Unit,
        onAbort: () -> Unit,
    ) {
        val bubble = ApprovalBubble("Codex Patch Approval", "Proposed changes:\n$summary", onApprove, onApproveSession, onDeny, onAbort)
        approvals.add(bubble)
        content.add(bubble.row)
        revalidateAndScroll()
    }

    inner class ApprovalBubble(
        title: String,
        message: String,
        onApprove: () -> Unit,
        onApproveSession: () -> Unit,
        onDeny: () -> Unit,
        onAbort: () -> Unit,
    ) {
        val row = JPanel()
        private val panel = JPanel(BorderLayout())
        private val inner = JPanel(BorderLayout())
        private val header = JLabel(title)
        private val text = JTextArea(message)
        private val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        private val approveBtn = JButton("Approve")
        private val approveSessBtn = JButton("Approve for Session")
        private val denyBtn = JButton("Deny")
        private val abortBtn = JButton("Abort")

        init {
            row.isOpaque = false
            row.layout = BoxLayout(row, BoxLayout.X_AXIS)

            val bg = JBColor(0xFFFBE6, 0x3A3F44)
            inner.background = bg
            inner.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
            )

            header.foreground = UIManager.getColor("Label.foreground")
            header.font = Font(Font.SANS_SERIF, Font.BOLD, UIManager.getFont("Label.font").size)
            header.border = JBUI.Borders.emptyBottom(6)

            text.isEditable = false
            text.lineWrap = true
            text.wrapStyleWord = true
            text.background = bg
            text.foreground = UIManager.getColor("Label.foreground")

            buttons.isOpaque = false
            buttons.border = JBUI.Borders.emptyTop(8)

            val onDecision: (String, () -> Unit) -> Unit = { label, action ->
                approveBtn.isEnabled = false
                approveSessBtn.isEnabled = false
                denyBtn.isEnabled = false
                abortBtn.isEnabled = false
                // Replace buttons with decision label
                buttons.removeAll()
                buttons.add(JLabel("Decision: $label"))
                buttons.revalidate()
                buttons.repaint()
                action.invoke()
            }

            approveBtn.addActionListener { onDecision("Approved", onApprove) }
            approveSessBtn.addActionListener { onDecision("Approved for Session", onApproveSession) }
            denyBtn.addActionListener { onDecision("Denied", onDeny) }
            abortBtn.addActionListener { onDecision("Aborted", onAbort) }

            buttons.add(approveBtn)
            buttons.add(approveSessBtn)
            buttons.add(denyBtn)
            buttons.add(abortBtn)

            val center = JPanel(BorderLayout())
            center.isOpaque = false
            center.add(header, BorderLayout.NORTH)
            center.add(text, BorderLayout.CENTER)
            center.add(buttons, BorderLayout.SOUTH)

            panel.isOpaque = false
            panel.add(inner, BorderLayout.WEST)
            inner.add(center, BorderLayout.CENTER)
            panel.border = JBUI.Borders.empty(4, 0)
            panel.alignmentX = Component.LEFT_ALIGNMENT

            row.add(panel)
            row.add(Box.createHorizontalGlue())
        }

        fun setMaxWidth(maxWidth: Int) {
            val innerInsets = inner.border?.getBorderInsets(inner) ?: Insets(0, 0, 0, 0)
            val panelInsets = panel.border?.getBorderInsets(panel) ?: Insets(0, 0, 0, 0)

            val bubbleWidth = maxWidth
            val wrapWidth = (bubbleWidth - innerInsets.left - innerInsets.right).coerceAtLeast(60)
            text.size = Dimension(wrapWidth, 100_000)
            val textH = text.preferredSize.height

            val buttonsH = buttons.preferredSize.height
            val headerH = header.preferredSize.height
            val totalH = headerH + textH + buttonsH + innerInsets.top + innerInsets.bottom + panelInsets.top + panelInsets.bottom + JBUI.scale(1)

            panel.preferredSize = Dimension(bubbleWidth, totalH)
            panel.maximumSize = Dimension(bubbleWidth, totalH)
            row.maximumSize = Dimension(Int.MAX_VALUE, totalH)
            panel.revalidate()
            panel.repaint()
        }
    }

    private fun refreshConsole(c: ConsoleBubble) {
        val containerW = scroll.viewport.extentSize.width.takeIf { it > 0 } ?: content.width
        val available = (containerW * 0.9).toInt().coerceAtLeast(200)
        c.setMaxWidth(available)
    }

    enum class Role { User, Assistant, Note, Error, Diff, Status, Reasoning }

    class MessageBubble(initialText: String, role: Role, mono: Boolean = false) {
        val row = JPanel()
        val panel = JPanel(BorderLayout())
        private val area = JTextArea()
        private val inner = JPanel(BorderLayout())
        private val roleType = role

        init {
            val bg = when (role) {
                Role.User -> JBColor(0xE7F3FF, 0x2B3A48)
                Role.Assistant -> JBColor(0xF5F5F5, 0x3A3F44)
                Role.Note -> JBColor(0xFFFBE6, 0x3A3F44)
                Role.Error -> JBColor(0xFFECEC, 0x4A2B2B)
                Role.Diff -> JBColor(0xF8F8F8, 0x2F2F2F)
                Role.Status -> JBColor(0xF5F5F5, 0x3A3F44)
                Role.Reasoning -> JBColor(0xF0F0FF, 0x32324A)
            }
            val fg = when (role) {
                Role.Error -> JBColor(0xA80000, 0xFFB3B3)
                else -> UIManager.getColor("Label.foreground")
            }
            // Row with horizontal alignment (left for assistant, right for user)
            row.isOpaque = false
            row.layout = BoxLayout(row, BoxLayout.X_AXIS)
            val bubbleFirst = role == Role.User // user on right => glue then bubble
            if (!bubbleFirst) {
                // assistant: bubble then glue
                // we'll add after bubble setup
            }
            panel.isOpaque = false
            inner.background = bg
            inner.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
            )
            area.isEditable = false
            area.lineWrap = true
            area.wrapStyleWord = true
            area.background = bg
            area.foreground = fg
            if (mono) {
                area.font = Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").size)
            }
            area.text = initialText
            inner.add(area, BorderLayout.CENTER)
            panel.layout = BorderLayout()
            panel.add(inner, BorderLayout.WEST)
            panel.border = JBUI.Borders.empty(4, 0)
            panel.alignmentX = Component.LEFT_ALIGNMENT
            // Insert into row with alignment
            if (role == Role.User) {
                row.add(Box.createHorizontalGlue())
                row.add(panel)
            } else {
                row.add(panel)
                row.add(Box.createHorizontalGlue())
            }
        }

        fun append(delta: String) {
            area.append(delta)
        }

        fun setText(text: String) {
            area.text = text
        }

        fun getText(): String = area.text
        fun getRole(): Role = roleType

        fun setMaxWidth(maxWidth: Int) {
            // Compute desired width based on content, capped at maxWidth
            val innerInsets = inner.border?.getBorderInsets(inner) ?: Insets(0, 0, 0, 0)
            val panelInsets = panel.border?.getBorderInsets(panel) ?: Insets(0, 0, 0, 0)
            val areaInsets = area.insets ?: Insets(0, 0, 0, 0)

            // Measure the widest unwrapped line using FontMetrics
            val fm = area.getFontMetrics(area.font)
            val lines = area.text.split("\n")
            var contentPx = 0
            for (ln in lines) contentPx = maxOf(contentPx, fm.stringWidth(ln))

            val minBubble = 80
            val fudge = JBUI.scale(2)
            val desired = (contentPx + innerInsets.left + innerInsets.right + areaInsets.left + areaInsets.right + fudge)
                .coerceAtLeast(minBubble)
            val bubbleWidth = desired.coerceAtMost(maxWidth)

            // Ask JTextArea to wrap within the chosen width (<= maxWidth)
            val wrapWidth = (bubbleWidth - innerInsets.left - innerInsets.right - areaInsets.left - areaInsets.right)
                .coerceAtLeast(60)
            area.size = Dimension(wrapWidth, 100_000)
            val textPref = area.preferredSize

            val panelPrefH = textPref.height + innerInsets.top + innerInsets.bottom + panelInsets.top + panelInsets.bottom + JBUI.scale(1)

            // Respect preferred (<= 90% max). Prevent BoxLayout from stretching this bubble beyond its preferred width.
            panel.preferredSize = Dimension(bubbleWidth, panelPrefH)
            panel.maximumSize = Dimension(bubbleWidth, panelPrefH)
            row.maximumSize = Dimension(Int.MAX_VALUE, panelPrefH)
            panel.revalidate()
            panel.repaint()
        }
    }

    // Minimal ANSI-aware console bubble with max height + scroll
    inner class ConsoleBubble(commandDisplay: String, private val project: Project? = null) {
        val row = JPanel()
        private val panel = JPanel(BorderLayout())
        private val inner = JPanel(BorderLayout())
        private val header = JLabel()
        private val text = JTextPane()
        private val doc = text.styledDocument
        private val scroll = JBScrollPane(
            text,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        )
        private val footer = JLabel()
        private val ansi = AnsiEscapeDecoder()

        // Incremental UTF-8 decoding support
        private var pendingUtf8 = ByteArray(0)
        // ANSI sequence pending (if chunk ended mid-sequence)
        private var pendingAnsi: String = ""

        // Current SGR style
        // base style is handled by ConsoleView; stderr vs stdout is controlled by initial key

        // Config
        private val maxHeight = JBUI.scale(240)

        init {
            row.isOpaque = false
            row.layout = BoxLayout(row, BoxLayout.X_AXIS)

            val bgPanel = JBColor(0x111111, 0x1E1E1E)
            inner.background = bgPanel
            inner.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6, 8)
            )

            header.text = commandDisplay
            header.foreground = JBColor(0xB8B8B8, 0xA8A8A8)
            header.font = Font(Font.MONOSPACED, Font.BOLD, UIManager.getFont("Label.font").size)
            header.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(0, 0, 6, 0)
            )

            text.isEditable = false
            text.background = bgPanel
            text.foreground = JBColor(0xF0F0F0, 0xE6E6E6)
            text.font = Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").size)

            scroll.border = null
            scroll.isOpaque = false
            scroll.viewport.isOpaque = false
            // Replace scrollbars with variants that always paint a visible thumb
            scroll.verticalScrollBar = JBScrollBar(Adjustable.VERTICAL).apply {
                isOpaque = false
                unitIncrement = JBUI.scale(16)
                ui = AlwaysPaintThumbUI()
            }
            scroll.horizontalScrollBar = JBScrollBar(Adjustable.HORIZONTAL).apply {
                isOpaque = false
                unitIncrement = JBUI.scale(16)
                ui = AlwaysPaintThumbUI()
            }

            inner.add(header, BorderLayout.NORTH)
            inner.add(scroll, BorderLayout.CENTER)

            footer.foreground = JBColor(0x9AA0A6, 0x868686)
            footer.font = Font(
                Font.MONOSPACED,
                Font.PLAIN,
                (UIManager.getFont("Label.font").size - 1).coerceAtLeast(10)
            )
            footer.border = JBUI.Borders.emptyTop(6)
            inner.add(footer, BorderLayout.SOUTH)

            panel.isOpaque = false
            panel.add(inner, BorderLayout.WEST)
            panel.border = JBUI.Borders.empty(4, 0)
            panel.alignmentX = Component.LEFT_ALIGNMENT

            row.add(panel)
            row.add(Box.createHorizontalGlue())
        }

        fun setMaxWidth(maxWidth: Int) {
            // Width similar to message bubbles
            val innerInsets = inner.border?.getBorderInsets(inner) ?: Insets(0, 0, 0, 0)
            val panelInsets = panel.border?.getBorderInsets(panel) ?: Insets(0, 0, 0, 0)

            val bubbleWidth = maxWidth

            // Compute text preferred height at this width
            val wrapWidth = (bubbleWidth - innerInsets.left - innerInsets.right).coerceAtLeast(60)
            text.size = Dimension(wrapWidth, 100_000)
            val contentH = text.preferredSize.height
            val desiredViewportH = contentH.coerceAtMost(maxHeight)

            val totalH = desiredViewportH + header.preferredSize.height + footer.preferredSize.height + innerInsets.top + innerInsets.bottom + panelInsets.top + panelInsets.bottom + JBUI.scale(1)
            panel.preferredSize = Dimension(bubbleWidth, totalH)
            panel.maximumSize = Dimension(bubbleWidth, totalH)
            row.maximumSize = Dimension(Int.MAX_VALUE, totalH)
            panel.revalidate()
            panel.repaint()
        }

        fun appendBytes(bytes: ByteArray, isStdErr: Boolean) {
            val combined = if (pendingUtf8.isNotEmpty()) pendingUtf8 + bytes else bytes
            // Try decode; keep up to last 3 bytes as pending if needed
            val (decoded, remainder) = decodeUtf8WithRemainder(combined)
            pendingUtf8 = remainder
            if (decoded.isEmpty()) return
            appendAnsiText(decoded, isStdErr)
            scrollToBottom()
        }

        fun appendFooter(text: String) { footer.text = text; scrollToBottom() }

        private fun scrollToBottom() {
            SwingUtilities.invokeLater {
                val max = scroll.verticalScrollBar.maximum
                scroll.verticalScrollBar.value = max
            }
        }

        private fun decodeUtf8WithRemainder(bytes: ByteArray): Pair<String, ByteArray> {
            if (bytes.isEmpty()) return "" to ByteArray(0)
            // Fast path: try decode all
            try {
                return String(bytes, Charsets.UTF_8) to ByteArray(0)
            } catch (_: Throwable) {
                // fallthrough
            }
            // Try leaving 1..3 bytes as remainder until it decodes
            for (r in 1..3) {
                if (bytes.size <= r) break
                val head = bytes.copyOf(bytes.size - r)
                val tail = bytes.copyOfRange(bytes.size - r, bytes.size)
                try {
                    val s = String(head, Charsets.UTF_8)
                    return s to tail
                } catch (_: Throwable) {
                }
            }
            // Give up: decode with replacement
            return String(bytes, Charsets.UTF_8) to ByteArray(0)
        }

        private fun appendAnsiText(ch: String, isStdErr: Boolean) {
            var textIn = if (pendingAnsi.isNotEmpty()) {
                val s = pendingAnsi + ch
                pendingAnsi = ""
                s
            } else ch

            // If a chunk ends mid-escape, buffer it. AnsiEscapeDecoder handles complete sequences only.
            // Heuristic: if trailing ESC or ESC[ without 'm', stash it and return
            val escIdx = textIn.lastIndexOf('\u001B')
            if (escIdx >= 0 && escIdx >= textIn.length - 4) { // small trailing sequence; be conservative
                pendingAnsi = textIn.substring(escIdx)
                textIn = textIn.substring(0, escIdx)
            }

            if (textIn.isEmpty()) return
            val base = if (isStdErr) ProcessOutputType.STDERR else ProcessOutputType.STDOUT
            ansi.escapeText(textIn, base) { chunk, key ->
                val type = ConsoleViewContentType.getConsoleViewType(key)
                val attr = type.attributes
                val set = javax.swing.text.SimpleAttributeSet()
                attr?.foregroundColor?.let { javax.swing.text.StyleConstants.setForeground(set, it) }
                attr?.backgroundColor?.let { javax.swing.text.StyleConstants.setBackground(set, it) }
                val ft = attr?.fontType ?: Font.PLAIN
                javax.swing.text.StyleConstants.setBold(set, (ft and Font.BOLD) != 0)
                javax.swing.text.StyleConstants.setItalic(set, (ft and Font.ITALIC) != 0)
                doc.insertString(doc.length, chunk, set)
                text.caretPosition = doc.length
            }
        }
    }
}
