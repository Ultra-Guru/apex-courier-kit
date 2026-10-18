package com.apex.courierkit.dispatch

import kotlin.random.Random

/**
 * # Courier Backoff — 重递阶梯（抖动指数退避）
 *
 * 同趟物理尝试失败后的重递节奏：**base 起步、逐次翻倍、cap 封顶、四分
 * 之一抖动**；provider 给出 retryAfter 时直接采信（封顶 15 分钟口子）。
 * 超出上限 → 放弃（引擎侧终止或降级）。
 *
 * 与织造线的阶梯差异：信使线把「输出已开始的半截流」也纳入续跑语义
 * （见趟次机 Continue 出口）——本类只管**纯失败重试**的等待节奏。
 */
class CourierBackoff(
    private val config: CourierConfig,
    private val random: Random = Random.Default
) {

    /** 一次重递调度决策。 */
    data class Schedule(
        /** 等待毫秒（0 = 立即重递）。 */
        val delayMs: Long,
        /** 即将进行的重试序号（1-based）。 */
        val attempt: Int,
        /** true = 阶梯耗尽，放弃。 */
        val giveUp: Boolean
    )

    /**
     * 计算第 [attempt] 次失败后的下一步。
     *
     * @param attempt 已失败次数（1-based：第 1 次失败问「要不要第 1 次重试」）
     * @param retryAfterMs provider 建议等待（null = 无建议）
     * @param transportStyle 传输类超时失败（上限收紧为
     *   [CourierConfig.retryTransportMaxAttempts]）
     */
    fun next(
        attempt: Int,
        retryAfterMs: Long? = null,
        transportStyle: Boolean = false
    ): Schedule {
        val ceiling = if (transportStyle) {
            minOf(config.retryTransportMaxAttempts, config.retryMaxAttempts)
        } else {
            config.retryMaxAttempts
        }
        if (attempt > ceiling) {
            return Schedule(delayMs = 0, attempt = attempt, giveUp = true)
        }
        val suggestion = retryAfterMs?.takeIf { it > 0 }
        val delay = if (suggestion != null) {
            // provider 采信：封顶 retryAfter 口子，不加抖动
            minOf(suggestion, config.retryAfterCapMs)
        } else {
            // 指数翻倍封顶 + 四分之一抖动
            val shift = (attempt - 1).coerceIn(0, 30)
            val exp = try {
                config.retryBaseDelayMs shl shift
            } catch (e: Exception) {
                // 位移溢出走大数路径（shift 已钳 30 内不会发生，防御分支）
                config.retryCapDelayMs
            }
            val base = minOf(exp, config.retryCapDelayMs)
            val jitter = if (base <= 0) 0L else random.nextLong(base / 4 + 1)
            minOf(base + jitter, config.retryCapDelayMs)
        }
        return Schedule(delayMs = delay, attempt = attempt, giveUp = false)
    }
}
