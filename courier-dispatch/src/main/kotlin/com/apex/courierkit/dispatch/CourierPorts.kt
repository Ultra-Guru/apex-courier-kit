package com.apex.courierkit.dispatch

import kotlinx.coroutines.flow.Flow

/**
 * # Courier Ports — 信使引擎与宿主之间的端口契约
 *
 * 趟次步进机内核**只面向端口编程**：宿主侧由引擎适配器把 ModelRuntime /
 * ToolRegistry / CodeConversationMemory / AppLogger 等接进来——循环语义
 * 零宿主依赖（与 loom-weave / standard-loop 同款端口-适配器纪律，协议
 * 与循环行为是信使线自己的）。
 *
 * | 端口 | 宿主实现 | 职责 |
 * |---|---|---|
 * | [CourierModelClient] | ModelRuntime 适配 | 流式 / 非流式 LLM 调用（含单请求工具选择） |
 * | [CourierToolSource] | ToolRegistry 快照 | 工具目录（定义列表） |
 * | [CourierToolRunner] | 仅环境门执行器 | 工具流式执行 |
 * | [CourierMemory] | CodeConversationMemory 适配 | 会话持久化 |
 * | [CourierLogger] | AppLogger 适配 | 日志 |
 * | [CourierFailureClassifier] | ModelRuntimeException 映射 | 失败分类（重递策略） |
 * | [CourierEnvironmentSource] | 宿主环境行 | env 段数据 |
 * | [CourierModelInfoSource] | PRIMARY 解析链 | 模型 id + 真实窗口 + 输出上限 |
 * | [CourierTokenEstimator] | 文本加速核 / 启发式 | token 估算 |
 * | [CourierWorkspaceContextSource] | 工作区上下文段 | 工作区 / 规则段 |
 * | [CourierAgentCatalog] | 宿主画像目录（可选） | 画像登记与解析 |
 * | [CourierSkillCatalog] | 技能仓库桥（可选） | 技能索引（示廊清单 + 委派装载） |
 * | [CourierCommandProbe] | 终端工具参数探针（可选） | 从工具参数提取命令串（勘合拆解用） |
 * | [CourierPermissionSnapshotSource] | 设置层实时通道 | 会话级则例快照 |
 * | [CourierClock] | 系统时钟（可注入假钟） | 重递计时 / 日期行 |
 */
interface CourierModelClient {

    /**
     * 流式对话（主循环 / 下站 / 题匾共用；contextTag 区分流量来源）。
     *
     * @param toolChoice 单请求工具选择（null = 不干预；终趟用
     *   [DispatchToolChoice.None] 关调用但保留定义——缓存前缀不失效）
     */
    fun chatStream(
        contextTag: String,
        messages: List<DispatchMessage>,
        tools: List<DispatchToolDefinition>,
        temperature: Float,
        maxTokens: Int,
        toolChoice: DispatchToolChoice? = null
    ): Flow<DispatchStreamChunk>

    /** 非流式对话（档房摘要 / 题匾用）。 */
    suspend fun chat(
        contextTag: String,
        messages: List<DispatchMessage>,
        temperature: Float,
        maxTokens: Int
    ): DispatchModelResponse
}

/** 工具目录源（引擎组装工具面时拉取快照）。 */
fun interface CourierToolSource {
    fun definitions(): List<DispatchToolDefinition>
}

/** 工具执行端口（流式——进度与增量输出全程可见）。 */
fun interface CourierToolRunner {
    fun executeStream(toolId: String, arguments: String): Flow<DispatchToolStream>
}

/** 会话记忆端口（null = 下站/测试：纯内存会话不落盘，由引擎层处理）。 */
interface CourierMemory {
    fun load(): List<DispatchMessage>
    fun append(message: DispatchMessage)
    fun save(messages: List<DispatchMessage>)
    fun clear()
    fun count(): Int
    fun bindWorkspace(workspaceId: String)
}

/** 日志端口。 */
interface CourierLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable?)

    companion object {
        /** 静默实现（测试 / 不关心日志的宿主）。 */
        val Silent: CourierLogger = object : CourierLogger {
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String) = Unit
            override fun error(tag: String, message: String, throwable: Throwable?) = Unit
        }
    }
}

/** 失败分类（决定重递阶梯走向）。 */
enum class CourierFailureKind {
    /** 瞬时（网络抖动 / 限流 / 超时）——阶梯重递。 */
    TRANSIENT,

    /** 请求级拒绝（鉴权 / 配置 / 参数）——终止。 */
    REQUEST_REJECTED,

    /** 上下文溢出——先压缩再重试一次。 */
    CONTEXT_OVERFLOW,

    /** 未分类——保守终止。 */
    UNCLASSIFIED
}

