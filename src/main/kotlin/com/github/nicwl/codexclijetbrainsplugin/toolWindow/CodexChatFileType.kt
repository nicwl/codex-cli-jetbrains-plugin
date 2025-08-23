package com.github.nicwl.codexclijetbrainsplugin.toolWindow

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object CodexChatFileType : LanguageFileType(CodexChatLanguage) {
    override fun getName(): String = "CodexChat"
    override fun getDescription(): String = "Codex chat input"
    override fun getDefaultExtension(): String = "codexchat"
    override fun getIcon(): Icon? = null
}

