package com.apex.courierkit.dispatch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收发匣：steer 批 / queue 空闲投递 / 控制件置顶与边界 / 幂等 / 改投 / 取消。 */
class CourierInboxTest {

    private fun user(id: String, text: String = id) =
        CourierInbox.Item.UserItem(id, text)

    @Test
    fun `steer 批先行整批投递`() {
        val inbox = CourierInbox()
        inbox.admit(user("a"), CourierInbox.Delivery.STEER)
        inbox.admit(user("b"), CourierInbox.Delivery.STEER)
        inbox.admit(user("q"), CourierInbox.Delivery.QUEUE)
        val promotion = inbox.nextPromotable()!!
        assertEquals(listOf("a", "b"), promotion.items.map { (it as CourierInbox.Item.UserItem).text })
        assertTrue(promotion.resetTripCounter)
        assertNull(promotion.control)
        // 空闲边界投 queue
        val idle = inbox.nextPromotable()!!
        assertEquals(listOf("q"), idle.items.map { (it as CourierInbox.Item.UserItem).text })
        assertNull(inbox.nextPromotable())
    }

    @Test
    fun `steer 不跨越控制件`() {
        val inbox = CourierInbox()
        inbox.admit(user("a"), CourierInbox.Delivery.STEER)
        // 控制件落匣即置顶（后例覆盖式投递：先压缩，steer 原文保在 checkpoint 后）
        inbox.admit(CourierInbox.Item.CompactNow(id = "c1"))
        inbox.admit(user("b"), CourierInbox.Delivery.STEER)
        // 首次投递：控制件自成边界（置顶先行）
        val control = inbox.nextPromotable()!!
        assertNotNull(control.control)
        assertFalse(control.resetTripCounter)
        // 控制件之后：连续 steer 批整批投递
        val promotion = inbox.nextPromotable()!!
        assertEquals(listOf("a", "b"), promotion.items.map { (it as CourierInbox.Item.UserItem).text })
        assertTrue(promotion.resetTripCounter)
        assertNull(inbox.nextPromotable())
    }

    @Test
    fun `手动压缩控制件置顶`() {
        val inbox = CourierInbox()
        inbox.admit(user("early-1"), CourierInbox.Delivery.STEER)
        inbox.admit(user("early-2"), CourierInbox.Delivery.STEER)
        // 运行中途用户手动压缩 → 置顶（先压缩，steer 原文保在 checkpoint 后）
        inbox.admit(CourierInbox.Item.CompactNow(id = "c1", reason = "urgent"))
        val first = inbox.nextPromotable()!!
        assertEquals("urgent", first.control!!.reason)
        assertTrue(first.items.isEmpty())
    }

    @Test
    fun `落匣幂等按 id 去重`() {
        val inbox = CourierInbox()
        assertTrue(inbox.admit(user("x")))
        assertFalse(inbox.admit(user("x")))
        assertEquals(1, inbox.size())
    }

    @Test
    fun `改投与取消`() {
        val inbox = CourierInbox()
        inbox.admit(user("q"), CourierInbox.Delivery.QUEUE)
        inbox.admit(user("s"), CourierInbox.Delivery.STEER)
        // queue → steer 改投后成为批首
        assertTrue(inbox.steer("q"))
        val promotion = inbox.nextPromotable()!!
        assertEquals(listOf("q", "s"), promotion.items.map { (it as CourierInbox.Item.UserItem).text })
        // 取消
        inbox.admit(user("z"))
        assertTrue(inbox.cancel("z"))
        assertNull(inbox.nextPromotable())
        assertFalse(inbox.cancel("z"))
        assertFalse(inbox.steer("missing"))
    }

    @Test
    fun `合成件与用户件同批`() {
        val inbox = CourierInbox()
        inbox.admit(CourierInbox.Item.SyntheticItem("bg1", "courier finished"))
        val promotion = inbox.nextPromotable()!!
        assertEquals(1, promotion.items.size)
        assertTrue(promotion.items[0] is CourierInbox.Item.SyntheticItem)
        assertTrue(promotion.resetTripCounter)
    }

