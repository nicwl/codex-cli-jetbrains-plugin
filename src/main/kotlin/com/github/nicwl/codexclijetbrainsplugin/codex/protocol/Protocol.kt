package com.github.nicwl.codexclijetbrainsplugin.codex.protocol

import com.github.nicwl.codexclijetbrainsplugin.codex.protocol.json.ExternalTagging
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class Event(
    val id: String,
    val msg: EventMsg,
)

@Serializable
sealed class EventMsg {
    // Session/task lifecycle
    @Serializable
    @SerialName("task_started")
    data object TaskStarted : EventMsg()

    @Serializable
    @SerialName("task_complete")
    data class TaskComplete(@SerialName("last_agent_message") val lastAgentMessage: String? = null) : EventMsg()

    @Serializable
    @SerialName("session_configured")
    data class SessionConfigured(
        @SerialName("session_id") val sessionId: String,
        val model: String,
        @SerialName("history_log_id") val historyLogId: Long,
        @SerialName("history_entry_count") val historyEntryCount: Int,
    ) : EventMsg()

    // Assistant messages
    @Serializable
    @SerialName("agent_message")
    data class AgentMessage(val message: String) : EventMsg()

    @Serializable
    @SerialName("agent_message_delta")
    data class AgentMessageDelta(val delta: String) : EventMsg()

    // Reasoning summary/content
    @Serializable
    @SerialName("agent_reasoning")
    data class AgentReasoning(val text: String) : EventMsg()

    @Serializable
    @SerialName("agent_reasoning_delta")
    data class AgentReasoningDelta(val delta: String) : EventMsg()

    @Serializable
    @SerialName("agent_reasoning_raw_content")
    data class AgentReasoningRawContent(val text: String) : EventMsg()

    @Serializable
    @SerialName("agent_reasoning_raw_content_delta")
    data class AgentReasoningRawContentDelta(val delta: String) : EventMsg()

    @Serializable
    @SerialName("agent_reasoning_section_break")
    data object AgentReasoningSectionBreak : EventMsg()

    // Token usage
    @Serializable
    @SerialName("token_count")
    data class TokenCount(
        @SerialName("input_tokens") val inputTokens: Long,
        @SerialName("cached_input_tokens") val cachedInputTokens: Long? = null,
        @SerialName("output_tokens") val outputTokens: Long,
        @SerialName("reasoning_output_tokens") val reasoningOutputTokens: Long? = null,
        @SerialName("total_tokens") val totalTokens: Long,
    ) : EventMsg()

    // MCP tool invocation
    @Serializable
    @SerialName("mcp_tool_call_begin")
    data class McpToolCallBegin(@SerialName("call_id") val callId: String, val invocation: McpInvocation) : EventMsg()

    @Serializable
    @SerialName("mcp_tool_call_end")
    data class McpToolCallEnd(
        @SerialName("call_id") val callId: String,
        val invocation: McpInvocation,
        val duration: Duration,
        val result: McpToolCallResult,
    ) : EventMsg()

    @Serializable
    data class McpInvocation(val server: String, val tool: String, val arguments: JsonElement? = null)

    @Serializable(with = McpToolCallResultSerializer::class)
    sealed class McpToolCallResult {
        @Serializable
        @SerialName("Ok")
        data class Ok(val value: CallToolResult) : McpToolCallResult()

        @Serializable
        @SerialName("Err")
        data class Err(val error: String) : McpToolCallResult()
    }

    // Exec command streaming
    @Serializable
    @SerialName("exec_command_begin")
    data class ExecCommandBegin(
        @SerialName("call_id") val callId: String,
        val command: List<String>,
        val cwd: String,
        @Serializable(with = ParsedCommandListExternalSerializer::class) @SerialName("parsed_cmd") val parsedCmd: List<ParsedCommand> = emptyList(),
    ) : EventMsg()

    @Serializable
    @SerialName("exec_command_output_delta")
    data class ExecCommandOutputDelta(
        @SerialName("call_id") val callId: String,
        val stream: ExecOutputStream,
        // Matches serde_bytes::ByteBuf JSON representation (array of numbers)
        val chunk: ImmutableBytes,
    ) : EventMsg()

    @Serializable
    @SerialName("exec_command_end")
    data class ExecCommandEnd(
        @SerialName("call_id") val callId: String,
        val stdout: String,
        val stderr: String,
        @SerialName("exit_code") val exitCode: Int,
        val duration: Duration,
    ) : EventMsg()

    @Serializable
    enum class ExecOutputStream {
        @SerialName("stdout")
        Stdout,

