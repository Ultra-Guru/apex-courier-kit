package com.apex.courierkit.dispatch

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * # Courier Outpost — 下站前哨（子驿站会话簿记）
 *
 * `courier_task` 的执行端口（[CourierTaskLauncher] 实现）：
 *
 * - **深度闸**：本站深度 ≥ [CourierConfig.subagentDepthLimit] → 拒派
 *   （子驿不再递归）；
 * - **前台**：同步等待子驿排水完成，结果进 `<courier>` 信封回执；
 * - **后台**：登记子站句柄立即回执（RUNNING + 不轮询训令），完成时
 *   向母站收发匣投递**合成输入**（[CourierPrompts.backgroundCompletion]）
 *   ——母站下一安全边界被唤醒，读到完整结果；
 * - **收口**：[shutdownAll] 取消全部后台子驿（中止 / 新会话）。
 *
 * 子驿的**会话与排水执行**由引擎注入的 [childRunner] 承载——前哨只管
 * 簿记与投递，不拥有循环语义。
 */
class CourierOutpost(
    private val config: CourierConfig,
    private val logger: CourierLogger,
    /** 后台子驿的宿主作用域（引擎生命周期）。 */
    private val scope: CoroutineScope,
    private val parentInbox: CourierInbox,
    /** 本站深度（主站 = 0；子驿 = 母站深度 + 1）。 */
    private val depth: Int,
    /** 子驿排水执行器（引擎注入——返回子驿最终文本）。 */
    private val childRunner: suspend (
        request: CourierTaskLauncher.TaskRequest,
        stationId: String,
        childDepth: Int
    ) -> String
) : CourierTaskLauncher {

    private val stationSeq = AtomicLong(0)
    private val backgroundJobs = CopyOnWriteArrayList<Job>()

    override suspend fun launch(request: CourierTaskLauncher.TaskRequest): CourierTaskLauncher.TaskResult {
        if (depth >= config.subagentDepthLimit) {
            return CourierTaskLauncher.TaskResult(
                stationId = "depth-limited",
                status = DispatchChildStatus.FAILED,
                output = "Sub-station depth limit reached (${config.subagentDepthLimit}). " +
                    "Handle this chore in the current station instead."
            )
        }
        val stationId = "station-${stationSeq.incrementAndGet()}"
        return if (request.background) {
            val job = scope.launch {
                val output = runChildSafely(request, stationId)
                val notice = CourierPrompts.backgroundCompletion(
                    stationId = stationId,
                    description = request.description,
                    output = output
                )
                parentInbox.admit(
                    CourierInbox.Item.SyntheticItem(
                        id = "bg-$stationId",
                        text = notice,
                        description = "sub-station completion: $stationId"
                    )
                )
            }
            backgroundJobs += job
            CourierTaskLauncher.TaskResult(
                stationId = stationId,
                status = DispatchChildStatus.RUNNING,
                output = ""
            )
        } else {
            val output = runChildSafely(request, stationId)
            CourierTaskLauncher.TaskResult(
                stationId = stationId,
                status = DispatchChildStatus.COMPLETED,
                output = output
            )
        }
    }

    /** 取消全部后台子驿（中止 / 新会话收口）。 */
    fun shutdownAll() {
        for (job in backgroundJobs) {
            runCatching { job.cancel() }
        }
        backgroundJobs.clear()
    }

    /** 后台子驿数（运行中）。 */
    fun activeBackgroundCount(): Int = backgroundJobs.count { it.isActive }

    private suspend fun runChildSafely(
        request: CourierTaskLauncher.TaskRequest,
        stationId: String
    ): String = try {
        childRunner(request, stationId, depth + 1)
    } catch (t: Throwable) {
        logger.warn(TAG, "sub-station $stationId failed: ${t.message}")
        "Sub-station $stationId failed before producing a result: ${t.message ?: t::class.simpleName}"
    }

    private companion object {
        const val TAG = "Outpost"
    }
}
