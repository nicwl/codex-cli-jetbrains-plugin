package com.github.nicwl.codexclijetbrainsplugin.codex.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProtocolSerdeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        explicitNulls = false
    }

    private fun <T> enc(el: T, ser: kotlinx.serialization.KSerializer<T>): JsonElement =
        json.parseToJsonElement(json.encodeToString(ser, el))

    private fun parse(s: String) = json.parseToJsonElement(s)

    // ── Helpers
    private fun <T> assertEncodesTo(expected: String, actual: T, serializer: kotlinx.serialization.KSerializer<T>) {
        val got = enc(actual, serializer)
        val exp = parse(expected)
        assertEquals(exp, got)
    }

    private fun <T> assertDecodesFrom(input: String, expected: T, serializer: kotlinx.serialization.KSerializer<T>) {
        val got = json.decodeFromString(serializer, input)
        assertEquals(expected, got)
    }

    // ── Events: decode hardcoded JSON and verify encode structure ─────────────────────────

    @Test fun event_task_started() {
        val s = """{"id":"1","msg":{"type":"task_started"}}"""
        val ev = json.decodeFromString(Event.serializer(), s)
        assertEquals(EventMsg.TaskStarted, ev.msg)
        assertEncodesTo(s, ev, Event.serializer())
    }

    @Test fun event_task_complete() {
        val s = """{"id":"1","msg":{"type":"task_complete","last_agent_message":"done"}}"""
        val ev = json.decodeFromString(Event.serializer(), s)
        assertEquals(EventMsg.TaskComplete("done"), ev.msg)
        assertEncodesTo(s, ev, Event.serializer())
    }

    @Test fun event_session_configured() {
        val s = """{"id":"1","msg":{"type":"session_configured","session_id":"sess","model":"gpt","history_log_id":7,"history_entry_count":2}}"""
        val ev = json.decodeFromString(Event.serializer(), s)
        assertEquals(EventMsg.SessionConfigured("sess","gpt",7,2), ev.msg)
        assertEncodesTo(s, ev, Event.serializer())
    }

    @Test fun event_agent_message_delta_and_final() {
        val d = """{"id":"1","msg":{"type":"agent_message_delta","delta":"he"}}"""
        val dm = json.decodeFromString(Event.serializer(), d)
        assertEquals(EventMsg.AgentMessageDelta("he"), dm.msg)
        assertEncodesTo(d, dm, Event.serializer())

        val f = """{"id":"1","msg":{"type":"agent_message","message":"hello"}}"""
        val fm = json.decodeFromString(Event.serializer(), f)
        assertEquals(EventMsg.AgentMessage("hello"), fm.msg)
        assertEncodesTo(f, fm, Event.serializer())
    }

    @Test fun event_reasoning_variants() {
        val s1 = """{"id":"1","msg":{"type":"agent_reasoning","text":"why"}}"""
        val e1 = json.decodeFromString(Event.serializer(), s1)
        assertEquals(EventMsg.AgentReasoning("why"), e1.msg)
        assertEncodesTo(s1, e1, Event.serializer())

        val s2 = """{"id":"1","msg":{"type":"agent_reasoning_delta","delta":"w"}}"""
        val e2 = json.decodeFromString(Event.serializer(), s2)
        assertEquals(EventMsg.AgentReasoningDelta("w"), e2.msg)
        assertEncodesTo(s2, e2, Event.serializer())

        val s3 = """{"id":"1","msg":{"type":"agent_reasoning_raw_content","text":"raw"}}"""
        val e3 = json.decodeFromString(Event.serializer(), s3)
        assertEquals(EventMsg.AgentReasoningRawContent("raw"), e3.msg)
        assertEncodesTo(s3, e3, Event.serializer())

        val s4 = """{"id":"1","msg":{"type":"agent_reasoning_raw_content_delta","delta":"r"}}"""
        val e4 = json.decodeFromString(Event.serializer(), s4)
        assertEquals(EventMsg.AgentReasoningRawContentDelta("r"), e4.msg)
        assertEncodesTo(s4, e4, Event.serializer())

        val s5 = """{"id":"1","msg":{"type":"agent_reasoning_section_break"}}"""
        val e5 = json.decodeFromString(Event.serializer(), s5)
        assertEquals(EventMsg.AgentReasoningSectionBreak, e5.msg)
        assertEncodesTo(s5, e5, Event.serializer())
    }

    @Test fun event_token_count() {
        val s = """{"id":"1","msg":{"type":"token_count","input_tokens":10,"cached_input_tokens":2,"output_tokens":5,"reasoning_output_tokens":1,"total_tokens":16}}"""
        val ev = json.decodeFromString(Event.serializer(), s)
        assertEquals(EventMsg.TokenCount(10,2,5,1,16), ev.msg)
        assertEncodesTo(s, ev, Event.serializer())
    }

    @Test fun event_mcp_tool_call_begin_end() {
        val begin = """{"id":"1","msg":{"type":"mcp_tool_call_begin","call_id":"c1","invocation":{"server":"srv","tool":"tool","arguments":{"x":1}}}}"""
        val eb = json.decodeFromString(Event.serializer(), begin)
        assertEncodesTo(begin, eb, Event.serializer())

        val endOk = """{"id":"1","msg":{"type":"mcp_tool_call_end","call_id":"c1","invocation":{"server":"srv","tool":"tool","arguments":{"x":1}},"duration":{"secs":1,"nanos":2},"result":{"Ok":{}}}}"""
        val eo = json.decodeFromString(Event.serializer(), endOk)
        assertTrue((eo.msg as EventMsg.McpToolCallEnd).result is EventMsg.McpToolCallResult.Ok)
        assertEncodesTo(endOk, eo, Event.serializer())

        val endErr = """{"id":"1","msg":{"type":"mcp_tool_call_end","call_id":"c1","invocation":{"server":"srv","tool":"tool","arguments":{"x":1}},"duration":{"secs":1,"nanos":2},"result":{"Err":"boom"}}}"""
        val ee = json.decodeFromString(Event.serializer(), endErr)
        assertTrue((ee.msg as EventMsg.McpToolCallEnd).result is EventMsg.McpToolCallResult.Err)
        assertEncodesTo(endErr, ee, Event.serializer())
    }

    @Test fun event_exec_stream() {
        val begin = """{"id":"1","msg":{"type":"exec_command_begin","call_id":"cid","command":["echo","hi"],"cwd":"/tmp","parsed_cmd":[{"Unknown":{"cmd":"echo hi"}}]}}"""
        val eb = json.decodeFromString(Event.serializer(), begin)
        assertEncodesTo(begin, eb, Event.serializer())

        val delta = """{"id":"1","msg":{"type":"exec_command_output_delta","call_id":"cid","stream":"stdout","chunk":[0,1,2]}}"""
        val ed = json.decodeFromString(Event.serializer(), delta)
        assertEncodesTo(delta, ed, Event.serializer())

        val end = """{"id":"1","msg":{"type":"exec_command_end","call_id":"cid","stdout":"o","stderr":"e","exit_code":0,"duration":{"secs":0,"nanos":1}}}"""
        val ee = json.decodeFromString(Event.serializer(), end)
        assertEncodesTo(end, ee, Event.serializer())
    }

    @Test fun event_approvals_and_patch_apply() {
        val execReq = """{"id":"1","msg":{"type":"exec_approval_request","call_id":"c","command":["ls"],"cwd":"/w"}}"""
        val ea = json.decodeFromString(Event.serializer(), execReq)
        assertEncodesTo(execReq, ea, Event.serializer())

        val patchReq = """{"id":"1","msg":{"type":"apply_patch_approval_request","call_id":"c","changes":{"a":{"add":{"content":"x"}},"b":{"delete":null},"c":{"update":{"unified_diff":"d"}}},"reason":"why"}}"""
        val pr = json.decodeFromString(Event.serializer(), patchReq)
        assertEncodesTo(patchReq, pr, Event.serializer())

        val applyBegin = """{"id":"1","msg":{"type":"patch_apply_begin","call_id":"c","auto_approved":true,"changes":{"a":{"add":{"content":"x"}}}}}"""
        val ab = json.decodeFromString(Event.serializer(), applyBegin)
        assertEncodesTo(applyBegin, ab, Event.serializer())

        val applyEnd = """{"id":"1","msg":{"type":"patch_apply_end","call_id":"c","stdout":"","stderr":"","success":true}}"""
        val ae = json.decodeFromString(Event.serializer(), applyEnd)
        assertEncodesTo(applyEnd, ae, Event.serializer())
    }

    @Test fun event_turn_diff_history_mcp_list_plan_misc() {
        val diff = """{"id":"1","msg":{"type":"turn_diff","unified_diff":"--- a\n+++ b\n"}}"""
        val ed = json.decodeFromString(Event.serializer(), diff)
        assertEncodesTo(diff, ed, Event.serializer())

        val histWith = """{"id":"1","msg":{"type":"get_history_entry_response","offset":0,"log_id":1,"entry":{"session_id":"s","ts":123,"text":"hi"}}}"""
        val hw = json.decodeFromString(Event.serializer(), histWith)
        assertEncodesTo(histWith, hw, Event.serializer())

        val histNone = """{"id":"1","msg":{"type":"get_history_entry_response","offset":1,"log_id":1}}"""
        val hn = json.decodeFromString(Event.serializer(), histNone)
        assertEncodesTo(histNone, hn, Event.serializer())

        val tools = buildJsonObject {
            put("id", "1")
            putJsonObject("msg") {
                put("type", "mcp_list_tools_response")
                putJsonObject("tools") {
                    putJsonObject("srv.t") { put("name", "t"); put("description", "d") }
                }
            }
        }
        val tl = json.decodeFromString(Event.serializer(), tools.toString())
        assertEncodesTo(tools.toString(), tl, Event.serializer())

        val plan = """{"id":"1","msg":{"type":"plan_update","explanation":"e","plan":[{"step":"s","status":"pending"}]}}"""
        val pu = json.decodeFromString(Event.serializer(), plan)
        assertEncodesTo(plan, pu, Event.serializer())

        val bg = """{"id":"1","msg":{"type":"background_event","message":"log"}}"""
        val be = json.decodeFromString(Event.serializer(), bg)
        assertEncodesTo(bg, be, Event.serializer())

        val aborted = """{"id":"1","msg":{"type":"turn_aborted","reason":"interrupted"}}"""
        val ab = json.decodeFromString(Event.serializer(), aborted)
        assertEncodesTo(aborted, ab, Event.serializer())

        val sd = """{"id":"1","msg":{"type":"shutdown_complete"}}"""
        val sc = json.decodeFromString(Event.serializer(), sd)
        assertEncodesTo(sd, sc, Event.serializer())

        val err = """{"id":"1","msg":{"type":"error","message":"oops"}}"""
        val er = json.decodeFromString(Event.serializer(), err)
        assertEncodesTo(err, er, Event.serializer())
    }

    // ── Externally tagged enums directly ──────────────────────────────────────
    @Test fun file_change_external_decode_and_encode() {
        val addS = """{"add":{"content":"x"}}"""
        val add = json.decodeFromString(EventMsg.FileChange.serializer(), addS)
        assertEquals(EventMsg.FileChange.Add("x"), add)
        assertEncodesTo(addS, add, EventMsg.FileChange.serializer())

        val delS = """{"delete":null}"""
        val del = json.decodeFromString(EventMsg.FileChange.serializer(), delS)
        assertEquals(EventMsg.FileChange.Delete, del)
        assertEncodesTo(delS, del, EventMsg.FileChange.serializer())

        val updS = """{"update":{"unified_diff":"d"}}"""
        val upd = json.decodeFromString(EventMsg.FileChange.serializer(), updS)
        assertEquals(EventMsg.FileChange.Update("d", null), upd)
        assertEncodesTo(updS, upd, EventMsg.FileChange.serializer())
    }

    @Test fun parsed_command_external_decode_and_encode() {
        val s = """{"Read":{"cmd":"cat","name":"file"}}"""
        val pc = json.decodeFromString(EventMsg.ParsedCommand.serializer(), s)
        assertEquals(EventMsg.ParsedCommand.Read("cat","file"), pc)
        assertEncodesTo(s, pc, EventMsg.ParsedCommand.serializer())
    }

    @Test fun mcp_tool_call_result_external_decode_and_encode() {
        val okJson = """{"Ok":{}}""" // no fields when all are null by default
        val ok = json.decodeFromString(EventMsg.McpToolCallResult.serializer(), okJson)
        assertTrue(ok is EventMsg.McpToolCallResult.Ok)
        assertEncodesTo(okJson, ok, EventMsg.McpToolCallResult.serializer())

        val errJson = """{"Err":"boom"}"""
        val err = json.decodeFromString(EventMsg.McpToolCallResult.serializer(), errJson)
        assertTrue(err is EventMsg.McpToolCallResult.Err)
        assertEncodesTo(errJson, err, EventMsg.McpToolCallResult.serializer())
    }

    // ── Submissions / Ops (encode structure, decode back) ─────────────────────
    @Test fun submissions_encode_shape_and_decode() {
        fun expect(sub: Submission, expected: JsonObject) {
            val got = enc(sub, Submission.serializer())
            assertEquals(expected, got)
            val back = json.decodeFromString(Submission.serializer(), expected.toString())
            assertEquals(sub, back)
        }

        expect(Submission("1", Op.Interrupt), buildJsonObject { put("id","1"); putJsonObject("op") { put("type","interrupt") } })

        expect(
            Submission("2", Op.UserInput(listOf(InputItem.Text("hi")))),
            parse("""{"id":"2","op":{"type":"user_input","items":[{"type":"text","text":"hi"}]}}""") as JsonObject
        )

        val turn = Submission("3", Op.UserTurn(items = listOf(InputItem.Text("ok")), cwd = "/w",
            approvalPolicy = AskForApproval.OnRequest,
            sandboxPolicy = SandboxPolicy.WorkspaceWrite(listOf("/w"), false, false, false),
            model = "gpt-test"))
        val turnExp = parse("""{"id":"3","op":{"type":"user_turn","items":[{"type":"text","text":"ok"}],"cwd":"/w","approval_policy":"on-request","sandbox_policy":{"mode":"workspace-write","writable_roots":["/w"],"network_access":false,"exclude_tmpdir_env_var":false,"exclude_slash_tmp":false},"model":"gpt-test","effort":"medium","summary":"auto"}}""") as JsonObject
        expect(turn, turnExp)

        val otc = Submission("4", Op.OverrideTurnContext(cwd = "/w"))
        val otcExp = parse("""{"id":"4","op":{"type":"override_turn_context","cwd":"/w"}}""") as JsonObject
        expect(otc, otcExp)

        val ea = Submission("5", Op.ExecApproval(id="x", decision = ReviewDecision.Approved))
        val eaExp = parse("""{"id":"5","op":{"type":"exec_approval","id":"x","decision":"approved"}}""") as JsonObject
        expect(ea, eaExp)

        val pa = Submission("6", Op.PatchApproval(id="y", decision = ReviewDecision.Denied))
        val paExp = parse("""{"id":"6","op":{"type":"patch_approval","id":"y","decision":"denied"}}""") as JsonObject
        expect(pa, paExp)

        val hist = Submission("7", Op.AddToHistory("note"))
        val histExp = parse("""{"id":"7","op":{"type":"add_to_history","text":"note"}}""") as JsonObject
        expect(hist, histExp)

        val getHist = Submission("8", Op.GetHistoryEntryRequest(offset=0, logId=1L))
        val getHistExp = parse("""{"id":"8","op":{"type":"get_history_entry_request","offset":0,"log_id":1}}""") as JsonObject
        expect(getHist, getHistExp)

        val list = Submission("9", Op.ListMcpTools)
        val listExp = parse("""{"id":"9","op":{"type":"list_mcp_tools"}}""") as JsonObject
        expect(list, listExp)

        val compact = Submission("10", Op.Compact)
        val compactExp = parse("""{"id":"10","op":{"type":"compact"}}""") as JsonObject
        expect(compact, compactExp)

        val shutdown = Submission("11", Op.Shutdown)
        val shutdownExp = parse("""{"id":"11","op":{"type":"shutdown"}}""") as JsonObject
        expect(shutdown, shutdownExp)
    }
}
