package com.github.nicwl.codexclijetbrainsplugin.settings

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.BoxLayout

class CodexSettingsConfigurable : Configurable {
    private val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val codexPathField = JBTextField()
    private val autoStartBox = JBCheckBox("Auto-start Codex session in Tool Window")

    override fun getDisplayName(): String = "Codex CLI"

    override fun createComponent(): JComponent {
        val settings = CodexSettingsState.getInstance().state
        codexPathField.text = settings.codexPath
        autoStartBox.isSelected = settings.autoStart

        panel.add(JLabel("Path to 'codex' executable"))
        panel.add(codexPathField)
        panel.add(autoStartBox)
        return panel
    }

    override fun isModified(): Boolean {
        val s = CodexSettingsState.getInstance().state
        return s.codexPath != codexPathField.text || s.autoStart != autoStartBox.isSelected
    }

    override fun apply() {
        val st = CodexSettingsState.getInstance().state
        st.codexPath = codexPathField.text.trim().ifEmpty { "codex" }
        st.autoStart = autoStartBox.isSelected
    }
}

