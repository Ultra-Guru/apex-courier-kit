package com.apex.courierkit.dispatch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * # Courier Trip Runner — 趟次步进机（信使线循环核心）
 *
 * **一趟（trip）= 一条助手消息**；一趟内可发生多次**物理尝试**
 * （attempt：压缩后重试 / 流失败重递 / 溢出恢复——未产出耐久内容前
 * 重试不耗趟次额度）。排水（drain）= 收发匣 → 趟次循环 → 收件投递 →
 * 直到某趟「无本地工具调用」纯文本收束（needsContinuation = false）
 * 且收发匣空闲。
 *
 * ```
 * drain ──► inbox.nextPromotable ──► 投递（User/Synthetic 落账）
 *    ▲                                │ 趟次计数归零
 *    │                                ▼
 *    │                        runTrip（逻辑趟）
 *    │                          ├─ 自动压缩检查（估算 ≥ 触发线 → 档房 → 重试本趟）
 *    │                          ├─ 趟次上限 → 终趟令（工具面保留但关调用）
 *    │                          ├─ 流式消费（文本/思考/调用增量 + 并行工具纤程）
 *    │                          └─ 出口分类：
 *    │                               Done(false)        纯文本收束 → 回 drain
 *    │                               Done(true)         有调用 → 下一趟
 *    │                               ContinueInterrupt  半截流续令 → 新趟（额度不耗）
 *    │                               ContinueLimit      截断续令 → 新趟（连续3次失败）
 *    │                               Retry              流失败重递（同趟）
 *    │                               Failed / Interrupted
 *    └── 控制件（手动压缩）→ 档房 → 继续排水
 * ```
 */
