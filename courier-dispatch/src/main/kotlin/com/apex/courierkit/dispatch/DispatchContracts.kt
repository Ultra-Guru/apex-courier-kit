package com.apex.courierkit.dispatch

import java.io.File

/**
 * # Dispatch Contracts — 信使线协议类型（库自有词汇，零宿主依赖）
 *
 * 信使引擎（Courier）与宿主之间的全部协议数据形状。命名取自古驿传体系：
 *
 * | 驿传词 | 引擎语义 |
 * |---|---|
 * | 收发匣（inbox） | 用户输入的 steer / queue / 控制件投递 |
 * | 趟次（trip） | 一个逻辑步 = 一条助手消息（可含多次物理尝试） |
 * | 勘合（permit） | 工具调用的通行证核验（allow / ask / deny 模式法则） |
 * | 示廊（bulletin） | 常设指令源的价值增量公告（epoch 基线 + 冻结更正行） |
 * | 档房（archive） | 行程日志的结构化压缩（checkpoint 卷宗） |
 * | 下站（outpost） | 子代理 = 子驿站会话（深度受限 / 可后台 / 完成回执） |
 *
 * 协议形状与宿主 `llm-adapter` 的 LlmMessage / LlmStreamChunk 族**逐字段对齐**
 * （适配器可 1:1 映射），但全部为库自有类型——循环语义演进不牵动宿主。
 */
sealed interface DispatchMessage {

    /** 系统段（人设基线 / 示廊基线）。 */
    data class System(val content: String) : DispatchMessage

    /** 用户消息（文本 + 可选图片）。 */
    data class User(
        val content: String,
        val images: List<DispatchImageContent> = emptyList()
    ) : DispatchMessage

    /** 助手消息（正文 + 原生工具调用 + 思考链全文）。 */
    data class Assistant(
        val content: String,
        val toolCalls: List<DispatchToolCall> = emptyList(),
        val reasoning: String = ""
    ) : DispatchMessage

    /** 工具结果（回执：callId + 模型面内容）。 */
    data class ToolResult(val toolCallId: String, val content: String) : DispatchMessage
}

/** 多模态图片（base64 内联 / 远程 URL 二选一，url 优先）。 */
data class DispatchImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "auto",
    val url: String? = null
)

/** 附件文件引用（路径上下文拼入用户文本，由模型用工具读取）。 */
data class DispatchFileRef(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long
)

/** 用户多模态输入。 */
data class DispatchUserInput(
    val text: String,
    val images: List<DispatchImageContent> = emptyList(),
    val files: List<DispatchFileRef> = emptyList()
)

/** 原生工具调用（arguments 为 JSON 字符串；index 为并行调用流式装配位）。 */
data class DispatchToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val index: Int = -1
)

/** 工具定义（parameters 为 JSON Schema 字符串）。 */
data class DispatchToolDefinition(
    val name: String,
    val description: String,
    val parameters: String
)

/** 工具执行流事件（与宿主 ToolStreamEvent 逐字段对齐）。 */
sealed interface DispatchToolStream {
    data class Output(val chunk: String) : DispatchToolStream
    data class Progress(val percent: Float?, val message: String?) : DispatchToolStream
    data class Complete(val output: String) : DispatchToolStream
    data class Error(val message: String) : DispatchToolStream
}

/** 用量统计。 */
data class DispatchUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/** 流式帧（与宿主 LlmStreamChunk 逐字段对齐）。 */
data class DispatchStreamChunk(
    val content: String? = null,
    val toolCalls: List<DispatchToolCall> = emptyList(),
    val isFinish: Boolean = false,
    val finishReason: String? = null,
    val reasoningContent: String? = null,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val usage: DispatchUsage? = null
)

/** 非流式响应。 */
data class DispatchModelResponse(
    val content: String?,
    val toolCalls: List<DispatchToolCall> = emptyList(),
    val usage: DispatchUsage? = null,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList()
)