    @Test
    fun `pendingSteers 判据`() {
        val inbox = CourierInbox()
        assertEquals(0, inbox.pendingSteers())
        inbox.admit(user("a"), CourierInbox.Delivery.STEER)
        inbox.admit(user("q"), CourierInbox.Delivery.QUEUE)
        inbox.admit(CourierInbox.Item.CompactNow("c"))
        assertEquals(1, inbox.pendingSteers())
        inbox.clear()
        assertEquals(0, inbox.pendingSteers())
    }
}

/** 在驿卷宗：落账 / 模型视图 / checkpoint / 估算 / 近期段。 */
class CourierSessionTest {

    private class ListMemory : CourierMemory {
        val stored = ArrayList<DispatchMessage>()
        private val bound = ArrayList<String>()
        override fun load(): List<DispatchMessage> = stored.toList()
        override fun append(message: DispatchMessage) {
            stored += message
        }

        override fun save(messages: List<DispatchMessage>) {
            stored.clear()
            stored += messages
        }

        override fun clear() = stored.clear()
        override fun count(): Int = stored.size
        override fun bindWorkspace(workspaceId: String) {
            bound += workspaceId
        }
    }

    private val config = CourierConfig(keepTokens = 50)
    private fun session(memory: CourierMemory = ListMemory()) =
        CourierSession(memory, CourierHeuristicEstimator, config)

    @Test
    fun `落账并持久化`() {
        val memory = ListMemory()
        val s = session(memory)
        s.appendUser(CourierInbox.Item.UserItem("u1", "hello"))
        s.appendAssistant("thinking", emptyList(), "chain")
        s.appendToolResult("c1", "result body")
        assertEquals(3, s.messageCount())
        assertEquals(3, memory.stored.size)
        // 模型视图：系统段 + 历史
        val view = s.toModelMessages("PERSONA", "BASELINE", null, emptyList())
        assertEquals(4, view.size)
        assertEquals("PERSONA\n\nBASELINE", (view[0] as DispatchMessage.System).content)
        assertEquals("hello", (view[1] as DispatchMessage.User).content)
    }

    @Test
    fun `附加注入段顺序为历史后追加`() {
        val s = session()
        s.appendUser(CourierInbox.Item.UserItem("u1", "hi"))
        val view = s.toModelMessages(
            persona = "P",
            bulletinBaseline = null,
            checklist = "## Current trip checklist\n\n- [ ] x",
            notices = listOf("Standing instructions have changed — …")
        )
        // System(P) + User(hi) + System(notice) + System(checklist)
        assertEquals(4, view.size)
        val notice = view[2] as DispatchMessage.System
        assertTrue(notice.content.startsWith("Standing instructions"))
        val checklist = view[3] as DispatchMessage.System
        assertTrue(checklist.content.contains("trip checklist"))
    }

    @Test
    fun `checkpoint 折叠并保留近期段`() {
        val memory = ListMemory()
        val s = session(memory)
        s.appendUser(CourierInbox.Item.UserItem("u1", "old task"))
        s.appendAssistant("old answer", emptyList(), "")
        s.appendUser(CourierInbox.Item.UserItem("u2", "recent task"))
        s.appendAssistant("recent answer", emptyList(), "")
        val recent = s.tailAfterLastCheckpoint().takeLast(2)
        s.applyCheckpoint(
            envelope = CourierPrompts.dossierEnvelope("dossier body", "recent log"),
            dossier = "dossier body",
            recent = recent
        )
        assertTrue(s.hasCheckpoint)
        assertEquals("dossier body", s.lastDossier)
        // 记忆通道 = 信封 + 近期段
        assertEquals(3, memory.stored.size)
        val envelopeMsg = memory.stored[0] as DispatchMessage.User
        assertTrue(envelopeMsg.content.contains("<station-dossier>"))
        assertEquals("recent task", (memory.stored[1] as DispatchMessage.User).content)
        // tailAfterLastCheckpoint 现在为近期段
        assertEquals(2, s.tailAfterLastCheckpoint().size)
    }