class CourierTripRunner(
    private val config: CourierConfig,
    private val clock: CourierClock,
    private val modelClient: CourierModelClient,
    private val toolSource: CourierToolSource,
    private val toolRunner: CourierToolRunner,
    private val runtime: CourierToolRuntime,
    private val localTools: CourierLocalTools,
    private val permitGate: CourierPermitGate,
    private val session: CourierSession,
    private val inbox: CourierInbox,
    private val bulletin: CourierBulletin,
    private val archivist: CourierArchivist,
    private val planGate: CourierPlanGate,
    private val estimator: CourierTokenEstimator,
    private val modelInfoSource: CourierModelInfoSource,
    private val failureClassifier: CourierFailureClassifier,
    private val backoff: CourierBackoff,
    private val logger: CourierLogger,
    private val abortSignal: CourierAbortSignal,
    private val todoBoard: CourierTodoBoard,
    /** 引擎态供给（画像/模式/档位/强制工具/工位）。 */
    private val state: StateProvider,
    /** 子站模式：无计划门 / 无勘合问答（门已自动拒付）/ 无终趟提示注入差异。 */
    private val childMode: Boolean = false
) {

    /** 引擎态供给口（engine 实现——运行中切换即时生效）。 */
    interface StateProvider {
        val role: DispatchAgentRole
        val mode: DispatchMode
        val thinkingLevel: DispatchThinkingLevel
        val forcedTools: Set<String>
        val forcedExposeAll: Boolean
        val workspaceLabel: String?
        val extras: String?
    }

    /** 排水终态。 */
    sealed interface DrainOutcome {
        /** 排水自然收束（当前批已答完）。 */
        data object Completed : DrainOutcome

        /** 被中止（abort / 用户拒付缺陷隧道）。 */
        data class Interrupted(val cause: String) : DrainOutcome

        /** 失败终止。 */
        data class Failed(val message: String, val recoverable: Boolean) : DrainOutcome
    }

    /** 一趟的出口分类。 */
    private sealed interface TripOutcome {
        /** 本趟收束。[needsContinuation] = true 表示有本地调用，需要下一趟。 */
        data class Done(val needsContinuation: Boolean) : TripOutcome

        /** 半截流续令（新趟，额度不耗）。 */
        data object ContinueInterrupted : TripOutcome

        /** 输出截断续令（新趟，额度不耗但有连续上限）。 */
        data object ContinueLimit : TripOutcome

        /** 趟内失败。 */
        data class Failed(val message: String, val recoverable: Boolean) : TripOutcome

        /** 中断（abort / 拒付隧道 / 计划门超时）。 */
        data class Interrupted(val cause: String) : TripOutcome
    }

    /** 连续 length 收尾计数（≥ 上限 → 失败）。 */
    private var consecutiveLengthFinishes = 0

    private val assembler = CourierCallAssembler()

    // ═══════════════════════════ 排水主循环 ═══════════════════════════

    /**
     * 排水：投递 → 趟次循环，直到收发匣空闲且末趟纯文本收束。
     * 事件经 [emit] 直通引擎（引擎负责映射宿主协议）。
     */
    suspend fun drain(emit: suspend (DispatchEvent) -> Unit): DrainOutcome {
        var logicalTrip = 1
        var displayTrip = 1
        while (true) {
            if (abortSignal.isRaised()) {
                return DrainOutcome.Interrupted("aborted")
            }
            val promotion = inbox.nextPromotable() ?: break
            if (promotion.control != null) {
                // 手动压缩控制件：档房 → 继续排水（更正行随 checkpoint 推进）
                val result = archivist.compact(session, "manual", windowTokens())
                if (result.performed) {
                    bulletin.advanceEpoch()
                    emitCompressed(emit, result)
                } else {
                    logger.info(TAG, "manual compaction skipped: ${result.failure}")
                }
                continue
            }
            // 可见件落账
            for (item in promotion.items) {
                when (item) {
                    is CourierInbox.Item.UserItem -> session.appendUser(item)
                    is CourierInbox.Item.SyntheticItem -> session.appendSynthetic(item)
                    is CourierInbox.Item.CompactNow -> Unit // 已处理
                }
            }
            if (promotion.resetTripCounter) {
                logicalTrip = 1
            }
            // 趟次循环：直到本批收束
            var tripLoopActive = true
            while (tripLoopActive) {
                if (abortSignal.isRaised()) {
                    return DrainOutcome.Interrupted("aborted")
                }
                bulletin.refresh()
                val notice = bulletin.consumePendingNotice()
                val notices = listOfNotNull(notice)
                val outcome = runTrip(displayTrip, logicalTrip, notices, emit)
                displayTrip++
                when (outcome) {
                    is TripOutcome.Done -> {
                        if (outcome.needsContinuation) {
                            logicalTrip++
                        } else {
                            tripLoopActive = false
                        }
                    }

                    is TripOutcome.ContinueInterrupted,
                    is TripOutcome.ContinueLimit -> logicalTrip++ // 续令也是新趟（额度口径：不额外收紧）

                    is TripOutcome.Failed ->
                        return DrainOutcome.Failed(outcome.message, outcome.recoverable)

                    is TripOutcome.Interrupted ->
                        return DrainOutcome.Interrupted(outcome.cause)
                }
            }
        }
        return DrainOutcome.Completed
    }

    // ═══════════════════════════ 单趟（逻辑步）═══════════════════════════

    private suspend fun runTrip(
        displayTrip: Int,
        logicalTrip: Int,
        notices: List<String>,
        emit: suspend (DispatchEvent) -> Unit
    ): TripOutcome {
        var attempt = 0
        var overflowRecoveryUsed = false
        while (true) {
            if (abortSignal.isRaised()) return TripOutcome.Interrupted("aborted")
            attempt++
            // ── 自动压缩（估算 ≥ 触发线）──
            val window = windowTokens()
            val estimate = session.estimateTokens() +
                notices.sumOf { estimator.estimateTokens(it) }
            if (archivist.shouldCompact(estimate, window, manual = false)) {
                val result = archivist.compact(session, "auto", window)
                if (result.performed) {
                    bulletin.advanceEpoch()
                    emitCompressed(emit, result)
                    continue // 同趟新尝试（未产出耐久内容）
                }
                // 压不动：继续请求（溢出时走溢出恢复）
            }
            // ── 终趟判定 ──
            val role = state.role
            val stepCeiling = role.steps ?: config.maxTrips
            val finalTrip = logicalTrip >= stepCeiling
            // ── 上下文组装 ──
            val persona = CourierPrompts.personaFor(role, state.mode, state.workspaceLabel)
            val checklist = todoBoard.render().takeIf { !todoBoard.isEmpty() }
                ?.let { CourierPrompts.checklistSection(it) }
            val extras = state.extras?.takeIf { it.isNotBlank() }
            val extrasNotice = extras?.let { listOf(it) } ?: emptyList()
            val allNotices = notices + extrasNotice
            var messages = session.toModelMessages(
                persona = persona,
                bulletinBaseline = bulletin.baselineSection(),
                checklist = checklist,
                notices = allNotices
            )
            if (finalTrip && !childMode) {
                messages += DispatchMessage.System(content = CourierPrompts.finalTripPrompt)
            }
            // ── 工具面 ──
            val defs = visibleToolSurface(role, state.mode)
            val toolChoice: DispatchToolChoice? =
                if (finalTrip) DispatchToolChoice.None else null
            // ── 预算 ──
            val modelInfo = modelInfoSource.current()
            val pendingEstimate = CourierBudget.pendingEstimate(
                estimator, config,
                texts = messages.map { m ->
                    when (m) {
                        is DispatchMessage.System -> m.content
                        is DispatchMessage.User -> m.content
                        is DispatchMessage.Assistant -> m.content
                        is DispatchMessage.ToolResult -> m.content
                    }
                },
                imageCount = messages.filterIsInstance<DispatchMessage.User>()
                    .sumOf { it.images.size }
            )
            val maxTokens = CourierBudget.outputMaxTokens(
                config, modelInfo,
                measuredInputTokens = session.lastMeasuredInputTokens,
                pendingEstimate = pendingEstimate
            )
            // ── 请求与流式消费 ──
            emit(DispatchEvent.IterationStart(displayTrip))
            emit(DispatchEvent.ThinkingStart(displayTrip, state.thinkingLevel))
            val textBuf = StringBuilder()
            val reasonBuf = StringBuilder()
            assembler.reset()
            var durable = false
            var finishReason: String? = null
            var usage: DispatchUsage? = null
            var streamFailure: Throwable? = null
            try {
                modelClient.chatStream(
                    contextTag = if (childMode) config.childContextTag else config.loopContextTag,
                    messages = messages,
                    tools = defs,
                    temperature = -1f, // 哨兵：Profile 值
                    maxTokens = maxTokens,
                    toolChoice = toolChoice
                ).collect { chunk ->
                    chunk.reasoningContent?.let { delta ->
                        reasonBuf.append(delta)
                        durable = true
                        emit(DispatchEvent.ThinkingChunk(delta))
                    }
                    chunk.content?.let { delta ->
                        textBuf.append(delta)
                        durable = true
                        emit(DispatchEvent.ResponseChunk(delta))
                    }
                    if (chunk.toolCalls.isNotEmpty()) {
                        chunk.toolCalls.forEach { assembler.feed(it) }
                    }
                    chunk.usage?.let { usage = it }
                    if (chunk.isFinish) {
                        finishReason = chunk.finishReason
                    }
                }
            } catch (t: Throwable) {
                streamFailure = t
            }
            if (usage != null) {
                session.lastMeasuredInputTokens = usage!!.promptTokens
                emit(
                    DispatchEvent.UsageUpdated(
                        usage!!.promptTokens, usage!!.completionTokens, usage!!.totalTokens
                    )
                )
            }
            // ── 流失败分类 ──
            if (streamFailure != null) {
                val t = streamFailure!!
                val kind = failureClassifier.classify(t)
                if (kind == CourierFailureKind.CONTEXT_OVERFLOW && !durable && !overflowRecoveryUsed) {
                    // 溢出恢复：先压缩再重试本趟（仅一次）
                    val result = archivist.compact(session, "overflow-recovery", windowTokens())
                    if (result.performed) {
                        overflowRecoveryUsed = true
                        bulletin.advanceEpoch()
                        emitCompressed(emit, result)
                        continue
                    }
                }
                if (durable) {
                    // 半截流：保留部分产物 + 续令新趟
                    if (kind == CourierFailureKind.TRANSIENT) {
                        val schedule = backoff.next(attempt)
                        if (schedule.giveUp) {
                            return TripOutcome.Failed(
                                "dispatch stream failed after retries: ${t.message}", true
                            )
                        }
                        emit(
                            DispatchEvent.LlmRetryScheduled(
                                attempt = schedule.attempt,
                                delayMs = schedule.delayMs,
                                reason = "stream interrupted: ${t.message ?: "unknown"}"
                            )
                        )
                        delay(schedule.delayMs)
                    }
                    session.appendAssistant(textBuf.toString(), emptyList(), reasonBuf.toString())
                    emit(DispatchEvent.ResponseComplete(textBuf.toString()))
                    session.appendSynthetic(
                        CourierInbox.Item.SyntheticItem(
                            id = "continue-$displayTrip-$attempt",
                            text = CourierPrompts.continueAfterInterrupt
                        )
                    )
                    return TripOutcome.ContinueInterrupted
                }
                // 耐久内容前失败：同趟重递阶梯
                if (kind == CourierFailureKind.TRANSIENT) {
                    val transportStyle = isTransportStyle(t)
                    val schedule = backoff.next(attempt, retryAfterOf(t), transportStyle)
                    if (schedule.giveUp) {
                        return TripOutcome.Failed(
                            "dispatch failed after ${attempt} attempts: ${t.message}", true
                        )
                    }
                    emit(
                        DispatchEvent.LlmRetryScheduled(
                            attempt = schedule.attempt,
                            delayMs = schedule.delayMs,
                            reason = t.message ?: t::class.simpleName ?: "stream error"
                        )
                    )
                    delay(schedule.delayMs)
                    continue
                }
                return TripOutcome.Failed(
                    "dispatch rejected: ${t.message ?: t::class.simpleName}", false
                )
            }
            // ── 收尾分类 ──
            val normalized = DispatchFinish.normalize(finishReason)
            if (normalized == DispatchFinish.CONTENT_FILTER) {
                session.appendAssistant(textBuf.toString(), emptyList(), reasonBuf.toString())
                emit(DispatchEvent.ResponseComplete(textBuf.toString()))
                return TripOutcome.Failed("response stopped by the provider content filter", false)
            }
            if (normalized == DispatchFinish.LENGTH) {
                consecutiveLengthFinishes++
                if (consecutiveLengthFinishes > config.outputLimitContinues + 1) {
                    return TripOutcome.Failed(
                        "output hit the token limit ${consecutiveLengthFinishes} times in a row",
                        true
                    )
                }
                session.appendAssistant(textBuf.toString(), emptyList(), reasonBuf.toString())
                emit(DispatchEvent.ResponseComplete(textBuf.toString()))
                session.appendSynthetic(
                    CourierInbox.Item.SyntheticItem(
                        id = "limit-$displayTrip-$attempt",
                        text = CourierPrompts.continueAfterOutputLimit
                    )
                )
                return TripOutcome.ContinueLimit
            }
            consecutiveLengthFinishes = 0
            // ── 成器 ──
            val calls = assembler.settle()
            session.appendAssistant(textBuf.toString(), calls, reasonBuf.toString())
            emit(DispatchEvent.ThinkingComplete(reasonBuf.toString()))
            emit(DispatchEvent.ResponseComplete(textBuf.toString()))
            if (calls.isEmpty()) {
                return finishTextOnlyTrip(emit)
            }
            // ── 工具纤程 ──
            val interrupted = executeCalls(calls, emit, role)
            if (interrupted != null) return interrupted
            return TripOutcome.Done(needsContinuation = true)
        }
    }

    /** 纯文本收束趟：PLAN 模式走计划门；BUILD 直接收束。 */
    private suspend fun finishTextOnlyTrip(
        emit: suspend (DispatchEvent) -> Unit
    ): TripOutcome {
        if (state.mode == DispatchMode.PLAN && !childMode) {
            val text = session.rawMessages()
                .filterIsInstance<DispatchMessage.Assistant>()
                .lastOrNull()?.content ?: ""
            val plan = planGate.parsePlan(text)
            if (plan != null) {
                emit(DispatchEvent.PlanGenerated(plan))
                emit(DispatchEvent.PlanAwaitingConfirmation(plan))
                val verdict = planGate.await(plan)
                return when (verdict) {
                    true -> {
                        emit(DispatchEvent.PlanConfirmed(plan))
                        session.appendSynthetic(
                            CourierInbox.Item.SyntheticItem(
                                id = "plan-approved-${plan.steps.size}",
                                text = CourierPrompts.planApprovedNotice
                            )
                        )
                        TripOutcome.Done(needsContinuation = true)
                    }

                    false -> {
                        session.appendSynthetic(
                            CourierInbox.Item.SyntheticItem(
                                id = "plan-rejected-${plan.steps.size}",
                                text = CourierPrompts.planRejectedNotice(planGate.lastFeedback)
                            )
                        )
                        TripOutcome.Done(needsContinuation = true)
                    }

                    null -> {
                        emit(DispatchEvent.UserInputExpired)
                        TripOutcome.Interrupted("plan gate timed out")
                    }
                }
            }
        }
        return TripOutcome.Done(needsContinuation = false)
    }

    /** 工具纤程执行（并行或串行）；返回非 null = 趟次中断。 */
    private suspend fun executeCalls(
        calls: List<DispatchToolCall>,
        emit: suspend (DispatchEvent) -> Unit,
        role: DispatchAgentRole
    ): TripOutcome? = coroutineScope {
        val gate = if (config.maxParallelTools > 0) {
            Semaphore(config.maxParallelTools)
        } else {
            null
        }
        val results = if (config.parallelToolCalls) {
            calls.map { call ->
                async {
                    if (gate != null) gate.withPermit { executeCall(call, emit, role) }
                    else executeCall(call, emit, role)
                }
            }.awaitAll()
        } else {
            calls.map { call -> executeCall(call, emit, role) }
        }
        // 拒付隧道 / 中断优先上报
        results.firstOrNull { it != null }?.let { return@coroutineScope it }
        null
    }

    /**
     * 单调用执行：勘合核验 → 本地/宿主路由 → 回执落账。
     * 返回非 null = 中断（拒付隧道 / 勘验超时 / 中止）。
     */
    private suspend fun executeCall(
        call: DispatchToolCall,
        emit: suspend (DispatchEvent) -> Unit,
        role: DispatchAgentRole
    ): TripOutcome? {
        if (abortSignal.isRaised()) return TripOutcome.Interrupted("aborted")
        val startedAt = clock.nowMillis()
        emit(
            DispatchEvent.ToolCallStart(
                callId = call.id,
                toolName = call.name,
                arguments = call.arguments
            )
        )
        // ── 勘合 ──
        val inspection = permitGate.inspect(call.name, call.arguments, role, state.mode)
        val verdict = inspection.precomputed ?: run {
            val prompt = CourierPrompts.permitAskPrompt(
                action = inspection.requests.firstOrNull()?.action ?: "tool",
                resource = inspection.requests.firstOrNull()?.resource ?: call.name
            )
            val ticket = permitGate.issueTicket(inspection, prompt)
            permitGate.awaitDecision(ticket)
        }
        when (verdict) {
            is CourierPermitGate.PermitOutcome.Blocked -> {
                settleCall(call, verdict.message, verdict.message, false, startedAt, emit)
                return null
            }

            is CourierPermitGate.PermitOutcome.Declined -> {
                // 带反馈 = 纠正性回执（模型可见）；无反馈 = 缺陷隧道（中断）
                if (verdict.feedback != null) {
                    val recept = "The user declined this dispatch with feedback: " +
                        "${verdict.feedback}. Change approach accordingly."
                    settleCall(call, recept, recept, false, startedAt, emit)
                    return null
                }
                failUnsettled(call, "The user declined this dispatch", startedAt, emit)
                return TripOutcome.Interrupted("user declined a permit")
            }

            CourierPermitGate.PermitOutcome.Expired -> {
                failUnsettled(call, "The permit request expired without an answer", startedAt, emit)
                return TripOutcome.Interrupted("permit ask expired")
            }

            CourierPermitGate.PermitOutcome.Granted -> Unit
        }
        // ── 路由执行 ──
        val outcome: RelayOutcome = if (localTools.isLocal(call.name)) {
            val local = localTools.execute(call.name, call.arguments) { event -> emit(event) }
            RelayOutcome(
                success = local.success,
                modelContent = local.model,
                fullContent = local.model
            )
        } else {
            val relay = runtime.relayHostStream(
                toolRunner.executeStream(call.name, call.arguments)
            ) { event ->
                when (event) {
                    is DispatchToolStream.Output ->
                        emit(DispatchEvent.ToolOutputChunk(call.id, event.chunk))

                    is DispatchToolStream.Progress ->
                        emit(DispatchEvent.ToolProgress(call.id, event.percent, event.message))

                    else -> Unit
                }
            }
            RelayOutcome(relay.success, relay.modelContent, relay.fullContent)
        }
        settleCall(call, outcome.modelContent, outcome.fullContent, outcome.success, startedAt, emit)
        return null
    }

    private data class RelayOutcome(
        val success: Boolean,
        val modelContent: String,
        val fullContent: String
    )

    /** 回执落账 + 完成事件。 */
    private suspend fun settleCall(
        call: DispatchToolCall,
        modelContent: String,
        fullContent: String,
        success: Boolean,
        startedAt: Long,
        emit: suspend (DispatchEvent) -> Unit
    ) {
        session.appendToolResult(callId = call.id, content = modelContent)
        emit(
            DispatchEvent.ToolCallComplete(
                callId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                output = modelContent,
                fullOutput = fullContent,
                success = success,
                durationMs = clock.nowMillis() - startedAt
            )
        )
    }

    /** 未决调用失败落账（中止 / 拒付隧道路径）。 */
    private suspend fun failUnsettled(
        call: DispatchToolCall,
        message: String,
        startedAt: Long,
        emit: suspend (DispatchEvent) -> Unit
    ) {
        settleCall(call, message, message, false, startedAt, emit)
    }

    // ── 工具面 ──

    /** 可见工具面：本地三件 + 宿主面（整枝剪除 + 强制工具过滤）。 */
    private fun visibleToolSurface(
        role: DispatchAgentRole,
        mode: DispatchMode
    ): List<DispatchToolDefinition> {
        val hostDefs = toolSource.definitions()
            .filterNot { permitGate.isWhollyDenied(it.name, role, mode) }
        val localDefs = localTools.definitions()
        return if (state.forcedTools.isNotEmpty() && !state.forcedExposeAll) {
            (hostDefs + localDefs).filter { it.name in state.forcedTools || it.name.startsWith("courier_") }
        } else {
            hostDefs + localDefs
        }
    }

    // ── 杂项 ──

    private fun windowTokens(): Int = modelInfoSource.current()?.contextWindow ?: 0

    private suspend fun emitCompressed(
        emit: suspend (DispatchEvent) -> Unit,
        result: CourierArchivist.Result
    ) {
        emit(
            DispatchEvent.ContextCompressed(
                beforeTokens = result.beforeTokens,
                afterTokens = result.afterTokens,
                strategy = "TRIP_DOSSIER",
                summary = result.summary,
                messagesRemoved = result.messagesRemoved,
                messagesTruncated = 0
            )
        )
    }

    private fun isTransportStyle(t: Throwable): Boolean {
        val name = (t.message ?: "").lowercase()
        return name.contains("timeout") || name.contains("timed out")
    }

    private fun retryAfterOf(t: Throwable): Long? {
        val text = (t.message ?: "").lowercase()
        val m = Regex("(?:retry[- ]after|retryafter)[:=]\\s*(\\d+)").find(text) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private companion object {
        const val TAG = "TripRunner"
    }
}
