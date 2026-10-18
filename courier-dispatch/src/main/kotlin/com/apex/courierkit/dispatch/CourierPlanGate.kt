package com.apex.courierkit.dispatch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Courier Plan Gate — 计划门（PLAN 趟的人控确认）
 *
 * PLAN 模式流（宿主 UX 一致的适配口径）：
 *
 * 1. 规划趟完成 → 从正文解析结构化计划（`GOAL:` / `STEPS:` 编号 /
 *    `RISKS:`）；
 * 2. 解析出 ≥1 步 → [DispatchEvent.PlanGenerated] +
 *    [DispatchEvent.PlanAwaitingConfirmation]，挂起等待确认；
 * 3. [confirm]：批准 → 注入批准续令（转入执行——画像法则放宽到构建者
 *    口径）；驳回 → 注入反馈续令（继续修订计划）；
 * 4. 超时 → [DispatchEvent.UserInputExpired]，按未决处理（趟次中断）。
 */
class CourierPlanGate(
    private val config: CourierConfig,
    private val clock: CourierClock
) {

    /** 确认等待句柄。 */
    class Awaiting internal constructor(
        val plan: DispatchExecutionPlan,
        internal val deferred: CompletableDeferred<Boolean?>
    )

    /** 挂起中的计划（null = 无）。 */
    @Volatile
    private var awaiting: Awaiting? = null

    /** 是否有计划待确认。 */
    fun isAwaiting(): Boolean = awaiting != null

    /**
     * 挂起等待确认。
     *
     * @return 确认结果（true 批准 / false 驳回 / null 超时或收口）
     */
    suspend fun await(plan: DispatchExecutionPlan): Boolean? {
        val handle = Awaiting(plan, CompletableDeferred())
        awaiting = handle
        try {
            return withTimeoutOrNull(PLAN_GATE_TIMEOUT_MS) { handle.deferred.await() }
        } finally {
            awaiting = null
        }
    }

    /** 确认/驳回（true = 送达挂起等待）。 */
    fun confirm(approved: Boolean, feedback: String? = null): Boolean {
        val handle = awaiting ?: return false
        lastFeedback = feedback
        return handle.deferred.complete(approved)
    }

    /** 最近一次驳回反馈（引擎取用注入）。 */
    @Volatile
    var lastFeedback: String? = null
        private set

    /** 收口（中止 / 清会话）：挂起计划按超时兑现。 */
    fun cancelPending() {
        awaiting?.deferred?.complete(null)
        awaiting = null
    }

    /**
     * 从规划正文解析结构化计划。
     *
     * 认可形态：
     * ```
     * GOAL: 一句话目标
     * STEPS:
     * 1. 步骤一（可含 工具@路径 提示）
     * 2. 步骤二
     * RISKS: 风险行（可缺省）
     * ```
     */
    fun parsePlan(text: String): DispatchExecutionPlan? {
        val goal = Regex("(?im)^\\s*goal\\s*[:：]\\s*(.+)$")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val stepsBlock = Regex("(?ims)^\\s*steps\\s*[:：]?\\s*\\n(.+?)(?=\\n\\s*(risks\\s*[:：]|$))")
            .find(text)?.groupValues?.get(1)
            ?: return null
        val steps = Regex("(?m)^\\s*(?:\\d+[.、)]|[-*])\\s*(.+)$")
            .findAll(stepsBlock)
            .mapIndexed { index, m ->
                val raw = m.groupValues[1].trim()
                DispatchPlanStep(
                    index = index,
                    description = raw.substringBefore("（").substringBefore("(").trim()
                        .ifEmpty { raw },
                    toolName = extractToolHint(raw),
                    estimatedArgs = null,
                    dependsOn = emptyList()
                )
            }
            .toList()
        if (steps.isEmpty()) return null
        val risks = Regex("(?im)^\\s*risks\\s*[:：]\\s*(.+)$")
            .find(text)?.groupValues?.get(1)?.trim() ?: "none"
        return DispatchExecutionPlan(
            goal = goal,
            steps = steps,
            estimatedToolCalls = steps.size.coerceAtMost(12),
            riskLevel = when {
                risks.contains("critical", ignoreCase = true) -> DispatchRiskLevel.CRITICAL
                risks.contains("high", ignoreCase = true) -> DispatchRiskLevel.HIGH
                risks.contains("medium", ignoreCase = true) || risks.equals("none", true) ->
                    DispatchRiskLevel.MEDIUM

                else -> DispatchRiskLevel.LOW
            },
            reasoning = risks
        )
    }

    /** 计划步里的工具提示（`步骤…（edit@main.kt）` → edit）。 */
    private fun extractToolHint(raw: String): String? {
        val m = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*@\\s*([\\w./-]+)").find(raw) ?: return null
        return m.groupValues[1]
    }

    private companion object {
        const val PLAN_GATE_TIMEOUT_MS = 600_000L
    }
}
