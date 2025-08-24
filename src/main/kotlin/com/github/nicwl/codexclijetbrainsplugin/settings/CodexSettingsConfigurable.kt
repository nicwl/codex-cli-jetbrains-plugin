package com.github.nicwl.codexclijetbrainsplugin.settings

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.openapi.ui.DialogPanel
import javax.swing.JComponent

class CodexSettingsConfigurable : Configurable {
    private var ui: DialogPanel? = null

    private var codexPath: String = CodexSettingsState.getInstance().state.codexPath
    private var autoStart: Boolean = CodexSettingsState.getInstance().state.autoStart

    override fun getDisplayName(): String = "Codex CLI"

    override fun createComponent(): JComponent {
        val st = CodexSettingsState.getInstance().state
        codexPath = st.codexPath
        autoStart = st.autoStart

        ui = panel {
            row("Path to 'codex' executable") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(::codexPath)
            }.layout(RowLayout.PARENT_GRID)

            row {
                checkBox("Auto-start Codex session in Tool Window")
                    .bindSelected(::autoStart)
            }.topGap(TopGap.SMALL)
        }
        return ui as DialogPanel
    }

    override fun isModified(): Boolean {
        val s = CodexSettingsState.getInstance().state
        return s.codexPath != codexPath || s.autoStart != autoStart
    }

    override fun apply() {
        val st = CodexSettingsState.getInstance().state
        st.codexPath = codexPath.trim().ifEmpty { "codex" }
        st.autoStart = autoStart
    }

    override fun reset() {
        val st = CodexSettingsState.getInstance().state
        codexPath = st.codexPath
        autoStart = st.autoStart
        ui?.reset()
    }

    override fun disposeUIResources() {
        ui = null
    }
}
