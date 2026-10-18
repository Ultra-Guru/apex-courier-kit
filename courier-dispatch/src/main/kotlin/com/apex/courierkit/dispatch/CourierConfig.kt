package com.apex.courierkit.dispatch

/**
 * # Courier Config — 信使引擎预算配置
 *
 * 全部字段带缺省值；[sanitized] 把脏值钳回安全界（**零 require**——
 * 配置错误折叠为缺省，绝不击穿构造）。
 *
 * | 组 | 字段 | 缺省 | 语义 |
 * |---|---|---|---|
 * | 趟次 | [maxTrips] | 50 | 单次 execute 的逻辑步上限（超限注入终趟提示并关调用） |
 * | 压缩 | [keepTokens] | 15000 | checkpoint 后逐字保留的近期窗口 |
 * | 压缩 | [compactBufferFraction] / [compactBufferFloor] | 0.10 / 16384 | 触发线 = 窗口 − max(10%, 16k) |
 * | 回执 | [outputLineLimit] / [outputByteLimit] | 2000 / 51200 | 工具回执边界（头部保留 + 截尾注记） |
 * | 重递 | [retryMaxAttempts] | 10 | 同趟物理重试上限 |
 * | 重递 | [retryBaseDelayMs] / [retryCapDelayMs] | 2000 / 10000 | 抖动指数阶梯（2s 翻倍封顶 10s） |
 * | 重递 | [retryAfterCapMs] | 900000 | provider retryAfter 上限（15 分钟） |
 * | 续跑 | [outputLimitContinues] | 2 | 输出截断续跑上限（连续 3 条截断即失败） |
 * | 下站 | [subagentDepthLimit] | 1 | 子驿站递归深度 |
 * | 并行 | [parallelToolCalls] | true | 同趟多工具调用并行执行 |
 * | 收发 | [inboxQueueBatch] | 1 | 空闲边界一次投递的 queue 条数 |
 * | 杂项 | [titleGeneration] | false | 题匾役（宿主自管标题时关闭） |
 * | 杂项 | [imageTokenCost] | 1500 | 单图 token 估算 |
 */
