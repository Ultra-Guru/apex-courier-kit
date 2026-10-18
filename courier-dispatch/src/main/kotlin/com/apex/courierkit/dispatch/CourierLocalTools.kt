package com.apex.courierkit.dispatch

import kotlinx.coroutines.CompletableDeferred

/**
 * # Courier Todo Board — 行程清单簿
 *
 * `courier_todos` 工具维护的会话级清单态。**非空时随每趟注入上下文**
 * （当前行高亮），全部完成自动清空。清单态不单独持久化——历史中的
 * 工具回执即日志（重启后从最近一次清单回执恢复）。
 */
class CourierTodoBoard {

    @Volatile
    private var items: List<DispatchTodoItem> = emptyList()

    /** 整表替换（工具写入口径）。 */
    fun replace(next: List<DispatchTodoItem>) {
        items = next
    }

    /** 从历史回执恢复（会话装载口径——null 保持现状）。 */
    fun restore(lastKnown: List<DispatchTodoItem>?) {
        if (lastKnown != null) items = lastKnown
    }

    fun current(): List<DispatchTodoItem> = items

    fun clear() {
        items = emptyList()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    /** 渲染（注入上下文 / 工具回执共用口径）。 */
    fun render(): String {
        if (items.isEmpty()) return "Trip checklist: (empty)"
        return buildString {
            appendLine("Trip checklist:")
            for (item in items) {
                val mark = when (item.status) {
                    DispatchTodoStatus.DONE -> "[x]"
                    DispatchTodoStatus.IN_PROGRESS -> "[>]"
                    DispatchTodoStatus.PENDING -> "[ ]"
                }
                val active = if (item.activeForm.isNotBlank() &&
                    item.status == DispatchTodoStatus.IN_PROGRESS
                ) {
                    " — ${item.activeForm}"
                } else {
                    ""
                }
                appendLine("- $mark ${item.content}$active")
            }
        }.trimEnd()
    }

    /** 解析历史回执行（恢复口径）：`- [x] 文本` 形态。 */
    fun parseRendered(text: String): List<DispatchTodoItem> {
        val parsed = ArrayList<DispatchTodoItem>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            val m = CHECKLIST_LINE.matchEntire(trimmed) ?: continue
            val (mark, content) = m.destructured
            parsed += DispatchTodoItem(
                content = content.trim(),
                status = when (mark) {
                    "[x]" -> DispatchTodoStatus.DONE
                    "[>]" -> DispatchTodoStatus.IN_PROGRESS
                    else -> DispatchTodoStatus.PENDING
                }
            )
        }
        return parsed
    }

    private companion object {
        val CHECKLIST_LINE = Regex("""- (\[[ x>]\]) (.+)""")
    }
}

/**
 * # Courier Question Box — 问答案
 *
 * `courier_question` 工具的悬置问答：出票 → 引擎发
 * [DispatchEvent.UserInputRequired]（TEXT/CHOICE）→ 用户答复回流
 * [answer] → 工具取回答案作为回执。
 */
class CourierQuestionBox {

    class PendingQuestion internal constructor(
        val ticket: String,
        val render: String,
        internal val deferred: CompletableDeferred<String?>
    )

    @Volatile
    private var asked = false

    private var pendingTicket: PendingTicketSlot? = null

    private class PendingTicketSlot(val question: PendingQuestion)

    /** 当前会话是否已用过问答（子役无问答权——画像则例约束的兜底）。 */
    fun hasAsked(): Boolean = asked

    /** 出票并等待（工具协程挂起于此）。 */
    suspend fun ask(ticket: String, render: String): String? {
        asked = true
        val question = PendingQuestion(ticket, render, CompletableDeferred())
        pendingTicket = PendingTicketSlot(question)
        try {
            return question.deferred.await()
        } finally {
            pendingTicket = null
        }
    }

    /** 用户答复（true = 已兑现；false = 无悬置票）。 */
    fun answer(reply: String): Boolean {
        val slot = pendingTicket ?: return false
        return slot.question.deferred.complete(reply)
    }

    /** 会话收口：全部悬置问答按取消兑现（回执 null）。 */
    fun cancelAll() {
        pendingTicket?.question?.deferred?.complete(null)
        pendingTicket = null
    }

    /** 悬置票（只读）。 */
    fun current(): PendingQuestion? = pendingTicket?.question
}

/**
 * # Courier Task Launcher — 下站委派端口
 *
 * `courier_task` 工具的执行端口（引擎装配时由 [CourierOutpost] 实现——
 * 子驿站会话由引擎层全权管理，工具层只见请求/结果）。
 */
fun interface CourierTaskLauncher {

    /** 委派请求。 */
    data class TaskRequest(
        val roleId: String,
        val description: String,
        val prompt: String,
        val background: Boolean
    )

    /** 委派结果（stationId = 子站句柄——可用于续派）。 */
    data class TaskResult(
        val stationId: String,
        val status: DispatchChildStatus,
        val output: String
    )

    suspend fun launch(request: TaskRequest): TaskResult

    companion object {
        /** 未装配兜底（引擎装配前的占位——回执直接说明不可用）。 */
        val Unavailable: CourierTaskLauncher =
            CourierTaskLauncher { request ->
                TaskResult(
                    stationId = "unavailable",
                    status = DispatchChildStatus.FAILED,
                    output = "Sub-station dispatch is not available in this station."
                )
            }
    }
}

