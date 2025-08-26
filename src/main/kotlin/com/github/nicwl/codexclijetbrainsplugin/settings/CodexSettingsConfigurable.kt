package com.github.nicwl.codexclijetbrainsplugin.settings

import com.github.nicwl.codexclijetbrainsplugin.codex.CodexSettingsState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class CodexSettingsConfigurable : Configurable {
    private var ui: DialogPanel? = null

    override fun getDisplayName(): String = "Codex CLI"

    override fun createComponent(): JComponent {
        val settings = CodexSettingsState.getInstance()
        ui = panel {
            row("Path to 'codex' executable") {
                textField()
                    .align(AlignX.FILL)
                    .bindText(
                        getter = { settings.state.codexPath },
                        setter = { settings.state.codexPath = it.trim().ifEmpty { "codex" } }
                    )
            }.layout(RowLayout.PARENT_GRID)

            row {
                checkBox("Auto-start Codex session in Tool Window")
                    .bindSelected(
                        getter = { settings.state.autoStart },
                        setter = { settings.state.autoStart = it }
                    )
            }.topGap(TopGap.SMALL)
        }
        return ui as DialogPanel
    }

    override fun isModified(): Boolean = ui?.isModified() ?: false

    override fun apply() {
        ui?.apply()
    }

    override fun reset() {
        ui?.reset()
    }

    override fun disposeUIResources() {
        ui = null
    }
}