        @SerialName("stderr")
        Stderr
    }

    // Exec/Patch approvals
    @Serializable
    @SerialName("exec_approval_request")
    data class ExecApprovalRequest(
        @SerialName("call_id") val callId: String,
        val command: List<String>,
        val cwd: String,
        val reason: String? = null,
    ) : EventMsg()

    @Serializable
    @SerialName("apply_patch_approval_request")
    data class ApplyPatchApprovalRequest(
        @SerialName("call_id") val callId: String,
        @Serializable(with = FileChangeMapExternalSerializer::class) val changes: Map<String, FileChange>,
        val reason: String? = null,
        @SerialName("grant_root") val grantRoot: String? = null,
    ) : EventMsg()

    // Patch application notifications
    @Serializable
    @SerialName("patch_apply_begin")
    data class PatchApplyBegin(
        @SerialName("call_id") val callId: String,
        @SerialName("auto_approved") val autoApproved: Boolean,
        @Serializable(with = FileChangeMapExternalSerializer::class) val changes: Map<String, FileChange>,
    ) : EventMsg()

    @Serializable
    @SerialName("patch_apply_end")
    data class PatchApplyEnd(
        @SerialName("call_id") val callId: String,
        val stdout: String,
        val stderr: String,
        val success: Boolean,
    ) : EventMsg()

    // Diff + history
    @Serializable
    @SerialName("turn_diff")
    data class TurnDiff(@SerialName("unified_diff") val unifiedDiff: String) : EventMsg()

    @Serializable
    @SerialName("get_history_entry_response")
    data class GetHistoryEntryResponse(
        val offset: Int,
        @SerialName("log_id") val logId: Long,
        val entry: HistoryEntry? = null,
    ) : EventMsg()

    // MCP tools list
    @Serializable
    @SerialName("mcp_list_tools_response")
    data class McpListToolsResponse(val tools: Map<String, McpTool> = emptyMap()) : EventMsg()

    // Plan update models (for the plan tool)
    @Serializable
    @SerialName("plan_update")
    data class PlanUpdate(val explanation: String? = null, val plan: List<PlanItemArg>) : EventMsg()

    // Background/logging
    @Serializable
    @SerialName("background_event")
    data class BackgroundEvent(val message: String) : EventMsg()

    // Aborts and shutdown
    @Serializable
    @SerialName("turn_aborted")
    data class TurnAborted(val reason: TurnAbortReason) : EventMsg()

    @Serializable
    @SerialName("shutdown_complete")
    data object ShutdownComplete : EventMsg()

    // Error
    @Serializable
    @SerialName("error")
    data class Error(val message: String) : EventMsg()

    // Common nested types
    @Serializable
    data class Duration(val secs: Long, val nanos: Int)

    @Serializable
    enum class TurnAbortReason {
        @SerialName("interrupted")
        Interrupted,

        @SerialName("replaced")
        Replaced
    }

    @Serializable
    data class HistoryEntry(@SerialName("session_id") val sessionId: String, val ts: Long, val text: String)

    @Serializable
    @JsonClassDiscriminator("type")
    sealed class FileChange {
        @Serializable
        @SerialName("add")
        data class Add(val content: String) : FileChange()

        @Serializable
        @SerialName("delete")
        data object Delete : FileChange()

        @Serializable
        @SerialName("update")
        data class Update(
            @SerialName("unified_diff") val unifiedDiff: String,
            @SerialName("move_path") val movePath: String? = null,
        ) : FileChange()
    }

    // Parsed command structures (heuristics for classifying shell commands)
    @Serializable
    @JsonClassDiscriminator("type")
    sealed class ParsedCommand {
        @Serializable
        @SerialName("Read")
        data class Read(val cmd: String, val name: String) : ParsedCommand()

        @Serializable
        @SerialName("ListFiles")
        data class ListFiles(val cmd: String, val path: String? = null) : ParsedCommand()

        @Serializable
        @SerialName("Search")
        data class Search(val cmd: String, val query: String? = null, val path: String? = null) : ParsedCommand()

        @Serializable
        @SerialName("Format")
        data class Format(val cmd: String, val tool: String? = null, val targets: List<String>? = null) :
            ParsedCommand()

        @Serializable
        @SerialName("Test")
        data class Test(val cmd: String) : ParsedCommand()

        @Serializable
        @SerialName("Lint")
        data class Lint(val cmd: String, val tool: String? = null, val targets: List<String>? = null) : ParsedCommand()

        @Serializable
        @SerialName("Noop")
        data class Noop(val cmd: String) : ParsedCommand()

