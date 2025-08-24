package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JComponent
import javax.swing.Timer

/**
 * A compact, animated status line shown above the chat while Codex is working.
 * It mimics the TUI status indicator: a cyan left bar, an animated “Working”
 * header, elapsed seconds, an “Esc to interrupt” hint, and a typewriter-style
 * reveal of the latest status text streamed from the agent.
 */
class WorkingIndicator(private val onInterrupt: () -> Unit) : JComponent() {
    // Animation timing
    private val tickMs = 100
    private val timer = Timer(tickMs) { repaintTarget?.repaint() ?: repaint() }
    private var startTimeMs: Long = 0

    // Typewriter animation state (mirrors codex-rs TUI logic)
    private var lastTargetLen = 0
    private var baseFrame = 0L
    private var revealLenAtBase = 0
    private var latestText: String = "waiting for model"

    // Whether the indicator is active/visible
    private var active = false
    // External surface to repaint since this component isn't added to the hierarchy
    private var repaintTarget: JComponent? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8, 6, 8)

        // Map Escape key to interrupt when focused
        val im = getInputMap(WHEN_FOCUSED)
        val am = actionMap
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "codex-interrupt")
        am.put("codex-interrupt", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { onInterrupt() }
        })
    }

    fun start() {
        if (active) return
        active = true
        isVisible = true
        startTimeMs = System.currentTimeMillis()
        // Reset typewriter baseline to begin at 0 shown
        val stripped = stripAnsiAndSingleLine(latestText)
        lastTargetLen = stripped.codePointCount(0, stripped.length)
        baseFrame = currentFrame()
        revealLenAtBase = 0
        if (!timer.isRunning) timer.start()
        repaintTarget?.repaint() ?: repaint()
    }

    fun stop() {
        if (!active) return
        active = false
        isVisible = false
        timer.stop()
        repaintTarget?.repaint() ?: repaint()
    }

    fun isActive(): Boolean = active

    /**
     * Update the latest status text. Resets the typewriter baseline but keeps
     * already-revealed characters when the target changes mid-animation.
     */
    fun updateText(text: String) {
        val sanitized = stripAnsiAndSingleLine(text)
        if (sanitized == latestText) return

        val newLen = sanitized.codePointCount(0, sanitized.length)
        val shownNow = currentShownLen(currentFrame())
        latestText = sanitized
        lastTargetLen = newLen
        baseFrame = currentFrame()
        revealLenAtBase = shownNow.coerceAtMost(newLen)
        repaintTarget?.repaint() ?: repaint()
    }

    fun setRepaintTarget(target: JComponent?) {
        repaintTarget = target
    }

    override fun getPreferredSize(): Dimension {
        // One line tall plus vertical insets
        val f = font ?: javax.swing.UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)
        val fm = getFontMetrics(f)
        val ins = border?.getBorderInsets(this) ?: Insets(0, 0, 0, 0)
        val h = ins.top + fm.height + ins.bottom
        // Width is controlled by the overlay; return minimal here.
        return Dimension(JBUI.scale(100), h)
    }

    override fun paintComponent(g: Graphics) {
        if (!active) return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val bounds = Rectangle(0, 0, width, height)
        // Subtle background to separate from toolbar/chat
        val bg = JBColor(0xF5F5F5, 0x2B2B2B)
        g2.color = bg
        g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)

        // Bottom border hairline
        g2.color = JBColor.border()
        g2.drawLine(bounds.x, bounds.height - 1, bounds.width, bounds.height - 1)

        val insets = border?.getBorderInsets(this) ?: Insets(0, 0, 0, 0)
        val x0 = insets.left
        val y0 = insets.top
        val innerW = width - insets.left - insets.right
        val baseFont = font ?: javax.swing.UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g2.font = baseFont
        val fm = g2.fontMetrics
        val baseline = y0 + fm.ascent

        // Left cyan bar
        val barW = JBUI.scale(6)
        val barH = fm.height
        g2.color = JBColor(0x00BCD4, 0x00BCD4)
        g2.fillRect(x0, baseline - fm.ascent, barW, barH)

        var x = x0 + barW + JBUI.scale(8)

        // Animated "Working"
        val working = "Working"
        val chars = working.toCharArray()
        val sweepPos = shimmerPos(chars.size)
        for (i in chars.indices) {
            val level = shimmerLevel(i, sweepPos)
            val col = Color(level, level, level)
            g2.color = col
            val s = chars[i].toString()
            g2.font = baseFont.deriveFont(Font.BOLD)
            g2.drawString(s, x, baseline)
            x += fm.stringWidth(s)
        }

        // Space
        x += fm.stringWidth(" ")

        // Parenthetical: "(Ns • Esc to interrupt)"
        val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toString() + "s"
        val dim = JBColor(0x777777, 0xAAAAAA)
        g2.color = dim
        g2.font = baseFont
        val prefix = "($elapsed • "
        g2.drawString(prefix, x, baseline)
        x += fm.stringWidth(prefix)
        g2.font = baseFont.deriveFont(Font.BOLD)
        g2.drawString("Esc", x, baseline)
        x += fm.stringWidth("Esc")
        g2.font = baseFont
        val suffix = " to interrupt)"
        g2.drawString(suffix, x, baseline)
        x += fm.stringWidth(suffix)

        // Space then the status text (typewriter reveal), dimmed
        val shownLen = currentShownLen(currentFrame())
        val statusPrefix = substringByCodePoints(latestText, shownLen)
        if (statusPrefix.isNotEmpty()) {
            x += fm.stringWidth(" ")
            val remainingW = innerW - (x - insets.left)
            if (remainingW > 10) {
                val clipped = clipToWidth(statusPrefix, fm, remainingW)
                g2.color = dim
                g2.drawString(clipped, x, baseline)
            }
        }
    }

    private fun currentFrame(): Long {
        return (System.currentTimeMillis() - startTimeMs) / tickMs
    }

    private fun currentShownLen(currentFrame: Long): Int {
        val typingCharsPerFrame = 7
        val frames = (currentFrame - baseFrame).coerceAtLeast(0)
        val advanced = revealLenAtBase + (frames.toInt() * typingCharsPerFrame)
        return advanced.coerceAtMost(lastTargetLen)
    }

    private fun shimmerPos(len: Int): Int {
        val padding = 10
        val period = len + padding * 2
        val sweepSeconds = 2.5f
        val elapsed = (System.currentTimeMillis() - startTimeMs).toFloat() / 1000f
        val posF = (elapsed % sweepSeconds) / sweepSeconds * period
        return posF.toInt()
    }

    private fun shimmerLevel(i: Int, pos: Int): Int {
        val padding = 10
        val bandHalfWidth = 3.0
        val iPos = i + padding
        val dist = kotlin.math.abs(iPos - pos).toDouble()
        val t = if (dist <= bandHalfWidth) {
            val x = Math.PI * (dist / bandHalfWidth)
            0.5 * (1.0 + kotlin.math.cos(x))
        } else 0.0
        val brightness = 0.4 + 0.6 * t
        return (brightness * 255.0).coerceIn(0.0, 255.0).toInt()
    }

    private fun stripAnsiAndSingleLine(text: String): String {
        // Very small, permissive ANSI stripper and single-line sanitizer.
        val noAnsi = text.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
        val oneLine = noAnsi.lines().lastOrNull { it.isNotBlank() } ?: ""
        return oneLine.trim()
    }

    private fun substringByCodePoints(s: String, count: Int): String {
        if (count <= 0) return ""
        var i = 0
        var idx = 0
        val n = s.length
        while (idx < n && i < count) {
            val ch = s[idx]
            idx += if (Character.isHighSurrogate(ch) && idx + 1 < n && Character.isLowSurrogate(s[idx + 1])) 2 else 1
            i++
        }
        return s.substring(0, idx)
    }

    private fun clipToWidth(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        var low = 0
        var high = text.length
        var best = ""
        while (low <= high) {
            val mid = (low + high) / 2
            val sub = text.substring(0, mid) + ellipsis
            val w = fm.stringWidth(sub)
            if (w <= maxWidth) {
                best = sub
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}
