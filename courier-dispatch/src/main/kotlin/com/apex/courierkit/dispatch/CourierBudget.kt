package com.apex.courierkit.dispatch

/**
 * # Courier Budget — 箱位预算（请求输出上限拟合）
 *
 * 输出 `maxTokens` = **目录上限 ∧ 硬顶**，再向**窗口余量**拟合：
 * `window − 实测输入 − 1.15 × 待发估算`（≥ 地板 1024）。
 * 窗口未知 → 目录上限直接生效。
 */
object CourierBudget {

    /**
     * 请求输出上限。
     *
     * @param config 预算配置
     * @param modelInfo 模型信息（null → 目录回退值）
     * @param measuredInputTokens 上次同源请求实测输入 token（0 = 无实测）
     * @param pendingEstimate 本趟系统段 + 待发内容估算 token
     */
    fun outputMaxTokens(
        config: CourierConfig,
        modelInfo: CourierModelInfo?,
        measuredInputTokens: Int,
        pendingEstimate: Int
    ): Int {
        val catalogCap = modelInfo?.outputLimit ?: config.outputTokenFallback
        val capped = minOf(catalogCap, config.outputTokenHardCap)
        val window = modelInfo?.contextWindow ?: 0
        if (window <= 0) return capped
        val inflated = (pendingEstimate * config.estimateInflation).toInt()
        val headroom = window - measuredInputTokens - inflated
        if (headroom <= 0) return config.outputTokenFloor
        return headroom.coerceIn(config.outputTokenFloor, capped)
    }

    /** 待发估算（字符估算 × 放大系数；图按 [CourierConfig.imageTokenCost]）。 */
    fun pendingEstimate(
        estimator: CourierTokenEstimator,
        config: CourierConfig,
        texts: List<String>,
        imageCount: Int = 0
    ): Int {
        val text = texts.sumOf { estimator.estimateTokens(it) }
        return ((text + imageCount * config.imageTokenCost) * config.estimateInflation).toInt()
    }
}