        @Serializable
        @SerialName("Unknown")
        data class Unknown(val cmd: String) : ParsedCommand()
    }

    // MCP tool definition (subset sufficient to deserialize faithfully)
    @Serializable
    data class McpTool(
        val name: String,
        val description: String? = null,
        @SerialName("title") val title: String? = null,
        @SerialName("inputSchema") val inputSchema: JsonElement? = null,
        @SerialName("outputSchema") val outputSchema: JsonElement? = null,
        val annotations: JsonElement? = null,
    )

    // Plan step models (mirrors codex-rs plan_tool.rs)
    @Serializable
    enum class StepStatus {
        @SerialName("pending")
        Pending,

        @SerialName("in_progress")
        InProgress,

        @SerialName("completed")
        Completed
    }

    @Serializable
    data class PlanItemArg(val step: String, val status: StepStatus)
}

@Serializable
data class CallToolResult(
    val content: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    @SerialName("structured_content") val structuredContent: JsonElement? = null,
)

// ───────── Custom serializers for external-tagged enums from Rust ─────────

// Legacy explicit serializers avoid init-order cycles when the sealed class references its own serializer
object ParsedCommandExternalSerializer :
    KSerializer<EventMsg.ParsedCommand> by ExternalTagging.forType(EventMsg.ParsedCommand::class)

object ParsedCommandListExternalSerializer :
    KSerializer<List<EventMsg.ParsedCommand>> by ListSerializer(ParsedCommandExternalSerializer)

// ─────────────────────────────────────────────────────────────────────────────
// Outgoing submissions (SQ) – mirrors Rust Submission/Op shapes
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class Submission(val id: String, val op: Op)

@Serializable
sealed class Op {
    @Serializable
    @SerialName("interrupt")
    data object Interrupt : Op()

    @Serializable
    @SerialName("user_input")
    data class UserInput(val items: List<InputItem>) : Op()

    @Serializable
    @SerialName("user_turn")
    data class UserTurn(
        val items: List<InputItem>,
        val cwd: String,
        @SerialName("approval_policy") val approvalPolicy: AskForApproval,
        @SerialName("sandbox_policy") val sandboxPolicy: SandboxPolicy,
        val model: String,
        @kotlinx.serialization.EncodeDefault val effort: ReasoningEffort = ReasoningEffort.Medium,
        @kotlinx.serialization.EncodeDefault val summary: ReasoningSummary = ReasoningSummary.Auto,
    ) : Op()

    @Serializable
    @SerialName("override_turn_context")
    data class OverrideTurnContext(
        val cwd: String? = null,
        @SerialName("approval_policy") val approvalPolicy: AskForApproval? = null,
        @SerialName("sandbox_policy") val sandboxPolicy: SandboxPolicy? = null,
        val model: String? = null,
        val effort: ReasoningEffort? = null,
        val summary: ReasoningSummary? = null,
    ) : Op()

    @Serializable
    @SerialName("exec_approval")
    data class ExecApproval(val id: String, val decision: ReviewDecision) : Op()

    @Serializable
    @SerialName("patch_approval")
    data class PatchApproval(val id: String, val decision: ReviewDecision) : Op()

    @Serializable
    @SerialName("add_to_history")
    data class AddToHistory(val text: String) : Op()

    @Serializable
    @SerialName("get_history_entry_request")
    data class GetHistoryEntryRequest(val offset: Int, @SerialName("log_id") val logId: Long) : Op()

    @Serializable
    @SerialName("list_mcp_tools")
    data object ListMcpTools : Op()

    @Serializable
    @SerialName("compact")
    data object Compact : Op()

    @Serializable
    @SerialName("shutdown")
    data object Shutdown : Op()
}

@Serializable
sealed class InputItem {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : InputItem()

    @Serializable
    @SerialName("image")
    data class Image(@SerialName("image_url") val imageUrl: String) : InputItem()

    @Serializable
    @SerialName("local_image")
    data class LocalImage(val path: String) : InputItem()
}

@Serializable
enum class ReviewDecision {
    @SerialName("approved")
    Approved,

    @SerialName("approved_for_session")
    ApprovedForSession,

    @SerialName("denied")
    Denied,

    @SerialName("abort")
    Abort,
}

@Serializable
enum class AskForApproval {
    @SerialName("untrusted")
    UnlessTrusted,

    @SerialName("on-failure")
    OnFailure,

    @SerialName("on-request")
    OnRequest,

    @SerialName("never")
    Never,
}

@Serializable
enum class ReasoningEffort {
    @SerialName("minimal")
    Minimal,

    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High
}

