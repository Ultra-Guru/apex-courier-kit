package com.apex.courierkit.dispatch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎门面集成测：脚本化模型客户端 + 假工具执行器——覆盖金色路径：
 * 纯文本收束 / 工具调用链（needsContinuation）/ 勘合问答闭环 / 计划门 /
 * 下站前台委派 / 中止。
 */
class CourierEngineTest {

    // ── 测试替身 ──

    /** 脚本帧：一段流（chunks）/ 先出后断 / 一次失败。 */
    private sealed interface Script {
        data class Stream(val chunks: List<DispatchStreamChunk>) : Script

        /** 先流出 [chunks] 再抛 [error]（半截流路径）。 */
        data class StreamThenFail(
            val chunks: List<DispatchStreamChunk>,
            val error: Throwable
        ) : Script

        data class Fail(val error: Throwable) : Script
    }

    private class ScriptedClient : CourierModelClient {
        val scripts = ArrayList<Script>()
        val requests = ArrayList<List<DispatchMessage>>()

        private fun nextScript(): Script =
            scripts.removeFirstOrNull() ?: Script.Stream(
                listOf(
                    DispatchStreamChunk(content = "(no script)", isFinish = true, finishReason = "stop")
                )
            )

        override fun chatStream(
            contextTag: String,
            messages: List<DispatchMessage>,
            tools: List<DispatchToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: DispatchToolChoice?
        ): Flow<DispatchStreamChunk> {
            requests += messages
            val script = nextScript()
            return when (script) {
                is Script.Fail -> flow { throw script.error }
                is Script.StreamThenFail -> flow {
                    for (chunk in script.chunks) emit(chunk)
                    throw script.error
                }

                is Script.Stream -> flow {
                    for (chunk in script.chunks) emit(chunk)
                }
            }
        }

        override suspend fun chat(
            contextTag: String,
            messages: List<DispatchMessage>,
            temperature: Float,
            maxTokens: Int
        ): DispatchModelResponse = DispatchModelResponse(
            content = "# Mission\n# Constraints\n# Decisions\n# Progress\n# Next Step\n# Key Files\n# Notes\nfine"
        )
    }

    private class ScriptedRunner : CourierToolRunner {
        val executed = ArrayList<Pair<String, String>>()
        var output = "TOOL OUTPUT"
        var failMessage: String? = null

        /** 工具耗时（中止测试的慢工具——虚拟时钟）。 */
        var delayMs: Long = 0

        override fun executeStream(toolId: String, arguments: String): Flow<DispatchToolStream> =
            flow {
                executed += toolId to arguments
                if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                emit(DispatchToolStream.Output("partial "))
                if (failMessage != null) {
                    emit(DispatchToolStream.Error(failMessage!!))
                } else {
                    emit(DispatchToolStream.Complete(output))
                }
            }
    }

    private fun chunkText(text: String, finish: String? = null) = DispatchStreamChunk(
        content = text,
        isFinish = finish != null,
        finishReason = finish
    )

    private fun chunkCall(id: String, name: String, args: String, index: Int = 0) =
        DispatchStreamChunk(
            toolCalls = listOf(DispatchToolCall(id, name, args, index)),
            isFinish = false
        )

    private fun engine(
        client: ScriptedClient,
        runner: ScriptedRunner,
        snapshot: CourierPermissionSnapshotSource = CourierPermissionSnapshotSource.NONE
    ) = CourierEngine(
        modelClient = client,
        hostToolSource = CourierToolSource {
            listOf(
                DispatchToolDefinition("code_read", "read a file", "{}"),
                DispatchToolDefinition("code_edit", "edit a file", "{}")
            )
        },
        hostToolRunner = runner,
        logger = CourierLogger.Silent,
        modelInfoSource = CourierModelInfoSource { CourierModelInfo("test-model", 100_000) },
        permissionSnapshotSource = snapshot,
        engineConfig = CourierConfig(maxTrips = 8)
    )

    // ── 用例 ──

