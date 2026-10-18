package com.apex.courierkit.dispatch

import java.util.concurrent.atomic.AtomicBoolean

/**
 * # Courier Guards — 趟次机守卫件
 *
 * - [CourierAbortSignal]：中止信号（volatile 旗标 + 一次性等待）；
 * - [CourierDecline]：用户拒付勘合的**缺陷隧道**载荷——拒付不是工具
 *   失败（模型不该换姿势重试），而是**趟次中断**：经缺陷隧道穿透到
 *   步进机层收口。
 */
class CourierAbortSignal {

    private val raised = AtomicBoolean(false)

    /** 升起中止旗（幂等）。 */
    fun raise() {
        raised.set(true)
    }

    /** 旗是否已升起。 */
    fun isRaised(): Boolean = raised.get()

    /** 复位（新 execute 起点）。 */
    fun reset() {
        raised.set(false)
    }
}

/**
 * 用户拒付（勘合 ask 被拒 / 问答表单被取消）。
 *
 * 与「带反馈的纠正」([feedback] 非空) 的分工：
 * - 带反馈 → 纠正性失败（**模型可见**的工具回执，模型可改姿势）；
 * - 无反馈 → 纯拒付（**缺陷隧道**直达步进机，趟次中断——"用户拒绝了
 *   本次调用"，模型不再重试同一路径）。
 */
class CourierDecline(
    val feedback: String? = null
) : RuntimeException(
    if (feedback.isNullOrEmpty()) {
        "The user declined this dispatch"
    } else {
        "The user declined this dispatch with feedback: $feedback"
    }
)