/** 失败分类器端口。 */
fun interface CourierFailureClassifier {
    fun classify(t: Throwable): CourierFailureKind

    companion object {
        /**
         * 通用缺省：消息文本嗅探（溢出特征词）→ IOException（瞬时）→
         * 异常类名启发（限流/超时/不可用 → 瞬时；鉴权/配置/拒绝 → 请求级）。
         * 宿主有自家异常体系时应注入精确分类器。
         */
        val Default: CourierFailureClassifier = CourierFailureClassifier { t ->
            val text = (t.message ?: "").lowercase()
            if (OVERFLOW_MARKERS.any { text.contains(it) }) {
                CourierFailureKind.CONTEXT_OVERFLOW
            } else if (t is java.io.IOException) {
                CourierFailureKind.TRANSIENT
            } else {
                val name = t::class.simpleName?.lowercase() ?: ""
                when {
                    name.contains("ratelimited") || name.contains("timeout") ||
                        name.contains("unavailable") || name.contains("responseinvalid") ->
                        CourierFailureKind.TRANSIENT

                    name.contains("rejected") || name.contains("configuration") ||
                        name.contains("authentication") -> CourierFailureKind.REQUEST_REJECTED

                    else -> CourierFailureKind.UNCLASSIFIED
                }
            }
        }

        private val OVERFLOW_MARKERS = listOf(
            "context length", "context window", "maximum context",
            "too long", "prompt is too long", "input tokens exceed",
            "token limit", "context_length_exceeded", "maximum number of tokens"
        )
    }
}

/** token 估算端口（宿主可注入加速核；缺省启发式由引擎侧提供）。 */
fun interface CourierTokenEstimator {
    fun estimateTokens(text: String): Int
}

/** 环境信息（env 段数据）。 */
data class CourierEnvironmentInfo(
    val platformText: String,
    val workdirLabel: String? = null,
    val isGitRepo: Boolean? = null
)

/** 环境信息源端口。 */
fun interface CourierEnvironmentSource {
    fun current(): CourierEnvironmentInfo
}

/** 模型信息（真实上下文窗口 + 输出上限——箱位预算消费）。 */
data class CourierModelInfo(
    val modelId: String,
    val contextWindow: Int,
    val outputLimit: Int = 32768
)

/** 模型信息源端口。 */
fun interface CourierModelInfoSource {
    fun current(): CourierModelInfo?
}

/** 工作区上下文段源端口（工作区 / 全局规则 / 项目规则三段）。 */
interface CourierWorkspaceContextSource {
    fun workspaceSection(): String?
    fun globalRulesSection(): String?
    fun projectRulesSection(): String?
}

/** 画像目录端口（null 目录 → 库缺省画像集）。 */
fun interface CourierAgentCatalog {
    fun roles(): List<DispatchAgentRole>

    companion object {
        val Builtin: CourierAgentCatalog =
            CourierAgentCatalog { DispatchAgentRole.Builtins }
    }
}

/** 技能索引条目（示廊清单行）。 */
data class CourierSkillDigest(
    val id: String,
    val title: String
)

/** 技能装载体（委派时的指令注入）。 */
data class CourierSkillPayload(
    val id: String,
    val title: String,
    val instructions: String
)

/** 技能目录端口（宿主技能仓库桥；空 = 无技能面）。 */
interface CourierSkillCatalog {
    fun list(): List<CourierSkillDigest>
    fun load(id: String): CourierSkillPayload?

    companion object {
        val Empty: CourierSkillCatalog = object : CourierSkillCatalog {
            override fun list(): List<CourierSkillDigest> = emptyList()
            override fun load(id: String): CourierSkillPayload? = null
        }
    }
}

/**
 * 终端命令探针端口：从工具参数 JSON 提取待执行的命令串（勘合拆解器
 * 对复合命令逐段核验用）。返回 null = 该工具/参数不含命令（按工具面
 * 整体核验）。
 */
fun interface CourierCommandProbe {
    fun commandOf(toolId: String, arguments: String): String?

    companion object {
        val None: CourierCommandProbe = CourierCommandProbe { _, _ -> null }
    }
}

/** 勘合快照源端口（设置层实时通道——每次核验前拉取）。 */
fun interface CourierPermissionSnapshotSource {
    fun snapshot(): DispatchPermissionSnapshot

    companion object {
        val NONE: CourierPermissionSnapshotSource =
            CourierPermissionSnapshotSource { DispatchPermissionSnapshot.NONE }
    }
}

/** 时钟端口（重递计时 / 日期行——测试注入假钟）。 */
interface CourierClock {
    fun nowMillis(): Long

    companion object {
        val System: CourierClock = object : CourierClock {
            override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
        }
    }
}

/** 通用 token 估算缺省实现（启发式：CJK 字符 1:1，其余 4 字符 1 token）。 */
object CourierHeuristicEstimator : CourierTokenEstimator {
    override fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var rest = 0
        for (c in text) {
            if (c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF ||
                c.code in 0xFF00..0xFFEF
            ) {
                cjk++
            } else {
                rest++
            }
        }
        return cjk + (rest + 3) / 4
    }
}
