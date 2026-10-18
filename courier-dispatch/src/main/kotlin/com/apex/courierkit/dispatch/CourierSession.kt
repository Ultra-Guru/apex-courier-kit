package com.apex.courierkit.dispatch

/**
 * # Courier Session — 在驿卷宗（会话态与模型视图）
 *
 * 会话两条视图：
 *
 * - **落账视图**（[messages]）：直接持久化到 [CourierMemory] 的
 *   DispatchMessage 序列（System/User/Assistant/ToolResult——与宿主记忆
 *   通道 1:1）；
 * - **模型视图**（[toModelMessages]）：每趟请求的组装产物 = 系统段
 *   （画像基线 + 示廊基线）+ checkpoint 起的历史 + 附加注入（清单 /
 *   计划门 / 冻结更正行）。
 *
 * checkpoint（档房压缩成功）把 `历史前段` 折叠为**单条卷宗信封**（User
 * 消息），`近期段` 逐字保留——模型视图从最近 checkpoint 起读。
 */
class CourierSession(
    private val memory: CourierMemory?,
    private val estimator: CourierTokenEstimator,
    private val config: CourierConfig
) {

    /** 落账消息（checkpoint 起的全量——首 checkpoint 前为完整历史）。 */
    private val entries = ArrayList<DispatchMessage>()

    /** 最近一次模型实测输入 token（估算基线——lastUsage 优先）。 */
    @Volatile
    var lastMeasuredInputTokens: Int = 0

    /** 历史是否已有 checkpoint（增量卷宗口径）。 */
    var hasCheckpoint: Boolean = false
        private set

    /** 最近一份卷宗文本（增量调和用）。 */
    var lastDossier: String = ""
        private set

    // ═══════════════════════════ 装载 / 落账 ═══════════════════════════

    /** 从记忆通道装载（工作区切换 / 冷启）。 */
    fun load() {
        entries.clear()
        entries += memory?.load() ?: emptyList()
        hasCheckpoint = entries.any { it.isDossierEnvelope() }
        if (hasCheckpoint) {
            lastDossier = entries
                .filterIsInstance<DispatchMessage.User>()
                .lastOrNull { it.content.contains("<dossier>") }
                ?.extractDossier() ?: ""
        }
    }

    fun messageCount(): Int = entries.size

    fun rawMessages(): List<DispatchMessage> = entries.toList()

    fun clear() {
        entries.clear()
        hasCheckpoint = false
        lastDossier = ""
        lastMeasuredInputTokens = 0
        memory?.clear()
    }

    fun bindWorkspace(workspaceId: String) {
        memory?.bindWorkspace(workspaceId)
    }

    private fun record(message: DispatchMessage) {
        entries += message
        memory?.append(message)
    }

    // ═══════════════════════════ 落账入口 ═══════════════════════════

    /** 用户可见输入（收发匣投递）。 */
    fun appendUser(item: CourierInbox.Item.UserItem) {
        record(
            DispatchMessage.User(
                content = item.text,
                images = item.images
            )
        )
    }

    /** 合成输入（后台完成 / 续令 / 计划批驳——落 User，不进用户气泡）。 */
    fun appendSynthetic(item: CourierInbox.Item.SyntheticItem) {
        record(DispatchMessage.User(content = item.text))
    }

    /** 助手趟产物（正文 + 工具调用 + 思考链）。 */
    fun appendAssistant(
        content: String,
        toolCalls: List<DispatchToolCall>,
        reasoning: String
    ) {
        record(
            DispatchMessage.Assistant(
                content = content,
                toolCalls = toolCalls,
                reasoning = reasoning
            )
        )
    }

    /** 工具回执。 */
    fun appendToolResult(callId: String, content: String) {
        record(DispatchMessage.ToolResult(toolCallId = callId, content = content))
    }

    /** checkpoint 落账：前段折叠为卷宗信封，近期段逐字保留。 */
    fun applyCheckpoint(envelope: String, dossier: String, recent: List<DispatchMessage>) {
        val next = ArrayList<DispatchMessage>(recent.size + 1)
        next += DispatchMessage.User(content = envelope)
        next += recent
        entries.clear()
        entries += next
        hasCheckpoint = true
        lastDossier = dossier
        memory?.save(entries.toList())
    }

    // ═══════════════════════════ 模型视图 ═══════════════════════════

    /**
     * 组装模型请求消息：系统段 + 历史 + 附加注入段。
     * 历史**不在此裁剪**（压缩触发判定在档房——估算用 [estimateTokens]）。
     */
    fun toModelMessages(
        persona: String,
        bulletinBaseline: String?,
        checklist: String?,
        notices: List<String>
    ): List<DispatchMessage> {
        val system = buildString {
            append(persona)
            if (!bulletinBaseline.isNullOrBlank()) {
                append("\n\n")
                append(bulletinBaseline)
            }
        }
        val result = ArrayList<DispatchMessage>(entries.size + 2 + notices.size)
        result += DispatchMessage.System(content = system)
        result += entries
        for (notice in notices) {
            result += DispatchMessage.System(content = notice)
        }
        if (checklist != null) {
            result += DispatchMessage.System(content = checklist)
        }
        return result
    }

    // ═══════════════════════════ 估算 ═══════════════════════════

    /** 当前上下文 token 估算（实测优先 + 附加段估算）。 */
    fun estimateTokens(): Int {
        var estimate = 0
        for (m in entries) {
            estimate += when (m) {
                is DispatchMessage.System -> estimator.estimateTokens(m.content)
                is DispatchMessage.User ->
                    estimator.estimateTokens(m.content) + m.images.size * config.imageTokenCost

                is DispatchMessage.Assistant ->
                    estimator.estimateTokens(m.content) +
                        estimator.estimateTokens(m.reasoning) +
                        m.toolCalls.sumOf { estimator.estimateTokens(it.arguments) }

                is DispatchMessage.ToolResult -> estimator.estimateTokens(m.content)
            }
        }
        return estimate
    }

    /** 最近 checkpoint 之后的近期段（档房切分输入）。 */
    fun tailAfterLastCheckpoint(): List<DispatchMessage> {
        val idx = entries.indexOfLast { it.isDossierEnvelope() }
        return if (idx < 0) entries.toList() else entries.subList(idx + 1, entries.size)
    }

    /** 末条用户消息内容（答复 / 判定空闲用）。 */
    fun lastUserText(): String? =
        entries.filterIsInstance<DispatchMessage.User>().lastOrNull()?.content

    // ── 卷宗信封识别 ──

    private fun DispatchMessage.isDossierEnvelope(): Boolean =
        this is DispatchMessage.User && content.contains("<station-dossier>")

    private fun DispatchMessage.User.extractDossier(): String {
        val open = content.indexOf("<dossier>")
        val close = content.indexOf("</dossier>")
        return if (open >= 0 && close > open) {
            content.substring(open + "<dossier>".length, close).trim()
        } else {
            ""
        }
    }
}
