package com.apex.courierkit.dispatch

/**
 * # Courier Archivist — 档房（行程日志结构化压缩）
 *
 * 触发：估算 ≥ 触发线（窗口 − max(10%, 16k)）或手动 / 溢出恢复。
 *
 * 流程：
 * 1. **切分**（[splitConversation]）：从尾部累计 `keepTokens`，找到**用户
 *    消息边界**切分（调用与其回执永不拆开；至少保留最后一个交换）；
 *    切分点之前 = 远期段（折叠对象），之后 = 近期段（逐字保留）；
 * 2. **卷宗**：远期段（或 远期段+旧卷宗）经档房役摘要为结构化 dossier
 *    （七标题模板——[CourierPrompts.dossierHeadings]）；缺标题 → **纠偏
 *    一次**，再缺 → 压缩失败；
 * 3. **投递阶梯**：摘要请求溢出 → 远期段**扁平转写**（`[User]:` /
 *    `[Assistant dispatch]:` / `[Tool result]:` ≤1250 字符/条 + 最旧交换
 *    逐组丢弃）→ 仍溢出 → 按 70% → 50% → 35% 收缩目标砍转写长度；
 * 4. **落账**：卷宗信封（[CourierPrompts.dossierEnvelope]）+ 近期段
 *    逐字 → session.applyCheckpoint。
 */
