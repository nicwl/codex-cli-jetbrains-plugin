package com.github.nicwl.codexclijetbrainsplugin.codex

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "CodexSettings", storages = [Storage("codex-cli-plugin.xml")])
@Service
class CodexSettingsState : PersistentStateComponent<CodexSettingsState.State> {
    data class State(
        var codexPath: String = "codex",
        var autoStart: Boolean = true,
        var approvalPolicy: String = "on_request", // matches AskForApproval JSON
        var sandboxMode: String = "workspace-write" // matches SandboxPolicy JSON
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): CodexSettingsState = service()
    }
}

