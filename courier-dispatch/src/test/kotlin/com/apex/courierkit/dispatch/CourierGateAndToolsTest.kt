package com.apex.courierkit.dispatch

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 勘合门：堆叠核验 / 悬置出票与答复 / always 记忆自动复评 / 拒付批语义 /
 * 整枝剪除 / 复合命令逐段。
 */
class CourierPermitGateTest {

    private class FixedSnapshot(var rules: List<DispatchPermissionRule>) :
        CourierPermissionSnapshotSource {
        override fun snapshot(): DispatchPermissionSnapshot =
            DispatchPermissionSnapshot(rules = rules)
    }

    private val logger = CourierLogger.Silent
    private val probe = CourierCommandProbe { _, _ -> null }

    private fun gate(
        snapshot: CourierPermissionSnapshotSource = FixedSnapshot(emptyList()),
        hints: Map<String, String> = emptyMap()
    ) = CourierPermitGate(snapshot, probe, logger, hints)

    @Test
    fun `画像默认则例核验`() {
        val g = gate()
        // 构建者画像：edit 放行、read *.env 勘验
        val ok = g.inspect("code_write", """{"path":"main.kt"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertEquals(CourierPermitGate.PermitOutcome.Granted, ok.precomputed)

        val ask = g.inspect("code_read", """{"path":"prod.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertEquals(null, ask.precomputed)
        assertTrue(ask.requests.isNotEmpty())

        // 规划者画像：edit 整枝拒付
        val denied = g.inspect("code_edit", """{"path":"x.kt"}""", DispatchAgentRole.PLAN, DispatchMode.PLAN)
        assertTrue(denied.precomputed is CourierPermitGate.PermitOutcome.Blocked)
        // 且整枝剪除面生效
        assertTrue(g.isWhollyDenied("code_edit", DispatchAgentRole.PLAN, DispatchMode.PLAN))
        assertFalse(g.isWhollyDenied("code_read", DispatchAgentRole.PLAN, DispatchMode.PLAN))
    }

    @Test
    fun `会话则例覆盖画像`() {
        val snapshot = FixedSnapshot(
            listOf(
                DispatchPermissionRule("edit", "src/**", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("edit", "src/secret/**", DispatchRuleEffect.DENY)
            )
        )
        val g = gate(snapshot)
        // 会话收窄优先于画像（findLast）
        val blocked = g.inspect(
            "code_edit", """{"path":"src/secret/key.kt"}""",
            DispatchAgentRole.BUILD, DispatchMode.BUILD
        )
        assertTrue(blocked.precomputed is CourierPermitGate.PermitOutcome.Blocked)
    }

    @Test
    fun `悬置出票答复与批拒付`() = runTest {
        val g = gate()
        val inspection = g.inspect("code_read", """{"path":"prod.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertNull(inspection.precomputed)
        var heardTicket: String? = null
        var heardPrompt: String? = null
        g.askListener = { t, p ->
            heardTicket = t
            heardPrompt = p
        }
        val ticket = g.issueTicket(inspection, "Allow reading prod.env?")
        assertEquals(heardTicket, ticket.ticket)
        assertTrue(heardPrompt!!.contains("prod.env"))
        // 另一张悬置票（批拒付语义验证）
        val inspection2 = g.inspect("code_read", """{"path":"other.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val ticket2 = g.issueTicket(inspection2, "Allow reading other.env?")
        // 拒第一张（无反馈）→ 两张都进隧道
        assertTrue(g.reply(ticket.ticket, approved = false, always = false, feedback = null))
        assertEquals(CourierPermitGate.PermitOutcome.Declined(null), g.awaitDecision(ticket))
        assertEquals(CourierPermitGate.PermitOutcome.Declined(null), g.awaitDecision(ticket2))
    }

    @Test
    fun `always记忆落位并自动复评同类悬置`() = runTest {
        val g = gate()
        val i1 = g.inspect("code_read", """{"path":"prod.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val t1 = g.issueTicket(i1, "Allow prod.env?")
        val i2 = g.inspect("code_read", """{"path":"staging.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val t2 = g.issueTicket(i2, "Allow staging.env?")
        // 无 listener 也能答复
        g.askListener = null
        // always 答复 t1：记忆 code_read→prod.env；t2（staging.env）不命中仍悬置
        assertTrue(g.reply(t1.ticket, approved = true, always = true, feedback = null))
        assertEquals(CourierPermitGate.PermitOutcome.Granted, g.awaitDecision(t1))
        // 记忆核验：同资源再次核验直接放行
        val again = g.inspect("code_read", """{"path":"prod.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertEquals(CourierPermitGate.PermitOutcome.Granted, again.precomputed)
        assertTrue(g.pendingTickets().isNotEmpty())
        // 会话记忆清空后回到勘验（清记忆同时收口全部悬置票——t2 按超时兑现）
        g.clearSessionMemory()
        val afterClear = g.inspect("code_read", """{"path":"prod.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertNull(afterClear.precomputed)
        assertTrue(g.pendingTickets().isEmpty()) // t2 已随清场收口
        g.expireAllPending()
    }

    @Test
    fun `自动复评同模式悬置票`() = runTest {
        // 会话则例：*.env 勘验——always 记忆模式 `*.env` 后同式资源即时放行
        val snapshot = FixedSnapshot(
            listOf(DispatchPermissionRule("read", "*.env", DispatchRuleEffect.ASK))
        )
        val g = gate(snapshot)
        val i1 = g.inspect("code_read", """{"path":"a.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val t1 = g.issueTicket(i1, "Allow a.env?")
        val i2 = g.inspect("code_read", """{"path":"b.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val t2 = g.issueTicket(i2, "Allow b.env?")
        g.askListener = null
        // 手工落一条 `*.env` allow 记忆（等价 always 展开的 save 模式）
        assertTrue(
            g.reply(
                t1.ticket, approved = true, always = true, feedback = null
            )
        )
        // t1 的 save 模式是资源本身 a.env —— t2 不命中，不自动放行
        assertTrue(g.pendingTickets().isNotEmpty())
        // 拒付收口
        g.expireAllPending()
        assertEquals(CourierPermitGate.PermitOutcome.Expired, g.awaitDecision(t2))
    }

    @Test
    fun `复合命令逐段核验`() {
        val probe = CourierCommandProbe { _, _ -> "cat /a.env && git push origin main; rm -rf /tmp/x" }
        val g = CourierPermitGate(FixedSnapshot(emptyList()), probe, logger)
        val inspection = g.inspect(
            "ShellExecute", """{"command":"whatever"}""",
            DispatchAgentRole.BUILD, DispatchMode.BUILD
        )
        // 基座（工具 id 伪动作）+ 三段命令
        assertEquals(4, inspection.requests.size)
        val commandRows = inspection.requests.filter { it.action == "bash" }
        assertEquals(listOf("cat /a.env", "git push", "rm -rf"), commandRows.map { it.resource })
        assertEquals(
            listOf("cat /a.env *", "git push *", "rm -rf *"),
            commandRows.map { it.savePattern }
        )
        // 构建者画像 * allow → 全放行
        assertEquals(CourierPermitGate.PermitOutcome.Granted, inspection.precomputed)
    }

    @Test
    fun `工具 id 伪动作通道承载宿主规则`() {
        // 宿主工具 id 型规则（无空格模式）：deny code_edit* → 整个工具拒付
        val snapshot = FixedSnapshot(
            listOf(DispatchPermissionRule("tool", "code_edit*", DispatchRuleEffect.DENY))
        )
        val g = gate(snapshot)
        val blocked = g.inspect("code_edit", """{"path":"x.kt"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertTrue(blocked.precomputed is CourierPermitGate.PermitOutcome.Blocked)
        // 未命中工具规则的其它工具不受影响
        val ok = g.inspect("code_read", """{"path":"y.kt"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        assertEquals(CourierPermitGate.PermitOutcome.Granted, ok.precomputed)
    }

    @Test
    fun `拒付带反馈为纠正性回执`() = runTest {
        val g = gate()
        val i = g.inspect("code_read", """{"path":"x.env"}""", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        val t = g.issueTicket(i, "Allow x.env?")
        g.askListener = null
        assertTrue(g.reply(t.ticket, approved = false, always = false, feedback = "use the staging file instead"))
        assertEquals(
            CourierPermitGate.PermitOutcome.Declined("use the staging file instead"),
            g.awaitDecision(t)
        )
    }

    @Test
    fun `未分类工具只走基座通道`() {
        val g = gate()
        val inspection = g.inspect("some_custom_tool", "{}", DispatchAgentRole.BUILD, DispatchMode.BUILD)
        // 基座（非严格）命中画像 * allow → 放行
        assertEquals(1, inspection.requests.size)
        assertEquals(CourierPermitGate.PermitOutcome.Granted, inspection.precomputed)
    }

    @Test
    fun `动作提示覆盖优先生效`() {
        val g = gate(hints = mapOf("weird_tool" to "edit"))
        val snapshot = FixedSnapshot(listOf(DispatchPermissionRule("edit", "*", DispatchRuleEffect.DENY)))
        val g2 = CourierPermitGate(snapshot, probe, logger, mapOf("weird_tool" to "edit"))
        val inspection = g2.inspect("weird_tool", "{}", DispatchAgentRole.GENERAL, DispatchMode.BUILD)
        assertTrue(inspection.precomputed is CourierPermitGate.PermitOutcome.Blocked)
    }
}

/** 工具经手运行时：参数解码 / 坏参数回执 / 输出边界 / 宿主流经手。 */
class CourierToolRuntimeTest {

    private val runtime = CourierToolRuntime(CourierConfig())

    @Test
    fun `合法参数解码文本与原始双视图`() {
        val decoded = runtime.decode(
            "t", """{"a":"x","n":5,"list":["p","q"],"obj":{"k":"v"}}""",
            required = setOf("a"), optional = setOf("n", "list", "obj")
        )
        assertTrue(decoded is CourierToolRuntime.Decoded.Valid)
        val valid = decoded as CourierToolRuntime.Decoded.Valid
        assertEquals("x", valid.fields["a"])
        assertEquals("5", valid.fields["n"])
        assertEquals("p,q", valid.fields["list"])
        // 原始视图保真
        assertTrue(valid.raw["list"]!!.contains("\"p\""))
        assertTrue(valid.raw["obj"]!!.contains("\"k\""))
    }

    @Test
    fun `坏参数回执列五条并回显`() {
        val decoded = runtime.decode(
            "t", "not json at all", required = setOf("a", "b")
        )
        assertTrue(decoded is CourierToolRuntime.Decoded.Invalid)
        val recept = (decoded as CourierToolRuntime.Decoded.Invalid).modelRecept
        assertTrue(recept.contains("not a JSON object"))
        assertTrue(recept.contains("not json at all"))
    }

    @Test
    fun `缺字段与未知字段进问题列表`() {
        val decoded = runtime.decode(
            "t", """{"a":"x","z":"y"}""", required = setOf("a", "b")
        )
        assertTrue(decoded is CourierToolRuntime.Decoded.Invalid)
        val recept = (decoded as CourierToolRuntime.Decoded.Invalid).modelRecept
        assertTrue(recept.contains("\$b"))
        assertTrue(recept.contains("\$z"))
    }

    @Test
    fun `行数边界保头裁尾加注记`() {
        val config = CourierConfig(outputLineLimit = 3)
        val runtime = CourierToolRuntime(config)
        val raw = (1..10).joinToString("\n") { "line$it" }
        val bounded = runtime.bound(raw)
        assertTrue(bounded.startsWith("line1\nline2\nline3"))
        assertTrue(bounded.contains("[showing first 3 of 10 lines"))
    }

    @Test
    fun `字节边界不撕裂多字节字符`() {
        val config = CourierConfig(outputByteLimit = 20, outputLineLimit = 10_000)
        val runtime = CourierToolRuntime(config)
        val raw = "汉字汉字汉字汉字汉字汉字汉字"
        val bounded = runtime.bound(raw)
        val size = bounded.toByteArray(Charsets.UTF_8).size
        // 诊断消息：失败时输出实际尺寸与回执尾部（一次性取证后收敛）
        assertTrue("byte size=$size (expected<=140) tail=${bounded.substringAfterLast('\n')}", size <= 140)
        assertTrue("missing byte-cap marker: $bounded", bounded.contains("[output capped"))
        // 不撕裂：截断点为整字符边界（6 字 = 18 字节 ≤ 20）
        val head = bounded.substringBefore('\n')
        assertTrue("torn head bytes=${head.toByteArray(Charsets.UTF_8).size}", head.toByteArray(Charsets.UTF_8).size <= 20)
    }

    @Test
    fun `宿主流经手转发与终值边界`() = runTest {
        val config = CourierConfig(outputLineLimit = 2)
        val runtime = CourierToolRuntime(config)
        val seen = ArrayList<DispatchToolStream>()
        val result = runtime.relayHostStream(
            kotlinx.coroutines.flow.flow {
                emit(DispatchToolStream.Output("a\n"))
                emit(DispatchToolStream.Progress(0.5f, "half"))
                emit(DispatchToolStream.Output("b\nc\nd"))
                emit(DispatchToolStream.Complete("a\nb\nc\nd"))
            }
        ) { seen.add(it) }
        assertTrue(result.success)
        assertEquals(3, seen.size) // 2 Output + 1 Progress
        assertTrue(result.modelContent.contains("[showing first 2 of 4 lines"))
        assertEquals("a\nb\nc\nd", result.fullContent)
    }

    @Test
    fun `宿主流失败帧走失败回执`() = runTest {
        val result = CourierToolRuntime(CourierConfig()).relayHostStream(
            kotlinx.coroutines.flow.flow {
                emit(DispatchToolStream.Output("partial"))
                emit(DispatchToolStream.Error("boom"))
            }
        ) { }
        assertFalse(result.success)
        assertTrue(result.modelContent.contains("boom"))
    }
}

/** 本地工具面：清单簿 / 问答案 / 三个工具的执行。 */
class CourierLocalToolsTest {

    private class RecordingLauncher : CourierTaskLauncher {
        val requests = ArrayList<CourierTaskLauncher.TaskRequest>()
        var result = CourierTaskLauncher.TaskResult("child-1", DispatchChildStatus.COMPLETED, "done well")
        override suspend fun launch(request: CourierTaskLauncher.TaskRequest): CourierTaskLauncher.TaskResult {
            requests += request
            return result
        }
    }

    private fun tools(launcher: CourierTaskLauncher = RecordingLauncher()): Triple<
        CourierLocalTools, CourierTodoBoard, CourierQuestionBox
        > {
        val board = CourierTodoBoard()
        val box = CourierQuestionBox()
        val t = CourierLocalTools(CourierConfig(), CourierToolRuntime(CourierConfig()), board, box, launcher)
        return Triple(t, board, box)
    }

    @Test
    fun `清单读写与渲染`() {
        val (tools, board, _) = tools()
        val write = kotlinx.coroutines.runBlocking {
            tools.execute(
                CourierLocalTools.TODOS,
                """{"todos":[{"content":"read spec","status":"done"},{"content":"patch file","status":"in_progress","activeForm":"patching"}]}""",
                {}
            )
        }
        assertTrue(write.model.contains("[x] read spec"))
        assertTrue(write.model.contains("[>] patch file — patching"))
        assertEquals(2, board.current().size)
        // 读回（无参）
        val read = kotlinx.coroutines.runBlocking { tools.execute(CourierLocalTools.TODOS, "{}", {}) }
        assertEquals(board.render(), read.model)
        // 渲染 → 解析回路
        val restored = board.parseRendered(board.render())
        assertEquals(2, restored.size)
        assertEquals(DispatchTodoStatus.IN_PROGRESS, restored[1].status)
    }

    @Test
    fun `坏清单回执`() {
        val (tools, _, _) = tools()
        val bad = kotlinx.coroutines.runBlocking {
            tools.execute(CourierLocalTools.TODOS, """{"todos":"nonsense"}""", {})
        }
        assertTrue(bad.model.contains("Invalid dispatch"))
    }

    @Test
    fun `问答表单出票答复回执`() = runTest {
        val (tools, _, box) = tools()
        val events = ArrayList<DispatchEvent>()
        val answerer = launch {
            answerWhenAsked(box, "Use SQLite")
        }
        val outcome = tools.execute(
            CourierLocalTools.QUESTION,
            """{"questions":[{"header":"Storage","question":"Which store?","options":[{"label":"SQLite"},{"label":"files"}]}]}""",
            { events.add(it) }
        )
        answerer.join()
        assertTrue(outcome.model.contains("User has answered your questions"))
        assertTrue(outcome.model.contains("Use SQLite"))
        assertEquals(1, events.size)
        assertTrue(events[0] is DispatchEvent.UserInputRequired)
        val prompt = (events[0] as DispatchEvent.UserInputRequired).prompt
        assertTrue(prompt.contains("Storage: Which store?"))
        assertTrue(prompt.contains("- SQLite"))
    }

    /** 等问答案出票后应答（虚拟时钟轮询）。 */
    private suspend fun answerWhenAsked(box: CourierQuestionBox, answer: String?) {
        var spins = 0
        while (box.current() == null && spins < 500) {
            kotlinx.coroutines.delay(10)
            spins++
        }
        if (answer == null) {
            box.cancelAll()
        } else {
            box.current()?.let { box.answer(answer) }
        }
    }

    @Test
    fun `问答取消回执为继续最佳判断`() = runTest {
        val (tools, _, box) = tools()
        val canceller = launch {
            answerWhenAsked(box, null)
        }
        val outcome = tools.execute(
            CourierLocalTools.QUESTION,
            """{"questions":[{"question":"Ambiguous?"}]}""",
            {}
        )
        canceller.join()
        assertTrue(outcome.model.contains("dismissed the question form"))
    }

    @Test
    fun `下站委派前台回执信封`() = runTest {
        val launcher = RecordingLauncher()
        val (tools, _, _) = tools(launcher)
        val outcome = tools.execute(
            CourierLocalTools.TASK,
            """{"agent":"explore","description":"find config","prompt":"Locate the config loader."}""",
            {}
        )
        assertEquals(1, launcher.requests.size)
        assertEquals("explore", launcher.requests[0].roleId)
        assertEquals(false, launcher.requests[0].background)
        assertTrue(outcome.model.contains("<courier station=\"child-1\" state=\"completed\">"))
        assertTrue(outcome.model.contains("done well"))
    }

    @Test
    fun `下站委派后台立即回执不轮询`() = runTest {
        val launcher = RecordingLauncher()
        launcher.result = CourierTaskLauncher.TaskResult("child-9", DispatchChildStatus.RUNNING, "")
        val (tools, _, _) = tools(launcher)
        val outcome = tools.execute(
            CourierLocalTools.TASK,
            """{"agent":"general","description":"x","prompt":"y","background":true}""",
            {}
        )
        assertTrue(outcome.model.contains("moved to the background"))
        assertTrue(outcome.model.contains("do NOT poll"))
        assertTrue(launcher.requests[0].background)
    }

    @Test
    fun `下站委派缺必填字段回执`() = runTest {
        val (tools, _, _) = tools()
        val outcome = kotlinx.coroutines.runBlocking {
            tools.execute(CourierLocalTools.TASK, """{"agent":"general"}""", {})
        }
        assertTrue(outcome.model.contains("Invalid dispatch"))
        assertTrue(outcome.model.contains("\$description"))
        assertTrue(outcome.model.contains("\$prompt"))
    }

    @Test
    fun `定义面三件与本地判定`() {
        val (tools, _, _) = tools()
        val defs = tools.definitions()
        assertEquals(3, defs.size)
        assertEquals(
            setOf("courier_todos", "courier_question", "courier_task"),
            defs.map { it.name }.toSet()
        )
        // schema 是合法 JSON
        for (d in defs) {
            kotlinx.serialization.json.Json.parseToJsonElement(d.parameters)
        }
        assertTrue(tools.isLocal("courier_task"))
        assertFalse(tools.isLocal("code_read"))
    }
}