data class CourierConfig(
    // ── 趟次 ──
    val maxTrips: Int = 50,
    /** 终趟提示注入前的软警告线（软线不终止，仅提示收敛）。 */
    val tripWarnThreshold: Int = 40,

    // ── 档房压缩 ──
    val keepTokens: Int = 15_000,
    val compactBufferFraction: Double = 0.10,
    val compactBufferFloorTokens: Int = 16_384,
    /** 摘要请求输出上限（硬顶）。 */
    val summaryMaxTokens: Int = 32_000,

    // ── 工具回执边界 ──
    val outputLineLimit: Int = 2_000,
    val outputByteLimit: Int = 50 * 1024,
    /** 输入回显上限（坏参数回执中原始 JSON 摘录长度）。 */
    val inputEchoLimit: Int = 2_048,

    // ── 重递阶梯 ──
    val retryMaxAttempts: Int = 10,
    val retryBaseDelayMs: Long = 2_000,
    val retryCapDelayMs: Long = 10_000,
    val retryAfterCapMs: Long = 900_000,
    /** 传输类超时失败的重试上限（独立更紧的口子）。 */
    val retryTransportMaxAttempts: Int = 3,

    // ── 续跑 ──
    val outputLimitContinues: Int = 2,

    // ── 下站 ──
    val subagentDepthLimit: Int = 1,

    // ── 工具并行 ──
    val parallelToolCalls: Boolean = true,
    /** 同趟并行工具纤程上限（0 = 不限）。 */
    val maxParallelTools: Int = 0,

    // ── 收发匣 ──
    val inboxQueueBatch: Int = 1,

    // ── 题匾 ──
    val titleGeneration: Boolean = false,
    val titleMaxChars: Int = 50,
    val titleHistoryChars: Int = 8_000,

    // ── 箱位预算 ──
    /** 输出 token 硬顶（模型目录缺省输出上限）。 */
    val outputTokenFallback: Int = 32_768,
    val outputTokenHardCap: Int = 262_144,
    val outputTokenFloor: Int = 1_024,
    /** 估算放大系数（字符估算 → 保守请求预算）。 */
    val estimateInflation: Double = 1.15,
    val imageTokenCost: Int = 1_500,

    // ── 流量标签（宿主侧路由 SUMMARY 角色用）──
    val loopContextTag: String = "courier_loop",
    val summaryContextTag: String = "courier_summary",
    val childContextTag: String = "courier_child_loop",
    val titleContextTag: String = "courier_title"
) {
    /**
     * 脏值钳位（零 require）：
     * 趟次 ≥1；压缩窗口 ≥2000；阶梯参数为正；深度 ≥0；边界 ≥1；
     * 分数折叠到 0.02..0.90；标签空白回缺省。
     */
    fun sanitized(): CourierConfig = CourierConfig(
        maxTrips = maxTrips.coerceAtLeast(1),
        tripWarnThreshold = tripWarnThreshold.coerceIn(1, maxOf(1, maxTrips)),
        keepTokens = keepTokens.coerceAtLeast(2_000),
        compactBufferFraction = compactBufferFraction.coerceIn(0.02, 0.90),
        compactBufferFloorTokens = compactBufferFloorTokens.coerceAtLeast(1_024),
        summaryMaxTokens = summaryMaxTokens.coerceIn(1_024, outputTokenHardCap),
        outputLineLimit = outputLineLimit.coerceAtLeast(1),
        outputByteLimit = outputByteLimit.coerceAtLeast(256),
        inputEchoLimit = inputEchoLimit.coerceAtLeast(256),
        retryMaxAttempts = retryMaxAttempts.coerceIn(0, 25),
        retryBaseDelayMs = retryBaseDelayMs.coerceAtLeast(100),
        retryCapDelayMs = retryCapDelayMs.coerceAtLeast(retryBaseDelayMs),
        retryAfterCapMs = retryAfterCapMs.coerceAtLeast(retryCapDelayMs),
        retryTransportMaxAttempts = retryTransportMaxAttempts.coerceIn(0, maxOf(0, retryMaxAttempts)),
        outputLimitContinues = outputLimitContinues.coerceIn(0, 8),
        subagentDepthLimit = subagentDepthLimit.coerceAtLeast(0),
        parallelToolCalls = parallelToolCalls,
        maxParallelTools = maxParallelTools.coerceAtLeast(0),
        inboxQueueBatch = inboxQueueBatch.coerceIn(1, 16),
        titleGeneration = titleGeneration,
        titleMaxChars = titleMaxChars.coerceIn(8, 200),
        titleHistoryChars = titleHistoryChars.coerceAtLeast(1_000),
        outputTokenFallback = outputTokenFallback.coerceIn(1_024, outputTokenHardCap),
        outputTokenHardCap = outputTokenHardCap.coerceAtLeast(outputTokenFallback),
        outputTokenFloor = outputTokenFloor.coerceIn(1, 4_096),
        estimateInflation = estimateInflation.coerceIn(1.0, 3.0),
        imageTokenCost = imageTokenCost.coerceAtLeast(0),
        loopContextTag = loopContextTag.takeIf { it.isNotBlank() } ?: "courier_loop",
        summaryContextTag = summaryContextTag.takeIf { it.isNotBlank() } ?: "courier_summary",
        childContextTag = childContextTag.takeIf { it.isNotBlank() } ?: "courier_child_loop",
        titleContextTag = titleContextTag.takeIf { it.isNotBlank() } ?: "courier_title"
    )

    /** 档房触发线：窗口 − max(比例缓冲, 地板缓冲)。窗口未知返回 null（永不自动触发）。 */
    fun compactCeiling(contextWindow: Int): Int? {
        if (contextWindow <= 0) return null
        val buffer = maxOf(
            (contextWindow * compactBufferFraction).toInt(),
            compactBufferFloorTokens
        )
        return (contextWindow - buffer).coerceAtLeast(1_000)
    }
}
