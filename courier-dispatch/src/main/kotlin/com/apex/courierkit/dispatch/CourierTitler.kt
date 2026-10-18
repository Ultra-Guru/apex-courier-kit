package com.apex.courierkit.dispatch

/**
 * # Courier Titler — 题匾（会话命名，可选役）
 *
 * 首次投递可见输入后**一次性**触发（FiberMap 式去重——只对仍未命名的
 * 根会话）；小输出请求；同语言；超长截断；CAS 落位（结果回调由引擎
 * 持有，库内不持久化标题——宿主有自己的会话命名体系）。
 *
 * 缺省关闭（[CourierConfig.titleGeneration] = false）——宿主开启才有意义。
 */
class CourierTitler(
    private val config: CourierConfig,
    private val modelClient: CourierModelClient,
    private val logger: CourierLogger
) {

    @Volatile
    private var fired = false

    /** 是否已触发过（幂等闸）。 */
    fun hasFired(): Boolean = fired

    /** 重置（新会话）。 */
    fun reset() {
        fired = false
    }

    /**
     * 生成标题（fire-and-forget 语义由调用方协程承载——本函数为挂起调用）。
     *
     * @param historyExcerpt 会话历史摘录（调用方截取）
     * @return 标题（失败 / 空响应 → null）
     */
    suspend fun generate(historyExcerpt: String): String? {
        if (fired) return null
        fired = true
        return try {
            val response = modelClient.chat(
                contextTag = config.titleContextTag,
                messages = listOf(
                    DispatchMessage.System(content = CourierPrompts.titlerPersona()),
                    DispatchMessage.User(content = CourierPrompts.titlePrompt(historyExcerpt))
                ),
                temperature = 0.3f,
                maxTokens = 128
            )
            val title = response.content
                ?.trim()
                ?.replace("^\"|\"$".toRegex(), "")
                ?.take(config.titleMaxChars)
                ?.takeIf { it.isNotBlank() }
            if (title == null) {
                logger.info(TAG, "title response empty — skipped")
            }
            title
        } catch (t: Throwable) {
            logger.warn(TAG, "title generation failed: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Titler"
    }
}
