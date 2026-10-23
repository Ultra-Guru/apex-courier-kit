package com.apex.courierkit.dispatch

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CourierTitler 测试（题匾——README 承诺「首次投递后一次性小模型命名（≤50 字符、
 * 同语言、CAS 落位）」，此前 87 项测试中唯一零覆盖的 README 承诺件）：
 *
 * - 小输出请求参数锁（temperature 0.3 / maxTokens 128 / contextTag courier_title /
 *   系统人设 + 用户摘录双消息）；
 * - 一次性触发（第二次挂起调用直接 null、不再发请求）；reset 再战；
 * - 成功路径：trim → 去首尾引号 → take(titleMaxChars) → 空白折叠 null；
 * - 失败折叠 null 且不重试（fire-and-forget 语义）。
 */
class CourierTitlerTest {

    private class Recorded(
        val contextTag: String,
        val messages: List<DispatchMessage>,
        val temperature: Float,
        val maxTokens: Int
    )

    private class FakeClient(
        var content: String? = null,
        var error: Throwable? = null
    ) : CourierModelClient {
        val records = ArrayList<Recorded>()
        override fun chatStream(
            contextTag: String,
            messages: List<DispatchMessage>,
            tools: List<DispatchToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: DispatchToolChoice?
        ): Flow<DispatchStreamChunk> = flow { throw IllegalStateException("titler uses chat only") }

        override suspend fun chat(
            contextTag: String,
            messages: List<DispatchMessage>,
            temperature: Float,
            maxTokens: Int
        ): DispatchModelResponse {
            records += Recorded(contextTag, messages, temperature, maxTokens)
            error?.let { throw it }
            return DispatchModelResponse(content = content)
        }
    }

    private val logger = CourierLogger.Silent

    @Test
    fun `成功路径去引号截断且请求参数锁死`() = runTest {
        val long60 = "题".repeat(60)
        val client = FakeClient(content = "  \"$long60\"  ")
        val titler = CourierTitler(CourierConfig(), client, logger)
        val title = titler.generate("EXCERPT-MARK-7734")
        // trim → 去首尾引号 → take(50)
        assertEquals(50, title!!.length)
        assertEquals("题".repeat(50), title)
        // 小输出请求参数：一次性小模型调用的契约
        assertEquals(1, client.records.size)
        val r = client.records[0]
        assertEquals("courier_title", r.contextTag)
        assertEquals(0.3f, r.temperature, 1e-6f)
        assertEquals(128, r.maxTokens)
        assertEquals(2, r.messages.size)
        assertTrue("首消息应为系统人设", r.messages[0] is DispatchMessage.System)
        val user = r.messages[1] as? DispatchMessage.User
        assertTrue("次消息应为用户摘录且包含原文", user != null && user.content.contains("EXCERPT-MARK-7734"))
        assertTrue(titler.hasFired())
    }

    @Test
    fun `一次性触发不重复`() = runTest {
        val client = FakeClient(content = "首次标题")
        val titler = CourierTitler(CourierConfig(), client, logger)
        assertEquals("首次标题", titler.generate("e1"))
        // 第二次：即使换了摘录也不再请求（CAS 已落位）
        assertNull(titler.generate("e2"))
        assertEquals(1, client.records.size)
        assertTrue(titler.hasFired())
    }

    @Test
    fun `失败折叠 null 且不重试`() = runTest {
        val client = FakeClient(error = RuntimeException("boom"))
        val titler = CourierTitler(CourierConfig(), client, logger)
        assertNull(titler.generate("e1"))
        assertEquals(1, client.records.size)
        assertNull(titler.generate("e2"))
        assertEquals("失败后仍不重试", 1, client.records.size)
        assertTrue(titler.hasFired())
    }

    @Test
    fun `空响应与纯空白折叠 null`() = runTest {
        val client = FakeClient(content = null)
        val titler = CourierTitler(CourierConfig(), client, logger)
        assertNull(titler.generate("e1"))
        // 纯空白/引号空白同样折叠
        titler.reset()
        assertFalse(titler.hasFired())
        client.content = "   \"   \"   "
        assertNull(titler.generate("e2"))
        assertEquals(2, client.records.size)
    }

    @Test
    fun `重置后再战`() = runTest {
        val client = FakeClient(content = null)
        val titler = CourierTitler(CourierConfig(), client, logger)
        assertNull(titler.generate("e1"))
        titler.reset()
        assertFalse(titler.hasFired())
        client.content = "重置后的新标题"
        assertEquals("重置后的新标题", titler.generate("e3"))
        assertEquals(2, client.records.size)
    }
}
