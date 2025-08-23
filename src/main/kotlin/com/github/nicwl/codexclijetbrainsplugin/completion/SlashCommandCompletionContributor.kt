package com.github.nicwl.codexclijetbrainsplugin.completion

import com.github.nicwl.codexclijetbrainsplugin.shared.CodexKeys
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.intellij.openapi.diagnostic.Logger

class SlashCommandCompletionContributor : CompletionContributor() {
    private val log = Logger.getInstance(SlashCommandCompletionContributor::class.java)
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val editor = parameters.editor
                    val doc = editor.document
                    val text = doc.text
                    if (editor.getUserData(CodexKeys.CHAT_INPUT) != true) {
                        log.warn("[slash] skip: editor not marked as chat input")
                        return
                    }

                    log.warn("[slash] contributor invoked: text='${text.replace("\n", "\\n")}' len=${text.length}")

                    // Only when the entire input is a single token beginning with '/'
                    if (!text.startsWith('/')) { log.warn("[slash] skip: does not start with '/'"); return }
                    if (text.indexOfFirst { it.isWhitespace() } >= 0) { log.warn("[slash] skip: has whitespace"); return }

                    val prefixAfterSlash = text.drop(1)
                    val r = if (prefixAfterSlash.isNotEmpty()) result.withPrefixMatcher(prefixAfterSlash) else result
                    val countBefore = commands.size
                    commands.forEach { sc ->
                        val insert = "/${sc.id}" + if (sc.needsArg) " " else ""
                        r.addElement(
                            LookupElementBuilder
                                .create(insert)
                                .withPresentableText("/${sc.id}")
                                .withTailText("  —  ${sc.title}", true)
                                .withTypeText(sc.description, true)
                        )
                    }
                    log.warn("[slash] suggestions added: total=$countBefore prefix='${prefixAfterSlash}'")
                }
            }
        )
    }

    private data class SlashCommand(val id: String, val title: String, val description: String, val needsArg: Boolean = false)

    private val commands = listOf(
        SlashCommand("compact", "Compact context", "Summarize and compress context for the next turns"),
        SlashCommand("status", "Show status", "Show Codex session status and model info"),
        SlashCommand("diff", "Show git diff", "Display current workspace git diff with colors"),
        SlashCommand("mcp", "List MCP tools", "List configured MCP servers and tools"),
        SlashCommand("new", "New session", "Start a fresh Codex session"),
        SlashCommand("model", "Switch model", "Override the model for next turns", needsArg = true),
        SlashCommand("approvals", "Set approval mode", "Change approvals: untrusted | on_request | on_failure | never", needsArg = true),
        SlashCommand("quit", "Quit", "Close the Codex tool window")
    )
}