    @Test
    fun `纯文本收束`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(chunkText("hello there"), chunkText(" done", finish = "stop"))
        )
        val engine = engine(client, ScriptedRunner())
        val events = withTimeout(10_000) { engine.execute("hi").toList() }
        // 事件序列：IterationStart / ThinkingStart / ResponseChunk×2 / ThinkingComplete / ResponseComplete / Complete
        assertTrue(events[0] is DispatchEvent.IterationStart)
        assertTrue(events.any { it is DispatchEvent.ResponseChunk && it.text == "hello there" })
        assertTrue(events.any { it is DispatchEvent.ResponseComplete && it.fullText == "hello there done" })
        val complete = events.last() as DispatchEvent.Complete
        assertEquals(0, complete.totalToolCalls)
        assertEquals(1, complete.totalIterations)
        assertEquals(2, engine.historyCount()) // User + Assistant
    }

    @Test
    fun `工具调用链继续下一趟`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(
                chunkText("let me read"),
                chunkCall("c1", "code_read", """{"path":"a.kt"}"""),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        client.scripts += Script.Stream(listOf(chunkText("all done", finish = "stop")))
        val runner = ScriptedRunner()
        val engine = engine(client, runner)
        val events = withTimeout(10_000) { engine.execute("check the file").toList() }
        assertEquals(listOf("code_read" to """{"path":"a.kt"}"""), runner.executed)
        val toolComplete = events.filterIsInstance<DispatchEvent.ToolCallComplete>().single()
        assertTrue(toolComplete.success)
        assertTrue(toolComplete.output.contains("TOOL OUTPUT"))
        // 第二趟请求历史包含工具回执
        val secondRequest = client.requests[1]
        assertTrue(
            secondRequest.any { it is DispatchMessage.ToolResult && it.content.contains("TOOL OUTPUT") }
        )
        val complete = events.last() as DispatchEvent.Complete
        assertEquals(1, complete.totalToolCalls)
        assertEquals(2, complete.totalIterations)
        // 会话：User + Assistant(调用) + ToolResult + Assistant(结论) = 4
        assertEquals(4, engine.historyCount())
    }

    @Test
    fun `勘合问答闭环放行`() = runTest {
        val client = ScriptedClient()
        // 第一请求：模型只读一个 .env 文件（触发画像 ask 规则）
        client.scripts += Script.Stream(
            listOf(
                chunkCall("c1", "code_read", """{"path":"prod.env"}"""),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        client.scripts += Script.Stream(listOf(chunkText("got it", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        val collected = ArrayList<DispatchEvent>()
        val job = launch {
            engine.execute("read the env").take(30).toList(collected)
        }
        // 等待勘合问票（引擎事件泵周期送达）
        withTimeout(10_000) {
            while (collected.none { it is DispatchEvent.UserInputRequired }) {
                kotlinx.coroutines.delay(50)
            }
        }
        engine.submitUserInput("yes")
        withTimeout(10_000) { job.join() }
        val asked = collected.filterIsInstance<DispatchEvent.UserInputRequired>().single()
        assertTrue(asked.prompt.contains("prod.env"))
        // 放行后工具执行 + 收束
        assertTrue(collected.any { it is DispatchEvent.ToolCallComplete && it.success })
        assertTrue(collected.last() is DispatchEvent.Complete)
    }

    @Test
    fun `勘合拒付走缺陷隧道中断`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(
                chunkCall("c1", "code_read", """{"path":"prod.env"}"""),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        val engine = engine(client, ScriptedRunner())
        val collected = ArrayList<DispatchEvent>()
        val job = launch { engine.execute("read env").take(30).toList(collected) }
        withTimeout(10_000) {
            while (collected.none { it is DispatchEvent.UserInputRequired }) {
                kotlinx.coroutines.delay(50)
            }
        }
        engine.submitUserInput("no")
        withTimeout(10_000) { job.join() }
        // 缺陷隧道：趟次中断 → Aborted（拒付非工具失败）
        assertTrue(collected.any { it is DispatchEvent.ToolCallComplete && !it.success })
        assertTrue(collected.any { it is DispatchEvent.Aborted })
    }

    @Test
    fun `PLAN 模式计划门确认后执行`() = runTest {
        val client = ScriptedClient()
        // 第一趟：规划文本
        client.scripts += Script.Stream(
            listOf(
                chunkText(
                    "Looking around…\nGOAL: patch the bug\nSTEPS:\n1. read LoginVM\n2. fix null check\nRISKS: low",
                    finish = "stop"
                )
            )
        )
        // 第二趟：批准后执行结论
        client.scripts += Script.Stream(listOf(chunkText("plan executed", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        engine.updateMode(DispatchMode.PLAN)
        val collected = ArrayList<DispatchEvent>()
        val job = launch { engine.execute("plan the fix").take(30).toList(collected) }
        withTimeout(10_000) {
            while (collected.none { it is DispatchEvent.PlanAwaitingConfirmation }) {
                kotlinx.coroutines.delay(50)
            }
        }
        assertTrue(engine.submitPlanConfirmation(true))
        withTimeout(10_000) { job.join() }
        val plan = collected.filterIsInstance<DispatchEvent.PlanGenerated>().single().plan
        assertEquals("patch the bug", plan.goal)
        assertEquals(2, plan.steps.size)
        assertTrue(collected.any { it is DispatchEvent.PlanConfirmed })
        assertTrue(collected.last() is DispatchEvent.Complete)
    }

    @Test
    fun `下站前台委派信封回执`() = runTest {
        val client = ScriptedClient()
        // 母站第一趟：委派前台子驿
        client.scripts += Script.Stream(
            listOf(
                chunkCall(
                    "c1", "courier_task",
                    """{"agent":"general","description":"find config","prompt":"Locate the config loader."}"""
                ),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        // 子驿一趟：纯文本结论
        client.scripts += Script.Stream(listOf(chunkText("config lives in src/Config.kt", finish = "stop")))
        // 母站第二趟：总结
        client.scripts += Script.Stream(listOf(chunkText("found it", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        val events = withTimeout(10_000) { engine.execute("where is the config").toList() }
        val recept = events.filterIsInstance<DispatchEvent.ToolCallComplete>().single()
        assertTrue(recept.success)
        assertTrue(recept.output.contains("<courier station="))
        assertTrue(recept.output.contains("src/Config.kt"))
        // 子驿只跑一趟（纯文本收束）
        assertEquals(3, client.requests.size)
    }

    @Test
    fun `半截流续令继续新趟`() = runTest {
        val client = ScriptedClient()
        // 第一趟：输出到一半流断（IOException → 瞬时）
        client.scripts += Script.StreamThenFail(
            chunks = listOf(chunkText("partial answer be")),
            error = java.io.IOException("connection reset")
        )
        // 续趟：接续完成
        client.scripts += Script.Stream(listOf(chunkText("fore continuing", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        val events = withTimeout(10_000) { engine.execute("tell me").toList() }
        // 续令事件 + 两段 ResponseComplete
        assertTrue(events.any { it is DispatchEvent.LlmRetryScheduled })
        assertEquals(2, events.filterIsInstance<DispatchEvent.ResponseComplete>().size)
        assertTrue(events.last() is DispatchEvent.Complete)
        // 合成续令进历史
        assertEquals(4, engine.historyCount()) // User + partial Assistant + synthetic continue + final Assistant
    }

    @Test
    fun `请求级失败终止`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Fail(RuntimeException("401 unauthorized"))
        val engine = engine(client, ScriptedRunner())
        val events = withTimeout(10_000) { engine.execute("hello").toList() }
        val error = events.filterIsInstance<DispatchEvent.Error>().single()
        assertFalse(error.recoverable)
    }

    @Test
    fun `中止信号中断排水`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(chunkCall("c1", "code_read", """{"path":"a.kt"}"""))
        )
        // 第二趟：模型脚本存在但永远走不到（工具慢 + 中止先落地）
        client.scripts += Script.Stream(listOf(chunkText("late", finish = "stop")))
        val runner = ScriptedRunner()
        runner.delayMs = 5_000
        val engine = engine(client, runner)
        val collected = ArrayList<DispatchEvent>()
        val job = launch { engine.execute("work").take(30).toList(collected) }
        // 等工具开始（慢工具执行中）后升中止旗
        withTimeout(10_000) {
            while (collected.none { it is DispatchEvent.ToolCallStart }) {
                kotlinx.coroutines.delay(50)
            }
        }
        engine.abort()
        withTimeout(10_000) { job.join() }
        // 工具完成后趟次边界撞上中止旗 → Aborted
        assertTrue(collected.any { it is DispatchEvent.Aborted })
    }

    @Test
    fun `并行工具调用双纤程`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(
                chunkCall("c1", "code_read", """{"path":"a.kt"}""", index = 0),
                chunkCall("c2", "code_read", """{"path":"b.kt"}""", index = 1),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        client.scripts += Script.Stream(listOf(chunkText("both read", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        val events = withTimeout(10_000) { engine.execute("read both").toList() }
        assertEquals(2, events.filterIsInstance<DispatchEvent.ToolCallComplete>().size)
        val complete = events.last() as DispatchEvent.Complete
        assertEquals(2, complete.totalToolCalls)
    }

    @Test
    fun `清单随趟注入与持久化恢复`() = runTest {
        val client = ScriptedClient()
        client.scripts += Script.Stream(
            listOf(
                chunkCall(
                    "c1", "courier_todos",
                    """{"todos":[{"content":"step one","status":"done"},{"content":"step two","status":"in_progress"}]}"""
                ),
                DispatchStreamChunk(isFinish = true, finishReason = "tool_calls")
            )
        )
        client.scripts += Script.Stream(listOf(chunkText("working", finish = "stop")))
        val engine = engine(client, ScriptedRunner())
        withTimeout(10_000) { engine.execute("do the thing").toList() }
        // 第二趟请求包含清单注入段
        val secondRequest = client.requests[1]
        val checklistMsg = secondRequest.filterIsInstance<DispatchMessage.System>()
            .lastOrNull { it.content.contains("Trip checklist") }
        assertTrue(checklistMsg != null)
        assertTrue(checklistMsg!!.content.contains("[>] step two"))
    }
}