/**
 * # Courier Local Tools — 信使线本地工具面
 *
 * 三个改变**思考循环形态**的本地工具（其余工具面 = 宿主注册表）：
 *
 * | 工具 | 范式角色 |
 * |---|---|
 * | `courier_todos` | 行程清单——结构化步进状态，非空时随趟注入（模型每趟都看见清单） |
 * | `courier_question` | 问答表单——需求歧义时向用户提问（表单渲染 + 答案回执） |
 * | `courier_task` | 下站委派——子代理 = 子驿站会话（前台等待 / 后台完成即合成输入唤醒） |
 */
class CourierLocalTools(
    private val config: CourierConfig,
    private val runtime: CourierToolRuntime,
    private val todoBoard: CourierTodoBoard,
    private val questionBox: CourierQuestionBox,
    private val taskLauncher: CourierTaskLauncher
) {

    companion object {
        const val TODOS = "courier_todos"
        const val QUESTION = "courier_question"
        const val TASK = "courier_task"

        val IDS = setOf(TODOS, QUESTION, TASK)
    }

    /** 本地工具定义面（随每趟并入工具目录）。 */
    fun definitions(): List<DispatchToolDefinition> = listOf(
        DispatchToolDefinition(
            name = TODOS,
            description = "Maintain the trip checklist (todo list) for this task. Use it to " +
                "break work into steps and track progress; keep exactly one item in_progress " +
                "at a time. Call with no arguments to read the current list.",
            parameters = TODOS_SCHEMA
        ),
        DispatchToolDefinition(
            name = QUESTION,
            description = "Ask the station user clarifying questions when requirements are " +
                "ambiguous and a wrong guess would waste work. Renders a short form; answers " +
                "come back as the result. Do not use for confirmations the permit gate " +
                "already handles.",
            parameters = QUESTION_SCHEMA
        ),
        DispatchToolDefinition(
            name = TASK,
            description = "Dispatch a sub-station courier (child agent session) for a " +
                "self-contained subtask with its own context window. Foreground waits for " +
                "the result; background=true returns immediately and this station is woken " +
                "with the result when it finishes — do not poll. Give the courier a " +
                "complete, self-sufficient prompt.",
            parameters = TASK_SCHEMA
        )
    )

    fun isLocal(toolId: String): Boolean = toolId in IDS

    /**
     * 执行本地工具。[emit] 供问答表单发 [DispatchEvent.UserInputRequired]。
     */
    suspend fun execute(
        toolId: String,
        arguments: String,
        emit: suspend (DispatchEvent) -> Unit
    ): CourierToolRuntime.Outcome = when (toolId) {
        TODOS -> executeTodos(arguments)
        QUESTION -> executeQuestion(arguments, emit)
        TASK -> executeTask(arguments)
        else -> runtime.invalidRecept("Unknown local dispatch: $toolId")
    }

    // ── courier_todos ──

    private fun executeTodos(arguments: String): CourierToolRuntime.Outcome {
        if (arguments.isBlank() || arguments == "{}") {
            return runtime.outcome(
                machine = null,
                model = todoBoard.render()
            )
        }
        val decoded = runtime.decode(
            toolName = TODOS,
            arguments = arguments,
            required = emptySet(),
            optional = setOf("todos")
        )
        return when (decoded) {
            is CourierToolRuntime.Decoded.Invalid -> runtime.invalidRecept(decoded.modelRecept)

            is CourierToolRuntime.Decoded.Valid -> {
                val rawTodos = decoded.raw["todos"] ?: decoded.fields["todos"]
                if (rawTodos.isNullOrBlank() || rawTodos == "[]") {
                    return runtime.outcome(machine = null, model = todoBoard.render())
                }
                val parsed = parseTodoItems(rawTodos)
                if (parsed == null) {
                    runtime.invalidRecept(
                        "Invalid dispatch for $TODOS: field \"todos\" " +
                            "must be an array of {content, status, activeForm} items"
                    )
                } else {
                    todoBoard.replace(parsed)
                    runtime.outcome(
                        machine = null,
                        model = todoBoard.render(),
                        metadata = """{"todos":${parsed.size}}"""
                    )
                }
            }
        }
    }

    private fun parseTodoItems(raw: String): List<DispatchTodoItem>? {
        return try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            if (element !is kotlinx.serialization.json.JsonArray) return null
            element.mapNotNull { entry ->
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: return null
                val content = (obj["content"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: return null
                val statusText = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.lowercase() ?: "pending"
                val active = (obj["activeForm"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: ""
                DispatchTodoItem(
                    content = content,
                    status = when (statusText) {
                        "done", "completed" -> DispatchTodoStatus.DONE
                        "in_progress", "in-progress", "active" -> DispatchTodoStatus.IN_PROGRESS
                        else -> DispatchTodoStatus.PENDING
                    },
                    activeForm = active
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── courier_question ──

    private suspend fun executeQuestion(
        arguments: String,
        emit: suspend (DispatchEvent) -> Unit
    ): CourierToolRuntime.Outcome {
        val decoded = runtime.decode(
            toolName = QUESTION,
            arguments = arguments,
            required = setOf("questions"),
            optional = emptySet()
        )
        if (decoded is CourierToolRuntime.Decoded.Invalid) {
            return runtime.invalidRecept(decoded.modelRecept)
        }
        val valid = decoded as CourierToolRuntime.Decoded.Valid
        val rawQuestions = valid.raw["questions"] ?: valid.fields["questions"] ?: "[]"
        val render = renderQuestionForm(rawQuestions)
        emit(
            DispatchEvent.UserInputRequired(
                prompt = render,
                type = DispatchInputType.TEXT
            )
        )
        val ticket = "question-${System.identityHashCode(render)}"
        val answer = questionBox.ask(ticket, render)
        return if (answer == null) {
            CourierToolRuntime.Outcome(
                machine = null,
                model = "The user dismissed the question form; no answer was given. " +
                    "Proceed with your best judgment and state your assumptions."
            )
        } else {
            runtime.outcome(
                machine = null,
                model = "User has answered your questions:\n$answer",
                metadata = """{"answered":true}"""
            )
        }
    }

    /** 表单渲染（用户面提示词）。 */
    internal fun renderQuestionForm(rawQuestions: String): String = buildString {
        appendLine("The station courier has questions for you:")
        try {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(rawQuestions)
            if (element is kotlinx.serialization.json.JsonArray) {
                for ((i, entry) in element.withIndex()) {
                    val obj = entry as? kotlinx.serialization.json.JsonObject ?: continue
                    val header = (obj["header"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content ?: "Question ${i + 1}"
                    val question = (obj["question"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { it.isString }?.content ?: continue
                    appendLine()
                    appendLine("$header: $question")
                    val options = obj["options"] as? kotlinx.serialization.json.JsonArray
                    if (options != null) {
                        for (opt in options) {
                            val label = ((opt as? kotlinx.serialization.json.JsonObject)
                                ?.get("label") as? kotlinx.serialization.json.JsonPrimitive)
                                ?.takeIf { it.isString }?.content ?: continue
                            appendLine("  - $label")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            appendLine(rawQuestions)
        }
        appendLine()
        append("Reply with your answers (one per question, or your own words).")
    }

    // ── courier_task ──

    private suspend fun executeTask(arguments: String): CourierToolRuntime.Outcome {
        val decoded = runtime.decode(
            toolName = TASK,
            arguments = arguments,
            required = setOf("agent", "description", "prompt"),
            optional = setOf("background")
        )
        if (decoded is CourierToolRuntime.Decoded.Invalid) {
            return runtime.invalidRecept(decoded.modelRecept)
        }
        val fields = (decoded as CourierToolRuntime.Decoded.Valid).fields
        val result = taskLauncher.launch(
            CourierTaskLauncher.TaskRequest(
                roleId = fields["agent"] ?: "general",
                description = fields["description"] ?: "",
                prompt = fields["prompt"] ?: "",
                background = fields["background"]?.lowercase() in setOf("true", "1", "yes")
            )
        )
        return if (result.status == DispatchChildStatus.RUNNING) {
            // 后台：立即回执 + 完成后合成输入唤醒（不轮询）
            runtime.outcome(
                machine = null,
                model = "Dispatch moved to the background (station: ${result.stationId}). " +
                    "You will be woken with the result when it finishes — do NOT poll. " +
                    "Continue with non-overlapping work, or briefly tell the user what you " +
                    "launched and end your response.",
                metadata = """{"station":"${result.stationId}","status":"running"}"""
            )
        } else {
            val state = if (result.status == DispatchChildStatus.COMPLETED) {
                "completed"
            } else {
                "failed"
            }
            runtime.outcome(
                machine = null,
                model = "<courier station=\"${result.stationId}\" state=\"$state\">\n" +
                    "${result.output.trim()}\n</courier>",
                metadata = """{"station":"${result.stationId}","status":"$state"}"""
            )
        }
    }

    // ── Schema 常量 ──

    private val TODOS_SCHEMA = """{"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","done"]},"activeForm":{"type":"string"}},"required":["content","status"]}}},"required":[]}"""

    private val QUESTION_SCHEMA = """{"type":"object","properties":{"questions":{"type":"array","items":{"type":"object","properties":{"question":{"type":"string"},"header":{"type":"string"},"options":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string"},"description":{"type":"string"}},"required":["label"]}},"multiple":{"type":"boolean"}},"required":["question"]}}},"required":["questions"]}"""

    private val TASK_SCHEMA = """{"type":"object","properties":{"agent":{"type":"string","description":"sub-station role id"},"description":{"type":"string","description":"short human-readable summary"},"prompt":{"type":"string","description":"complete self-sufficient task brief"},"background":{"type":"boolean","default":false}},"required":["agent","description","prompt"]}"""
}