    @Test
    fun `装载恢复卷宗状态`() {
        val memory = ListMemory()
        val s1 = session(memory)
        s1.appendUser(CourierInbox.Item.UserItem("u1", "old"))
        s1.applyCheckpoint(
            envelope = CourierPrompts.dossierEnvelope("the dossier text", "r"),
            dossier = "the dossier text",
            recent = s1.tailAfterLastCheckpoint()
        )
        val s2 = session(memory)
        s2.load()
        assertTrue(s2.hasCheckpoint)
        assertEquals("the dossier text", s2.lastDossier)
    }

    @Test
    fun `token 估算含图片成本`() {
        val s = session()
        s.appendUser(
            CourierInbox.Item.UserItem(
                "u1", "hi",
                images = listOf(
                    DispatchImageContent("aaa"), DispatchImageContent("bbb")
                )
            )
        )
        val cost = s.estimateTokens()
        assertTrue(cost >= config.imageTokenCost * 2)
    }

    @Test
    fun `清空与工作区绑定`() {
        val memory = ListMemory()
        val s = session(memory)
        s.appendUser(CourierInbox.Item.UserItem("u1", "x"))
        s.bindWorkspace("ws-9")
        s.clear()
        assertEquals(0, s.messageCount())
        assertEquals(0, memory.count())
    }
}

/** 示廊：源注册 / 指纹增量 / 更正行一次性消费 / epoch 推进 / 日期源。 */
class CourierBulletinTest {

    private class FakeClock(var now: Long = 1_700_000_000_000L) : CourierClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun `首次刷新进基线无更正行`() {
        val bulletin = CourierBulletin(FakeClock())
        bulletin.register("env", "Environment") { "workspace root: /w" }
        bulletin.refresh()
        val baseline = bulletin.baselineSection()!!
        assertTrue(baseline.contains("## Environment"))
        assertTrue(baseline.contains("/w"))
        assertNull(bulletin.consumePendingNotice())
    }

    @Test
    fun `变更产出一次性更正行并并入基线`() {
        var envText = "root: /one"
        val bulletin = CourierBulletin(FakeClock())
        bulletin.register("env", "Environment") { envText }
        bulletin.refresh()
        envText = "root: /two"
        bulletin.refresh()
        val notice = bulletin.consumePendingNotice()
        assertNotNull(notice)
        assertTrue(notice!!.contains("have changed"))
        assertTrue(notice.contains("/two"))
        // 只消费一次
        assertNull(bulletin.consumePendingNotice())
        // 基线已更新
        assertTrue(bulletin.baselineSection()!!.contains("/two"))
        // 幂等：无变更再刷新不产更正行
        bulletin.refresh()
        assertNull(bulletin.consumePendingNotice())
    }

    @Test
    fun `源读取失败折叠为空不阻断`() {
        val bulletin = CourierBulletin(FakeClock())
        bulletin.register("bad", "Bad") { throw IllegalStateException("boom") }
        bulletin.register("good", "Good") { "fine" }
        bulletin.refresh()
        assertTrue(bulletin.baselineSection()!!.contains("fine"))
        assertFalse(bulletin.baselineSection()!!.contains("Bad"))
    }

    @Test
    fun `空源与全空基线为 null`() {
        val bulletin = CourierBulletin(FakeClock())
        bulletin.register("empty", "Empty") { null }
        bulletin.refresh()
        assertNull(bulletin.baselineSection())
    }

    @Test
    fun `epoch 推进清更正行`() {
        var envText = "a"
        val bulletin = CourierBulletin(FakeClock())
        bulletin.register("env", "Environment") { envText }
        bulletin.refresh()
        envText = "b"
        bulletin.refresh()
        bulletin.advanceEpoch()
        assertNull(bulletin.consumePendingNotice())
        // epoch 后基线照常
        assertTrue(bulletin.baselineSection()!!.contains("b"))
    }

    @Test
    fun `日期源渲染今天`() {
        val bulletin = CourierBulletin(FakeClock(1_736_165_600_000L)) // 2024-12-06 UTC 附近
        val source = bulletin.dateSource()
        val text = source.read()!!
        assertTrue(text.startsWith("Today's date: 20"))
    }
}