/** 单请求工具选择（终趟禁用调用 / 强制调用；null = 不干预）。 */
sealed interface DispatchToolChoice {
    object Auto : DispatchToolChoice
    object Required : DispatchToolChoice
    data class Function(val name: String) : DispatchToolChoice
    object None : DispatchToolChoice
}

/** 收尾原因归一化（provider 原始串 → 引擎语义）。 */
object DispatchFinish {
    const val STOP = "stop"
    const val TOOL_CALLS = "tool_calls"
    const val LENGTH = "length"
    const val CONTENT_FILTER = "content_filter"

    /** 原始收尾串归一（未知值折叠 STOP——半截流由重试阶梯兜底）。 */
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER -> raw!!.trim().lowercase()
        "tool_call", "toolcalls", "function_call" -> TOOL_CALLS
        "max_tokens", "max_output_tokens", "max_completion_tokens" -> LENGTH
        "content-filter", "refusal" -> CONTENT_FILTER
        else -> STOP
    }
}

// ═══════════════════════════ 引擎事件（宿主可映射面）═══════════════════════════

/**
 * 信使引擎事件流（趟次步进机全程发射）。
 *
 * 与宿主 `AgentEvent` 一一对应（适配器 1:1 映射）；信使线特有过程
 * （收件投递 / 示廊更正 / 勘合核验）折叠进既有事件面：
 * - 示廊冻结更正行 → 进入下一趟上下文（无独立事件）；
 * - 勘合 ask → [UserInputRequired]（type=CONFIRMATION）；
 * - 勘合 deny / 规则失败 → 工具回执（success=false）；
 * - 重递调度 → [LlmRetryScheduled]。
 */
sealed interface DispatchEvent {

    /** 趟次开始（iteration = 趟序，1-based）。 */
    data class IterationStart(val iteration: Int) : DispatchEvent

    /** 思考链开始（含档位快照）。 */
    data class ThinkingStart(
        val iteration: Int,
        val level: DispatchThinkingLevel
    ) : DispatchEvent

    /** 思考链增量（逐 token）。 */
    data class ThinkingChunk(val text: String) : DispatchEvent

    /** 思考链收束（全文）。 */
    data class ThinkingComplete(val fullThought: String) : DispatchEvent

    /** 正文增量（逐 token）。 */
    data class ResponseChunk(val text: String) : DispatchEvent

    /** 正文收束（全文）。 */
    data class ResponseComplete(val fullText: String) : DispatchEvent

    /** 工具调用开始（参数完整后发射）。 */
    data class ToolCallStart(
        val callId: String,
        val toolName: String,
        val arguments: String
    ) : DispatchEvent

    /** 工具输出增量。 */
    data class ToolOutputChunk(val callId: String, val chunk: String) : DispatchEvent

    /** 工具进度。 */
    data class ToolProgress(
        val callId: String,
        val percent: Float?,
        val message: String?
    ) : DispatchEvent

    /** 工具调用完成（成败回执 + 全量输出）。 */
    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val arguments: String = "",
        val output: String,
        val fullOutput: String = "",
        val success: Boolean,
        val durationMs: Long
    ) : DispatchEvent

    /** 真实用量（每次模型请求至多一条）。 */
    data class UsageUpdated(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    ) : DispatchEvent

    /** 上下文已压缩（checkpoint 卷宗落位）。 */
    data class ContextCompressed(
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String,
        val summary: String,
        val messagesRemoved: Int,
        val messagesTruncated: Int = 0
    ) : DispatchEvent

    /** 计划生成（PLAN 趟的布样解析产物）。 */
    data class PlanGenerated(val plan: DispatchExecutionPlan) : DispatchEvent

    /** 计划等待确认。 */
    data class PlanAwaitingConfirmation(val plan: DispatchExecutionPlan) : DispatchEvent

    /** 计划已确认。 */
    data class PlanConfirmed(val plan: DispatchExecutionPlan) : DispatchEvent

    /** 需要用户输入（勘合 ask / 问答表单 / 计划门）。 */
    data class UserInputRequired(
        val prompt: String,
        val type: DispatchInputType = DispatchInputType.CONFIRMATION
    ) : DispatchEvent

    /** 用户输入等待超时（引擎按未决继续）。 */
    data object UserInputExpired : DispatchEvent

    /** 重递调度（阶梯等待后重试同趟）。 */
    data class LlmRetryScheduled(
        val attempt: Int,
        val delayMs: Long,
        val reason: String
    ) : DispatchEvent

    /** 错误（recoverable=false 终止本execute）。 */
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : DispatchEvent

    /** 全部完成（summary = 末条助手正文摘要）。 */
    data class Complete(
        val summary: String,
        val totalIterations: Int,
        val totalToolCalls: Int,
        val totalDurationMs: Long
    ) : DispatchEvent

    /** 被中止。 */
    data object Aborted : DispatchEvent
}

