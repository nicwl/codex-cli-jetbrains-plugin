package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSessionService
import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.github.nicwl.codexclijetbrainsplugin.settings.CodexSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CodexToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodexToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)

        if (CodexSettingsState.getInstance().state.autoStart) {
            panel.ensureStarted()
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