/** 档房：触发线 / 切分边界 / 扁平转写 / 卷宗校验与纠偏 / 落账。 */
class CourierArchivistTest {

    private class ScriptedClient(
        var responses: MutableList<String?> = mutableListOf(),
        var failures: MutableList<Throwable?> = mutableListOf()
    ) : CourierModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<DispatchMessage>,
            tools: List<DispatchToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: DispatchToolChoice?
        ): Flow<DispatchStreamChunk> = flowOf()

        override suspend fun chat(
            contextTag: String,
            messages: List<DispatchMessage>,
            temperature: Float,
            maxTokens: Int
        ): DispatchModelResponse {
            failures.removeFirstOrNull()?.let { throw it }
            return DispatchModelResponse(content = responses.removeFirstOrNull())
        }
    }

    private val config = CourierConfig(keepTokens = 12)
    private fun estimator() = CourierHeuristicEstimator

    private fun archivist(client: ScriptedClient) = CourierArchivist(
        config, client, estimator(), CourierFailureClassifier.Default, CourierLogger.Silent
    )

    private fun filledSession(): Pair<CourierSession, MutableList<DispatchMessage>> {
        val log = ArrayList<DispatchMessage>()
        fun add(m: DispatchMessage) {
            log += m
        }
        // 4 个交换：old u/a + tool + recent u/a
        add(DispatchMessage.User("old question one"))
        add(DispatchMessage.Assistant("old answer one"))
        add(DispatchMessage.User("old question two"))
        add(DispatchMessage.Assistant("old answer two"))
        add(DispatchMessage.User("recent question"))
        add(DispatchMessage.Assistant("recent answer"))
        val session = CourierSession(null, estimator(), config)
        for (m in log) session.appendRawForTest(m)
        return session to log
    }

    private fun CourierSession.appendRawForTest(m: DispatchMessage) {
        // 测试直灌：经公共落账口（synthetic 包装）
        when (m) {
            is DispatchMessage.User -> appendUser(CourierInbox.Item.UserItem("id-${messageCount()}", m.content))
            is DispatchMessage.Assistant -> appendAssistant(m.content, m.toolCalls, m.reasoning)
            is DispatchMessage.ToolResult -> appendToolResult(m.toolCallId, m.content)
            is DispatchMessage.System -> {}
        }
    }

    @Test
    fun `触发线判定`() {
        val a = archivist(ScriptedClient())
        assertTrue(a.shouldCompact(99_999, 100_000, manual = false))
        assertFalse(a.shouldCompact(50_000, 100_000, manual = false))
        assertTrue(a.shouldCompact(1, 0, manual = true))
        assertFalse(a.shouldCompact(500_000, 0, manual = false)) // 未知窗口不自动
    }

    @Test
    fun `切分保留近期段且调用回执不拆对`() {
        fun archivistWith(keep: Int) = CourierArchivist(
            CourierConfig(keepTokens = keep),
            ScriptedClient(), estimator(), CourierFailureClassifier.Default, CourierLogger.Silent
        )
        val history = listOf(
            DispatchMessage.User("one two three four five"),
            DispatchMessage.Assistant(
                "a1",
                listOf(DispatchToolCall("c1", "code_read", "{}"))
            ),
            DispatchMessage.ToolResult("c1", "r1"),
            DispatchMessage.User("q2"),
            DispatchMessage.Assistant("a2")
        )
        // 大窗口：全量在 keep 内 → 无远期段
        val big = archivistWith(2_000).splitConversation(history)
        assertTrue(big.older.isEmpty)
        assertEquals(5, big.recent.size)
        // 小窗口：切分点回退到用户消息边界（q2 起），调用与回执同段不拆对
        val small = archivistWith(6).splitConversation(history)
        assertEquals(3, small.older.size)
        assertEquals(2, small.recent.size)
        val olderList = small.older.toList()
        assertTrue(olderList[0] is DispatchMessage.User)
        assertTrue(olderList[1] is DispatchMessage.Assistant)
        assertTrue(olderList[2] is DispatchMessage.ToolResult)
        assertTrue(small.recent[0] is DispatchMessage.User)
    }

    @Test
    fun `卷宗摘要纠偏一次后成功`() = runTest {
        val client = ScriptedClient()
        client.responses += "no headings here"
        client.responses += "# Mission\n# Constraints\n# Decisions\n# Progress\n# Next Step\n# Key Files\n# Notes\ndone"
        val (session, _) = filledSession()
        val result = archivist(client).compact(session, "auto", 1_000_000)
        assertTrue(result.performed)
        assertTrue(result.summary.contains("# Mission"))
        assertEquals(4, result.messagesRemoved)
    }

    @Test
    fun `卷宗缺标题两次失败返回未压缩`() = runTest {
        val client = ScriptedClient()
        client.responses += "still no headings"
        client.responses += "again no headings"
        val (session, _) = filledSession()
        val result = archivist(client).compact(session, "auto", 1_000_000)
        assertFalse(result.performed)
        assertTrue(result.failure!!.contains("nudge"))
    }

    @Test
    fun `溢出失败走收缩阶梯`() = runTest {
        val client = ScriptedClient()
        // 首请求溢出 → 第二请求（70% 转写）成功
        client.failures += RuntimeException("prompt is too long: context_length_exceeded")
        client.responses += "# Mission\n# Constraints\n# Decisions\n# Progress\n# Next Step\n# Key Files\n# Notes\nok"
        val (session, _) = filledSession()
        val result = archivist(client).compact(session, "auto", 1_000_000)
        assertTrue(result.performed)
    }

    @Test
    fun `历史过短不压缩`() = runTest {
        val session = CourierSession(null, estimator(), config)
        session.appendUser(CourierInbox.Item.UserItem("u1", "hi"))
        val result = archivist(ScriptedClient()).compact(session, "manual", 1_000_000)
        assertFalse(result.performed)
    }

    @Test
    fun `扁平转写限长与分组`() {
        val a = archivist(ScriptedClient())
        val flat = a.flatten(
            listOf(
                DispatchMessage.User("question"),
                DispatchMessage.Assistant(
                    "answer",
                    listOf(DispatchToolCall("c1", "code_read", "{\"path\":\"x\"}"))
                ),
                DispatchMessage.ToolResult("c1", "result".repeat(600))
            ),
            fraction = 1.0
        )
        assertTrue(flat.contains("[User]: question"))
        assertTrue(flat.contains("[Assistant dispatch]: code_read("))
        assertTrue(flat.contains("[Tool result]: "))
        assertTrue(flat.contains("…"))
    }
}

