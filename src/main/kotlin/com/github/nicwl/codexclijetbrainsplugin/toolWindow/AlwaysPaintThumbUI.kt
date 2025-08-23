package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.util.ui.JBUI
import java.awt.Adjustable
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicScrollBarUI

class AlwaysPaintThumbUI : BasicScrollBarUI() {
    private val width = JBUI.scale(12)

    override fun getPreferredSize(c: JComponent): Dimension {
        val base = super.getPreferredSize(c)
        return if (scrollbar.orientation == Adjustable.VERTICAL) Dimension(width, base.height) else Dimension(base.width, width)
    }

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: java.awt.Rectangle) {
        // Transparent track for a JetBrains-like look
    }

    override fun paintThumb(g: Graphics, c: JComponent, r: java.awt.Rectangle) {
        if (!scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val active = isThumbRollover || isDragging
            val base = if (active) Color(130, 130, 130, 220) else Color(120, 120, 120, 170)

            val inset = JBUI.scale(2)
            val arc = JBUI.scale(8)
            val x = r.x + if (scrollbar.orientation == Adjustable.VERTICAL) inset else 0
            val y = r.y + if (scrollbar.orientation == Adjustable.VERTICAL) 0 else inset
            val w = r.width - if (scrollbar.orientation == Adjustable.VERTICAL) inset * 2 else 0
            val h = r.height - if (scrollbar.orientation == Adjustable.VERTICAL) 0 else inset * 2

            g2.color = base
            g2.fillRoundRect(x, y, w.coerceAtLeast(2), h.coerceAtLeast(2), arc, arc)
        } finally {
            g2.dispose()
        }
    }

    override fun createDecreaseButton(orientation: Int) = zeroButton()
    override fun createIncreaseButton(orientation: Int) = zeroButton()

    private fun zeroButton() = JButton().apply {
        val d = Dimension(0, 0)
        preferredSize = d
        minimumSize = d
        maximumSize = d
        isOpaque = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
    }
}