@Serializable
enum class ReasoningSummary {
    @SerialName("auto")
    Auto,

    @SerialName("concise")
    Concise,

    @SerialName("detailed")
    Detailed,

    @SerialName("none")
    None
}

@Serializable
@JsonClassDiscriminator("mode")
sealed class SandboxPolicy {
    @Serializable
    @SerialName("danger-full-access")
    data object DangerFullAccess : SandboxPolicy()

    @Serializable
    @SerialName("read-only")
    data object ReadOnly : SandboxPolicy()

    @Serializable
    @SerialName("workspace-write")
    data class WorkspaceWrite(
        @SerialName("writable_roots") val writableRoots: List<String> = emptyList(),
        @kotlinx.serialization.EncodeDefault @SerialName("network_access") val networkAccess: Boolean = false,
        @kotlinx.serialization.EncodeDefault @SerialName("exclude_tmpdir_env_var") val excludeTmpdirEnvVar: Boolean = false,
        @kotlinx.serialization.EncodeDefault @SerialName("exclude_slash_tmp") val excludeSlashTmp: Boolean = false,
    ) : SandboxPolicy()
}

object FileChangeExternalSerializer :
    KSerializer<EventMsg.FileChange> by ExternalTagging.forType(EventMsg.FileChange::class)

object FileChangeMapExternalSerializer :
    KSerializer<Map<String, EventMsg.FileChange>> by MapSerializer(String.serializer(), FileChangeExternalSerializer)

object McpToolCallResultSerializer : KSerializer<EventMsg.McpToolCallResult> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("McpToolCallResult")

    override fun deserialize(decoder: Decoder): EventMsg.McpToolCallResult {
        val input = decoder as JsonDecoder
        val obj = input.decodeJsonElement().jsonObject
        require(obj.size == 1) { "McpToolCallResult expects single-key object, got ${obj.keys}" }
        val (tag, payload) = obj.entries.first()
        return when (tag) {
            "Ok" -> EventMsg.McpToolCallResult.Ok(
                value = input.json.decodeFromJsonElement(
                    CallToolResult.serializer(), payload
                )
            )

            "Err" -> EventMsg.McpToolCallResult.Err(error = payload.jsonPrimitive.content)
            else -> EventMsg.McpToolCallResult.Err(error = payload.toString())
        }
    }

    override fun serialize(encoder: Encoder, value: EventMsg.McpToolCallResult) {
        val output = encoder as JsonEncoder
        val json = when (value) {
            is EventMsg.McpToolCallResult.Ok -> JsonObject(
                mapOf(
                    "Ok" to output.json.encodeToJsonElement(
                        CallToolResult.serializer(), value.value
                    )
                )
            )

            is EventMsg.McpToolCallResult.Err -> JsonObject(mapOf("Err" to JsonPrimitive(value.error)))
        }
        output.encodeJsonElement(json)
    }
}

// Immutable bytes wrapper with JSON array-of-numbers representation
@Serializable(with = ImmutableBytesSerializer::class)
class ImmutableBytes private constructor(private val data: ByteArray) {
    companion object {
        fun from(bytes: ByteArray): ImmutableBytes = ImmutableBytes(bytes.copyOf())
        fun of(vararg values: Int): ImmutableBytes = ImmutableBytes(values.map { it.toByte() }.toByteArray())
    }

    fun toByteArray(): ByteArray = data.copyOf()
    val size: Int get() = data.size
    operator fun get(index: Int): Int = data[index].toInt() and 0xFF

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImmutableBytes) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
    override fun toString(): String = "ImmutableBytes(size=$size)"
}

object ImmutableBytesSerializer : KSerializer<ImmutableBytes> {
    override val descriptor = kotlinx.serialization.descriptors.buildClassSerialDescriptor("ImmutableBytes")

    override fun deserialize(decoder: Decoder): ImmutableBytes {
        val input = decoder as JsonDecoder
        val elem = input.decodeJsonElement()
        val arr = elem as? JsonArray ?: error("ImmutableBytes expects JSON array")
        val bytes = ByteArray(arr.size)
        var i = 0
        for (e in arr) {
            val v = e.jsonPrimitive.int
            require(v in 0..255) { "byte out of range: $v" }
            bytes[i++] = v.toByte()
        }
        return ImmutableBytes.from(bytes)
    }

    override fun serialize(encoder: Encoder, value: ImmutableBytes) {
        val output = encoder as JsonEncoder
        val arr = value.toByteArray().map { JsonPrimitive(it.toInt() and 0xFF) }
        output.encodeJsonElement(JsonArray(arr))
    }
}
