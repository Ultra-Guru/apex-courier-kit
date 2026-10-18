package com.apex.courierkit.dispatch

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重递阶梯：翻倍 / 封顶 / 抖动界 / retryAfter 采信 / 上限放弃 / 传输紧口。 */
class CourierBackoffTest {

    private fun config(
        base: Long = 2_000,
        cap: Long = 10_000,
        max: Int = 10,
        afterCap: Long = 900_000,
        transportMax: Int = 3
    ) = CourierConfig(
        retryBaseDelayMs = base,
        retryCapDelayMs = cap,
        retryMaxAttempts = max,
        retryAfterCapMs = afterCap,
        retryTransportMaxAttempts = transportMax
    )

    @Test
    fun `指数翻倍且封顶`() {
        val backoff = CourierBackoff(config(), Random(42))
        // 第 1 次失败 → base 起步；抖动上限 = base/4
        val s1 = backoff.next(1)
        assertTrue(s1.delayMs in 2_000..2_500)
        assertTrue(!s1.giveUp)
        val s2 = backoff.next(2)
        assertTrue(s2.delayMs in 4_000..5_000)
        val s3 = backoff.next(3)
        assertTrue(s3.delayMs in 8_000..10_000)
        val s4 = backoff.next(4)
        // 16000 → 封顶 10000
        assertEquals(10_000L, s4.delayMs)
    }

    @Test
    fun `retryAfter 采信且封顶`() {
        val backoff = CourierBackoff(config(), Random(7))
        assertEquals(60_000L, backoff.next(1, retryAfterMs = 60_000).delayMs)
        // 超过 15 分钟口子 → 钳到口子
        assertEquals(900_000L, backoff.next(1, retryAfterMs = 9_999_999).delayMs)
        // 非正建议忽略 → 走指数
        val s = backoff.next(1, retryAfterMs = 0)
        assertTrue(s.delayMs in 2_000..2_500)
    }

    @Test
    fun `上限耗尽放弃`() {
        val backoff = CourierBackoff(config(max = 2), Random(1))
        assertFalse(backoff.next(1).giveUp)
        assertFalse(backoff.next(2).giveUp)
        assertTrue(backoff.next(3).giveUp)
    }

    @Test
    fun `传输类失败上限收紧`() {
        val backoff = CourierBackoff(config(max = 10, transportMax = 1), Random(1))
        assertFalse(backoff.next(1, transportStyle = true).giveUp)
        assertTrue(backoff.next(2, transportStyle = true).giveUp)
        // 非传输路径仍宽
        assertFalse(backoff.next(2, transportStyle = false).giveUp)
    }
}

/** 复合指令拆解：分隔符切分 / 引号感知 / 环境前缀剥离 / 资源串与记忆模式。 */
class CourierCommandSplitterTest {

    @Test
    fun `复合命令按分隔符切段`() {
        val subs = CourierCommandSplitter.split("cd /a && git push; ls | head -5 || echo done")
        assertEquals(5, subs.size)
        assertEquals(listOf("cd", "git", "ls", "head", "echo"), subs.map { it.head })
        assertEquals("git push", subs[1].resource)
        assertEquals("git push *", subs[1].savePattern)
    }

    @Test
    fun `引号内的分隔符不切段`() {
        val subs = CourierCommandSplitter.split("echo \"a && b\" 'c; d'")
        assertEquals(1, subs.size)
        assertEquals("echo", subs[0].head)
    }

    @Test
    fun `环境前缀剥离后取首词`() {
        val subs = CourierCommandSplitter.split("FOO=1 BAR=2 make build")
        assertEquals(1, subs.size)
        assertEquals("make", subs[0].head)
        assertEquals("build", subs[0].argsTail)
        assertEquals("make build", subs[0].resource)
    }

    @Test
    fun `纯环境赋值段回退原文`() {
        val subs = CourierCommandSplitter.split("FOO=1 && BAR=2")
        // 两个赋值段：head 回退段原文（无命令词）
        assertEquals(2, subs.size)
    }

    @Test
    fun `无参命令资源即首词`() {
        val subs = CourierCommandSplitter.split("ls")
        assertEquals("ls", subs[0].resource)
        assertEquals("ls", subs[0].savePattern)
    }

    @Test
    fun `路径引用抽取去重`() {
        val paths = CourierCommandSplitter.referencedPaths("cat /a/b.txt /a/b.txt ~/note.md; grep x /c/d.rs")
        assertEquals(listOf("/a/b.txt", "~/note.md", "/c/d.rs"), paths)
    }

    @Test
    fun `空与空白命令安全折叠`() {
        assertTrue(CourierCommandSplitter.split("").isEmpty())
        assertTrue(CourierCommandSplitter.split("   ").isEmpty())
        assertTrue(CourierCommandSplitter.split(";; ; &&").isEmpty())
    }
}

/** 通行则例：通配 / 后例覆盖 / 聚合 / 整枝拒付 / 尾通配可选后缀。 */
class CourierRuleBookTest {

    private fun rule(
        action: String,
        resource: String,
        effect: DispatchRuleEffect
    ) = DispatchPermissionRule(action, resource, effect)

    @Test
    fun `星号与问号通配`() {
        assertTrue(CourierRuleBook.matches("*", "anything"))
        assertTrue(CourierRuleBook.matches("*.env", "prod.env"))
        assertTrue(CourierRuleBook.matches("git ?", "git a"))
        assertFalse(CourierRuleBook.matches("git ?", "git ab"))
        assertTrue(CourierRuleBook.matches("git push", "git push"))
        assertFalse(CourierRuleBook.matches("git push", "git push -f"))
    }