/** 计划门：解析 / 挂起确认 / 驳回反馈 / 超时。 */
class CourierPlanGateTest {

    private fun gate() = CourierPlanGate(CourierConfig(), object : CourierClock {
        override fun nowMillis(): Long = 0L
    })

    @Test
    fun `结构化计划解析`() {
        val gate = gate()
        val plan = gate.parsePlan(
            """
            Some intro text.
            GOAL: Fix the login crash
            STEPS:
            1. Reproduce with debug build（bash@gradlew）
            2. Patch the null check in LoginVM
            3. Add regression test
            RISKS: medium — touches auth flow
            """.trimIndent()
        )
        assertNotNull(plan)
        assertEquals("Fix the login crash", plan!!.goal)
        assertEquals(3, plan.steps.size)
        assertEquals("bash", plan.steps[0].toolName)
        assertEquals(DispatchRiskLevel.MEDIUM, plan.riskLevel)
    }

    @Test
    fun `无目标或无步骤返回 null`() {
        val gate = gate()
        assertNull(gate.parsePlan("no structure at all"))
        assertNull(gate.parsePlan("GOAL: x\nSTEPS:\n- \nRISKS: none"))
    }

    @Test
    fun `确认与驳回`() = runTest {
        val gate = gate()
        val plan = gate.parsePlan("GOAL: g\nSTEPS:\n1. do it")!!
        val confirmer = launch {
            gate.confirm(approved = true)
        }
        val outcome = gate.await(plan)
        confirmer.join()
        assertEquals(true, outcome)
        // 已无挂起 → confirm 返回 false
        assertFalse(gate.confirm(approved = false))
    }
}
