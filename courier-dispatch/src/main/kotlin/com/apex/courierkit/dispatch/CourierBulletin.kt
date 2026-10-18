package com.apex.courierkit.dispatch

import java.security.MessageDigest

/**
 * # Courier Bulletin — 示廊（常设指令源的价值增量公告）
 *
 * 常设指令（环境 / 日期 / 工作区 / 全局规则 / 项目规则 / 技能索引）以
 * **命名源**注册；每趟前逐源读取 → 规范拼接 → SHA-256 指纹：
 *
 * - **首次**（epoch 起点）：全文进 **基线**（渲染进系统提示词——
 *   每趟都在）；
 * - **变更**：产出一条**冻结更正行**（`Standing instructions changed:
 *   …`，以 System 消息渲染**一次**，随后并入基线）——模型在时间轴上
 *   看到一次显式更正，而不是提示词悄悄变；
 * - **epoch 推进**：checkpoint 落位时基线重置（更正行已进落账历史）。
 */
class CourierBulletin(private val clock: CourierClock) {

    /** 命名指令源（稳定顺序 = 注册顺序——缓存前缀友好）。 */
    class NamedSource(
        val key: String,
        val header: String,
        private val provider: () -> String?
    ) {
        fun read(): String? = provider()?.takeIf { it.isNotBlank() }
    }

    private val sources = ArrayList<NamedSource>()

    /** 当前 epoch 基线（渲染进系统提示词）。 */
    @Volatile
    private var baseline: String = ""

    /** 基线指纹。 */
    @Volatile
    private var baselineFingerprint: String = ""

    /** 待渲染的冻结更正行（consumePendingNotices 一次性取走）。 */
    private val pendingNotices = ArrayDeque<String>()

    /** 注册指令源（构建期一次性注册）。 */
    fun register(key: String, header: String, provider: () -> String?) {
        sources += NamedSource(key, header, provider)
    }

    /**
     * 每趟前刷新：逐源读取 → 指纹比对 → 首次进基线 / 变更产更正行。
     * 源读取失败折叠为空（不阻断趟次）。
     */
    @Synchronized
    fun refresh() {
        val rendered = renderCurrent()
        val fingerprint = sha256(rendered)
        if (fingerprint == baselineFingerprint) return
        if (baselineFingerprint.isEmpty()) {
            // epoch 起点：全文进基线
            baseline = rendered
        } else {
            // 变更：冻结更正行 + 基线更新
            pendingNotices += "Standing instructions have changed — the environment section " +
                "below now reads:\n$rendered"
            baseline = rendered
        }
        baselineFingerprint = fingerprint
    }

    /** 基线（系统提示词渲染段；空 = 无常设指令）。 */
    fun baselineSection(): String? = baseline.takeIf { it.isNotBlank() }

    /** 取走待渲染更正行（一次一条——渲染为 System 消息后即消费）。 */
    @Synchronized
    fun consumePendingNotice(): String? =
        if (pendingNotices.isEmpty()) null else pendingNotices.removeFirst()

    /** epoch 推进（checkpoint 落位——更正行已进落账历史，基线即全部）。 */
    @Synchronized
    fun advanceEpoch() {
        pendingNotices.clear()
        baselineFingerprint = sha256(renderCurrent())
    }

    /** 强制重置（新会话）。 */
    @Synchronized
    fun reset() {
        baseline = ""
        baselineFingerprint = ""
        pendingNotices.clear()
    }

    /** 当前全量渲染（调试 / 快照）。 */
    @Synchronized
    fun renderCurrent(): String = buildString {
        for (source in sources) {
            val text = try {
                source.read()
            } catch (e: Exception) {
                null
            }
            if (text != null) {
                if (isNotEmpty()) append("\n\n")
                append("## ")
                append(source.header)
                append('\n')
                append(text.trim())
            }
        }
    }.trim()

    /** 日期行（内置源——epoch 内变更即更正"今天日期"）。 */
    fun dateSource(): NamedSource = NamedSource(
        key = "date",
        header = "Today",
        provider = { "Today's date: ${formatToday()}" }
    )

    // ── 内部 ──

    private fun formatToday(): String {
        val day = java.time.Instant.ofEpochMilli(clock.nowMillis())
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return day.toString()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