    @Test
    fun `尾部星后缀可选`() {
        assertTrue(CourierRuleBook.matches("git push *", "git push"))
        assertTrue(CourierRuleBook.matches("git push *", "git push origin main"))
        assertFalse(CourierRuleBook.matches("git push *", "git fetch"))
    }

    @Test
    fun `反斜杠折叠与空白折叠`() {
        assertTrue(CourierRuleBook.matches("src\\main", "src/main"))
        assertTrue(CourierRuleBook.matches("a  b", "a b"))
    }

    @Test
    fun `后例覆盖findLast`() {
        val rules = listOf(
            rule("edit", "*", DispatchRuleEffect.ALLOW),
            rule("edit", "*.env", DispatchRuleEffect.DENY)
        )
        assertEquals(DispatchRuleEffect.DENY, CourierRuleBook.evaluate(rules, "edit", "prod.env"))
        assertEquals(DispatchRuleEffect.ALLOW, CourierRuleBook.evaluate(rules, "edit", "main.kt"))
    }

    @Test
    fun `未命中则例缺省勘验`() {
        assertEquals(
            DispatchRuleEffect.ASK,
            CourierRuleBook.evaluate(emptyList(), "edit", "x")
        )
    }

    @Test
    fun `聚合deny优先于ask优先于allow`() {
        assertEquals(
            DispatchRuleEffect.DENY,
            CourierRuleBook.aggregate(
                listOf(DispatchRuleEffect.ALLOW, DispatchRuleEffect.ASK, DispatchRuleEffect.DENY)
            )
        )
        assertEquals(
            DispatchRuleEffect.ASK,
            CourierRuleBook.aggregate(listOf(DispatchRuleEffect.ALLOW, DispatchRuleEffect.ASK))
        )
        assertEquals(
            DispatchRuleEffect.ALLOW,
            CourierRuleBook.aggregate(listOf(DispatchRuleEffect.ALLOW))
        )
    }

    @Test
    fun `堆叠顺序为画像会话记忆`() {
        val stacked = CourierRuleBook.stack(
            roleRules = listOf(rule("read", "*", DispatchRuleEffect.ALLOW)),
            sessionRules = listOf(rule("read", "*.key", DispatchRuleEffect.ASK)),
            savedRules = listOf(rule("read", "prod.key", DispatchRuleEffect.DENY))
        )
        assertEquals(3, stacked.size)
        // 记忆压轴 → 最后一条生效
        assertEquals(
            DispatchRuleEffect.DENY,
            CourierRuleBook.evaluate(stacked, "read", "prod.key")
        )
    }

    @Test
    fun `整枝拒付判定`() {
        val denied = listOf(rule("edit", "*", DispatchRuleEffect.DENY))
        assertTrue(CourierRuleBook.whollyDenies(denied, "edit"))
        val askTail = listOf(
            rule("edit", "*", DispatchRuleEffect.DENY),
            rule("edit", "/safe/**", DispatchRuleEffect.ALLOW)
        )
        // 后例覆盖放行 → 非整枝拒付
        assertFalse(CourierRuleBook.whollyDenies(askTail, "edit"))
        assertFalse(CourierRuleBook.whollyDenies(emptyList(), "edit"))
    }
}

/** 配置钳位：脏值折叠安全界（零 require —— 坏配置绝不击穿构造）。 */
class CourierConfigTest {

    @Test
    fun `全部脏值被钳回安全界`() {
        val bad = CourierConfig(
            maxTrips = 0,
            tripWarnThreshold = 999,
            keepTokens = 1,
            compactBufferFraction = 5.0,
            retryMaxAttempts = -3,
            retryBaseDelayMs = 0,
            retryCapDelayMs = 1,
            subagentDepthLimit = -2,
            inboxQueueBatch = 0,
            loopContextTag = "  ",
            outputLineLimit = -1
        ).sanitized()
        assertEquals(1, bad.maxTrips)
        assertEquals(1, bad.tripWarnThreshold)
        assertEquals(2_000, bad.keepTokens)
        assertEquals(0.90, bad.compactBufferFraction, 1e-9)
        assertEquals(0, bad.retryMaxAttempts)
        assertTrue(bad.retryBaseDelayMs >= 100)
        assertTrue(bad.retryCapDelayMs >= bad.retryBaseDelayMs)
        assertEquals(0, bad.subagentDepthLimit)
        assertEquals(1, bad.inboxQueueBatch)
        assertEquals("courier_loop", bad.loopContextTag)
        assertEquals(1, bad.outputLineLimit)
    }

    @Test
    fun `档房触发线窗口减缓冲`() {
        val config = CourierConfig(
            compactBufferFraction = 0.10,
            compactBufferFloorTokens = 16_384
        ).sanitized()
        // 小窗走地板缓冲
        assertEquals(100_000 - 16_384, config.compactCeiling(100_000)!!.toInt())
        // 大窗走比例缓冲
        assertEquals(1_000_000 - 100_000, config.compactCeiling(1_000_000)!!.toInt())
        // 未知窗口（0/负）→ null（永不自动触发）
        assertNull(config.compactCeiling(0))
        assertNull(config.compactCeiling(-5))
    }

    @Test
    fun `原始配置对象直接可用`() {
        // 缺省值即安全值——sanitized 幂等
        val def = CourierConfig()
        assertEquals(def, def.sanitized())
    }
}