// ═══════════════════════════ 档位 / 模式 / 输入类型 ═══════════════════════════

/** 思考档位（八档——与织造线同口径，宿主六档折叠见适配器）。 */
enum class DispatchThinkingLevel {
    NONE, LIGHT, STANDARD, DEEP, MAXIMUM, ULTRACODE, APEXCODE, AUTO
}

/** 执行模式（BUILD 常规 / PLAN 只读规划）。 */
enum class DispatchMode {
    BUILD, PLAN
}

/** 用户输入类型。 */
enum class DispatchInputType {
    CONFIRMATION, TEXT, CHOICE
}

// ═══════════════════════════ 画像（agent 即配置）═══════════════════════════

/** 画像角色面（主驿 / 下站子代理 / 两栖 / 隐藏内部役）。 */
enum class DispatchRoleMode {
    /** 主役（用户可直接选定）。 */
    PRIMARY,

    /** 子役（仅供下站委派）。 */
    SUBAGENT,

    /** 两栖（可主可子）。 */
    ALL,

    /** 隐藏内部役（压缩 / 题匾等，不进选择面）。 */
    HIDDEN
}

/**
 * 画像（agent-as-config）：一次任务循环的人格 = 系统提示词 + 工具面 +
 * 勘合默认则例 + 趟次预算。宿主可注入自定义画像；缺省四役 + 两隐藏役。
 */