class CourierArchivist(
    private val config: CourierConfig,
    private val modelClient: CourierModelClient,
    private val estimator: CourierTokenEstimator,
    private val failureClassifier: CourierFailureClassifier,
    private val logger: CourierLogger
) {

    /** 压缩结果。 */
    data class Result(
        val performed: Boolean,
        val reason: String,
        val beforeTokens: Int = 0,
        val afterTokens: Int = 0,
        val messagesRemoved: Int = 0,
        val summary: String = "",
        val failure: String? = null
    )

    /** 是否应当压缩（估算 vs 触发线）。 */
    fun shouldCompact(estimateTokens: Int, contextWindow: Int, manual: Boolean): Boolean {
        if (manual) return true
        val ceiling = config.compactCeiling(contextWindow) ?: return false
        return estimateTokens >= ceiling
    }

    /**
     * 执行压缩（调用于趟间安全边界）。返回 [Result]；失败不抛出——
     * 调用方决定继续（未压缩）或终止。
     */
    suspend fun compact(
        session: CourierSession,
        reason: String,
        contextWindow: Int
    ): Result {
        val history = session.tailAfterLastCheckpoint()
        if (history.size < MIN_HISTORY_FOR_COMPACT) {
            return Result(performed = false, reason = reason, failure = "history too short")
        }
        val beforeTokens = session.estimateTokens()
        val (older, recent) = splitConversation(history)
        if (older.isEmpty) {
            return Result(performed = false, reason = reason, failure = "nothing to fold")
        }

        // 卷宗摘要（增量调和 / 首次）
        val previousDossier = session.lastDossier.takeIf { it.isNotBlank() }
        val transcript = flatten(older.toList(), fraction = 1.0)
        val dossier = summarize(previousDossier, transcript)
            ?: return Result(
                performed = false, reason = reason, beforeTokens = beforeTokens,
                failure = "dossier summarization failed after nudge"
            )

        // 落账：卷宗信封 + 近期段逐字
        val recentTranscript = renderRecent(recent)
        val envelope = CourierPrompts.dossierEnvelope(dossier, recentTranscript)
        session.applyCheckpoint(envelope, dossier, recent)
        val afterTokens = session.estimateTokens()
        logger.info(
            TAG,
            "checkpoint applied: $reason, ${older.size} folded, " +
                "$beforeTokens -> $afterTokens tokens"
        )
        return Result(
            performed = true,
            reason = reason,
            beforeTokens = beforeTokens,
            afterTokens = afterTokens,
            messagesRemoved = older.size,
            summary = dossier
        )
    }

    // ═══════════════════════════ 切分 ═══════════════════════════

    /** 切分产物：older（远期段视图）/ recent（近期段）。 */
    internal class Split(
        val older: OlderView,
        val recent: List<DispatchMessage>
    )

    /** 远期段视图（列表或空——不复制大历史两次）。 */
    internal class OlderView(private val backing: List<DispatchMessage>) {
        val size: Int get() = backing.size
        val isEmpty: Boolean get() = backing.isEmpty()
        fun toList(): List<DispatchMessage> = backing
    }

    /**
     * 切分：从尾部累计 keepTokens → 回退到用户消息边界（不拆调用/回执对；
     * 至少保留最后一个交换）。
     */
    internal fun splitConversation(history: List<DispatchMessage>): Split {
        if (history.isEmpty()) return Split(OlderView(emptyList()), emptyList())
        var budget = config.keepTokens
        var cut = history.size
        var index = history.size - 1
        while (index >= 0) {
            val cost = costOf(history[index])
            if (budget - cost < 0) break
            budget -= cost
            cut = index
            index--
        }
        // 全量都在 keep 窗内 → 无远期段
        if (cut <= 0) return Split(OlderView(emptyList()), history.toList())
        // 回退到用户消息边界（避免拆开 交换/回执 对）
        while (cut < history.size) {
            if (history[cut] is DispatchMessage.User) break
            cut++
        }
        // 至少保留最后一个交换（最后一个 User 之后的所有内容）
        val lastUser = history.indexOfLast { it is DispatchMessage.User }
        if (lastUser in 0 until cut) cut = lastUser
        if (cut <= 0) return Split(OlderView(emptyList()), history.toList())
        return Split(
            OlderView(history.subList(0, cut)),
            history.subList(cut, history.size)
        )
    }

    private fun costOf(message: DispatchMessage): Int = when (message) {
        is DispatchMessage.System -> estimator.estimateTokens(message.content)
        is DispatchMessage.User ->
            estimator.estimateTokens(message.content) + message.images.size * config.imageTokenCost

        is DispatchMessage.Assistant ->
            estimator.estimateTokens(message.content) +
                estimator.estimateTokens(message.reasoning) +
                message.toolCalls.sumOf { estimator.estimateTokens(it.arguments) }

        is DispatchMessage.ToolResult -> estimator.estimateTokens(message.content)
    }

    // ═══════════════════════════ 卷宗摘要 ═══════════════════════════

    /** 摘要（缺标题纠偏一次；溢出走收缩阶梯）。 */
    private suspend fun summarize(previousDossier: String?, transcript: String): String? {
        var current = transcript
        for (fraction in SHRINK_LADDER) {
            if (fraction < 1.0) current = shrinkTranscript(transcript, fraction)
            val request = buildSummaryRequest(previousDossier, current)
            val first = requestSummary(request)
            if (first == null) {
                // 溢出 → 阶梯下一档收缩重试；其他失败 → 放弃
                if (lastWasOverflow) continue
                return null
            }
            if (hasAllHeadings(first)) return first
            // 缺标题 → 纠偏一次
            val second = requestSummary(request + "\n\n" + CourierPrompts.dossierNudge)
            when {
                second != null && hasAllHeadings(second) -> return second
                second != null -> return null // 纠偏后仍缺标题 → 失败
                lastWasOverflow -> continue // 纠偏请求溢出 → 下一档
                else -> return null
            }
        }
        return null
    }

    private fun buildSummaryRequest(previousDossier: String?, transcript: String): String =
        if (previousDossier == null) {
            CourierPrompts.dossierFirstPrompt(transcript)
        } else {
            CourierPrompts.dossierUpdatePrompt(previousDossier, transcript)
        }

    @Volatile
    private var lastWasOverflow: Boolean = false

    private suspend fun requestSummary(prompt: String): String? {
        lastWasOverflow = false
        return try {
            val response = modelClient.chat(
                contextTag = config.summaryContextTag,
                messages = listOf(
                    DispatchMessage.System(content = CourierPrompts.archivistPersona()),
                    DispatchMessage.User(content = prompt)
                ),
                temperature = 0.2f,
                maxTokens = config.summaryMaxTokens.coerceAtMost(8_192)
            )
            response.content?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            lastWasOverflow = failureClassifier.classify(t) == CourierFailureKind.CONTEXT_OVERFLOW
            logger.warn(TAG, "dossier request failed: ${t.message}")
            null
        }
    }

    private fun hasAllHeadings(dossier: String): Boolean =
        CourierPrompts.dossierHeadings.all { dossier.contains(it) }

    // ═══════════════════════════ 扁平转写 ═══════════════════════════

    /** 远期段扁平转写（交换分组 + 工具回执限长）。 */
    internal fun flatten(history: List<DispatchMessage>, fraction: Double): String {
        val lines = ArrayList<String>()
        for (message in history) {
            when (message) {
                is DispatchMessage.System -> {} // 系统段不进转写（基线在提示词）
                is DispatchMessage.User -> lines += "[User]: " + clip(message.content, RESULT_CLIP)
                is DispatchMessage.Assistant -> {
                    if (message.content.isNotBlank()) {
                        lines += "[Assistant]: " + clip(message.content, RESULT_CLIP)
                    }
                    for (call in message.toolCalls) {
                        lines += "[Assistant dispatch]: ${call.name}(" +
                            clip(call.arguments, ARG_CLIP) + ")"
                    }
                }

                is DispatchMessage.ToolResult ->
                    lines += "[Tool result]: " + clip(message.content, RESULT_CLIP)
            }
        }
        val bounded = if (fraction >= 1.0) lines else {
            // 收缩：从最旧开始丢行（保最新）
            val keep = (lines.size * fraction).toInt().coerceAtLeast(1)
            val dropped = lines.size - keep
            listOf("[${dropped} older exchange lines omitted]") + lines.subList(dropped, lines.size)
        }
        return bounded.joinToString("\n")
    }

    /** 转写收缩（按比例丢最旧行——保新弃旧）。 */
    private fun shrinkTranscript(full: String, fraction: Double): String {
        val lines = full.lines()
        if (lines.isEmpty()) return full
        val keep = (lines.size * fraction).toInt().coerceAtLeast(1)
        val dropped = lines.size - keep
        return (listOf("[${dropped} older lines omitted]") + lines.subList(dropped, lines.size))
            .joinToString("\n")
    }

    /** 近期段渲染（信封 recent-log 部分——逐字保真）。 */
    private fun renderRecent(recent: List<DispatchMessage>): String = buildString {
        for (message in recent) {
            when (message) {
                is DispatchMessage.System -> {} // 冻结更正行不重复进信封
                is DispatchMessage.User -> {
                    appendLine("[User]: " + clip(message.content, RECENT_CLIP))
                }

                is DispatchMessage.Assistant -> {
                    if (message.content.isNotBlank()) {
                        appendLine("[Assistant]: " + clip(message.content, RECENT_CLIP))
                    }
                    for (call in message.toolCalls) {
                        appendLine("[Assistant dispatch]: ${call.name}(${clip(call.arguments, ARG_CLIP)})")
                    }
                }

                is DispatchMessage.ToolResult ->
                    appendLine("[Tool result]: " + clip(message.content, RECENT_CLIP))
            }
        }
    }.trimEnd()

    private fun clip(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "…"

    private companion object {
        const val TAG = "Archivist"
        const val MIN_HISTORY_FOR_COMPACT = 4
        const val RESULT_CLIP = 1_250
        const val ARG_CLIP = 400
        const val RECENT_CLIP = 2_000
        val SHRINK_LADDER = listOf(1.0, 0.70, 0.50, 0.35)
    }
}
