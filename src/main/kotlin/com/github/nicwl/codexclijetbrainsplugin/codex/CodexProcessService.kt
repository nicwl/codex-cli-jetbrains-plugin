package com.github.nicwl.codexclijetbrainsplugin.codex

import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.Event
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

interface CodexTransportListener {
    fun onEvent(ev: Event) {}
    fun onStdErr(line: String) {}
    fun onExited(exitCode: Int) {}
}

@Service(Service.Level.PROJECT)
class CodexProcessService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(CodexProcessService::class.java)
    private val jsonCodec = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Volatile private var processHandler: KillableColoredProcessHandler? = null
    @Volatile private var stdin: BufferedWriter? = null
    private val listeners = CopyOnWriteArrayList<CodexTransportListener>()

    fun addListener(listener: CodexTransportListener) { listeners.add(listener) }
    fun removeListener(listener: CodexTransportListener) { listeners.remove(listener) }

    fun isRunning(): Boolean = processHandler?.isProcessTerminated == false

    fun start(args: List<String>, workDir: String?) {
        if (isRunning()) return
        val cmd = GeneralCommandLine(args).withCharset(StandardCharsets.UTF_8)
        workDir?.let { cmd.withWorkDirectory(it) }
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        // Prevent the parent process' API key from leaking to child unexpectedly
        cmd.environment.remove("OPENAI_API_KEY")

        val handler = try {
            log.warn("Starting codex process: ${args.joinToString(" ")} (cwd=${cmd.workDirectory?.path ?: ""})")
            KillableColoredProcessHandler(cmd)
        } catch (t: Throwable) {
            log.warn("Failed to start codex", t)
            return
        }
        processHandler = handler

        ApplicationManager.getApplication().executeOnPooledThread {
            try { stdin = BufferedWriter(OutputStreamWriter(handler.processInput, StandardCharsets.UTF_8)) }
            catch (t: Throwable) { log.warn("Failed to attach stdin", t) }
        }

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {}
            override fun processTerminated(event: ProcessEvent) {
                val code = event.exitCode
                listeners.forEach { l -> runSafely { l.onExited(code) } }
                log.warn("codex exited with $code")
            }

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                when (outputType) {
                    ProcessOutputType.STDOUT -> synchronized(stdoutBuffer) {
                        stdoutBuffer.append(text)
                        var idx: Int
                        while (true) {
                            idx = stdoutBuffer.indexOf("\n")
                            if (idx < 0) break
                            val line = stdoutBuffer.substring(0, idx).trim()
                            stdoutBuffer.delete(0, idx + 1)
                            if (line.isNotEmpty()) handleStdoutLine(line)
                        }
                    }
                    ProcessOutputType.STDERR -> synchronized(stderrBuffer) {
                        stderrBuffer.append(text)
                        var idx: Int
                        while (true) {
                            idx = stderrBuffer.indexOf("\n")
                            if (idx < 0) break
                            val line = stderrBuffer.substring(0, idx).trim()
                            stderrBuffer.delete(0, idx + 1)
                            if (line.isNotEmpty()) {
                                // codex proto writes logs to stderr; forward to our own stderr, not UI
                                try {
                                    System.err.println(line)
                                } catch (_: Throwable) {
                                    // fallback to idea logger
                                    log.warn(line)
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        })

        handler.startNotify()
    }

    private fun handleStdoutLine(line: String) {
        log.warn("codex stdout: $line")
        val ev: Event = try {
            jsonCodec.decodeFromString(Event.serializer(), line)
        } catch (e: SerializationException) {
            log.warn("Could not deserialize codex event from line: $line", e)
            return
        }
        listeners.forEach { l -> runSafely { l.onEvent(ev) } }
    }

    fun sendRaw(line: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val w = stdin
                if (w != null) {
                    w.append(line)
                    w.append('\n')
                    w.flush()
                } else {
                    log.warn("sendRaw: stdin not attached (is the process running?)")
                }
            } catch (t: Throwable) {
                log.warn("Failed to write to codex stdin", t)
            }
        }
    }

    override fun dispose() {
        try { processHandler?.destroyProcess() } catch (_: Throwable) {}
        processHandler = null
        stdin = null
    }

    private inline fun runSafely(crossinline r: () -> Unit) {
        try { r() } catch (t: Throwable) { log.warn("listener callback error", t) }
    }
}