data class DispatchAgentRole(
    val id: String,
    val name: String,
    val description: String = "",
    /** 自有系统提示词（null = 用库缺省基线）。 */
    val system: String? = null,
    val mode: DispatchRoleMode = DispatchRoleMode.ALL,
    val steps: Int? = null,
    /** 勘合默认则例（展开在会话则例之前——会话可收窄）。 */
    val permissionRules: List<DispatchPermissionRule> = emptyList()
) {
    companion object {
        /** 构建者（缺省主役：全放行 + 环境文件读取勘合）。 */
        val BUILD = DispatchAgentRole(
            id = "build",
            name = "Builder",
            description = "Primary station role: reads, edits, runs commands and delivers working changes.",
            mode = DispatchRoleMode.PRIMARY,
            steps = 50,
            permissionRules = listOf(
                DispatchPermissionRule("*", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("read", "*.env", DispatchRuleEffect.ASK),
                DispatchPermissionRule("read", "*.env.*", DispatchRuleEffect.ASK)
            )
        )

        /** 规划者（只读主役：编辑类一律拒付，出计划不出改）。 */
        val PLAN = DispatchAgentRole(
            id = "plan",
            name = "Planner",
            description = "Read-only primary role: explores the workspace and produces a step plan.",
            mode = DispatchRoleMode.PRIMARY,
            steps = 50,
            permissionRules = listOf(
                DispatchPermissionRule("edit", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("write", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("*", "*", DispatchRuleEffect.ALLOW)
            )
        )

        /** 通用子役（并行杂务：无问答、无再委派）。 */
        val GENERAL = DispatchAgentRole(
            id = "general",
            name = "General Hand",
            description = "Sub-station role for parallel chores; cannot ask questions or spawn further couriers.",
            mode = DispatchRoleMode.SUBAGENT,
            steps = 30,
            permissionRules = listOf(
                DispatchPermissionRule("ask", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("task", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("*", "*", DispatchRuleEffect.ALLOW)
            )
        )

        /** 探查子役（只读检索专精）。 */
        val EXPLORE = DispatchAgentRole(
            id = "explore",
            name = "Scout",
            description = "Read-only sub-station role specialized in search and codebase reconnaissance.",
            mode = DispatchRoleMode.SUBAGENT,
            steps = 30,
            permissionRules = listOf(
                DispatchPermissionRule("*", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("read", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("glob", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("grep", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("search", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "ls*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "cat*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "head*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "tail*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "find*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "rg*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "git status*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "git log*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "git diff*", DispatchRuleEffect.ALLOW)
            )
        )

        /** 档房役（隐藏：压缩摘要专用）。 */
        val COMPACTION = DispatchAgentRole(
            id = "compaction",
            name = "Archivist",
            description = "Hidden role that condenses trip logs into checkpoint dossiers.",
            mode = DispatchRoleMode.HIDDEN
        )

        /** 题匾役（隐藏：会话命名专用）。 */
        val TITLE = DispatchAgentRole(
            id = "title",
            name = "Titler",
            description = "Hidden role that drafts a short station title for the session.",
            mode = DispatchRoleMode.HIDDEN
        )

        /** 缺省画像集（宿主目录为空时的全集）。 */
        val Builtins: List<DispatchAgentRole> =
            listOf(BUILD, PLAN, GENERAL, EXPLORE, COMPACTION, TITLE)
    }
}

// ═══════════════════════════ 勘合（权限法则）═══════════════════════════

/** 通行效应（三态：放行 / 拒付 / 勘验）。 */
enum class DispatchRuleEffect {
    ALLOW, DENY, ASK
}

/**
 * 通行则例（action × resource 通配模式 × 效应）。
 *
 * resource 支持 `*`（任意串）与 `?`（单字符）通配；其余字符按字面匹配；
 * `\` 折叠为 `/`。评估时**后例覆盖前例**（findLast 优先）——画像给底、
 * 会话收窄、勘合记忆最后落位。
 */
data class DispatchPermissionRule(
    val action: String,
    val resource: String,
    val effect: DispatchRuleEffect
)

/** 勘合快照（宿主设置层实时通道：画像外则会话级则例 + 已记忆放行）。 */
data class DispatchPermissionSnapshot(
    val rules: List<DispatchPermissionRule> = emptyList(),
    val commandRules: List<DispatchPermissionRule> = emptyList()
) {
    companion object {
        val NONE = DispatchPermissionSnapshot()
    }
}

// ═══════════════════════════ 行程清单 / 计划 ═══════════════════════════

/** 行程清单条目状态。 */
enum class DispatchTodoStatus {
    PENDING, IN_PROGRESS, DONE
}

/** 行程清单条目（courier_todos 工具维护，非空时随趟次注入上下文）。 */
data class DispatchTodoItem(
    val content: String,
    val status: DispatchTodoStatus = DispatchTodoStatus.PENDING,
    val activeForm: String = ""
)

/** 计划步。 */
data class DispatchPlanStep(
    val index: Int,
    val description: String,
    val toolName: String? = null,
    val estimatedArgs: String? = null,
    val dependsOn: List<Int> = emptyList()
)

/** 风险等级。 */
enum class DispatchRiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

/** 执行计划（PLAN 趟布样解析产物 + 人控门确认对象）。 */
data class DispatchExecutionPlan(
    val goal: String,
    val steps: List<DispatchPlanStep>,
    val estimatedToolCalls: Int,
    val riskLevel: DispatchRiskLevel,
    val reasoning: String
)

// ═══════════════════════════ 下站（子代理）═══════════════════════════

/** 下站回执状态。 */
enum class DispatchChildStatus {
    RUNNING, COMPLETED, FAILED
}

/** 工作区快照（引擎侧持有的当前驿站位置）。 */
data class DispatchWorkspaceInfo(
    val id: String,
    val name: String,
    val root: File?
)
