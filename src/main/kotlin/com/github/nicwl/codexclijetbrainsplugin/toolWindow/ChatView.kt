package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.*

class ChatView : JPanel(BorderLayout()) {
    private val content = JPanel()
    private val scroll = JBScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)

    private var currentAssistant: MessageBubble? = null
    private var currentReasoning: MessageBubble? = null
    private val bubbles = mutableListOf<MessageBubble>()

    init {
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.border = JBUI.Borders.empty(8)
        add(scroll, BorderLayout.CENTER)

        // Recompute bubble widths on resize
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateBubbleWidths()
            }
        })
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
            addNote("[mcp] No tools configured.")
            return
        }
        val sb = StringBuilder()
        sb.append("[mcp] Tools:\n")
        tools.forEach { (name, desc) ->
            if (desc.isNotBlank()) sb.append(" - ").append(name).append(": ").append(desc).append('\n')
            else sb.append(" - ").append(name).append('\n')
        }
        addNote(sb.toString())
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
        content.revalidate()
        content.repaint()
    }

    private fun refreshBubble(b: MessageBubble) {
        val containerW = scroll.viewport.extentSize.width.takeIf { it > 0 } ?: content.width
        val available = (containerW * 0.9).toInt().coerceAtLeast(200)
        b.setMaxWidth(available)
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
        }
    }
}
