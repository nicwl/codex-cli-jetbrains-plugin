package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.github.nicwl.codexclijetbrainsplugin.settings.CodexSettingsConfigurable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool window container that manages multiple Codex chat tabs, each with its own
 * child Codex process and independent chat history. Actions are shown in a toolbar
 * above the tabs (separate row).
 */
class CodexToolWindowPanel(private val project: Project) : Disposable {
    private val rootPanel = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(4) }
    private val tabs = JBTabbedPane()
    private val sessions = mutableListOf<CodexSessionPanel>()

    val component = rootPanel

    init {
        val toolbar = createToolbar()
        rootPanel.add(toolbar, BorderLayout.NORTH)
        rootPanel.add(tabs, BorderLayout.CENTER)
        addNewTab()
    }

    fun ensureStarted() {
        val idx = tabs.selectedIndex
        if (idx in sessions.indices) sessions[idx].ensureStarted()
    }

    private fun addNewTab() {
        val panel = CodexSessionPanel(project)
        sessions.add(panel)
        val title = "Session ${sessions.size}"
        tabs.addTab(title, panel.component)
        tabs.selectedIndex = tabs.tabCount - 1
        panel.ensureStarted()
        panel.focusInput()
    }

    private fun closeCurrentTab() {
        if (tabs.tabCount <= 1) return
        val idx = tabs.selectedIndex
        if (idx < 0) return
        val session = sessions.removeAt(idx)
        try { session.dispose() } catch (_: Throwable) {}
        tabs.removeTabAt(idx)
        // Re-label remaining tabs
        for (i in sessions.indices) {
            tabs.setTitleAt(i, "Session ${i + 1}")
        }
    }

    private fun createToolbar(): javax.swing.JComponent {
        val newTabAction = object : AnAction("New Tab", "Open a new Codex session tab", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { addNewTab() }
        }
        val closeTabAction = object : AnAction("Close Tab", "Close current tab", AllIcons.Actions.Close) {
            override fun actionPerformed(e: AnActionEvent) { closeCurrentTab() }
        }
        val settingsAction = object : AnAction("Settings", "Open Codex settings", AllIcons.General.Gear) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, CodexSettingsConfigurable::class.java)
            }
        }
        val group = DefaultActionGroup().apply {
            add(newTabAction)
            add(closeTabAction)
            add(settingsAction)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("CodexTabsToolbar", group, true)
        toolbar.targetComponent = rootPanel
        return toolbar.component
    }

    override fun dispose() {
        sessions.forEach { s -> try { s.dispose() } catch (_: Throwable) {} }
        sessions.clear()
    }
}
