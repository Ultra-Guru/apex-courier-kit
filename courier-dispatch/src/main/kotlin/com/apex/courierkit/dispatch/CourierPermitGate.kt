package com.apex.courierkit.dispatch

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * # Courier Permit Gate — 勘合门（工具调用通行证核验）
 *
 * 每次工具调用先核勘合，后放行。法则（详见 [CourierRuleBook]）：
 *
 * - **则例堆叠**：画像默认（底）→ 会话快照则例（中）→ always 勘合记忆
 *   （顶）——后例覆盖；
 * - **复合命令逐段**：终端类调用经 [CourierCommandProbe] 提取命令串、
 *   [CourierCommandSplitter] 拆段，每段独立成行（deny>ask>allow 聚合），
 *   always 记忆按段模式（`git push *`）落位；
 * - **ask 悬置**：命中勘验 → 出票（ticket）+ 悬置 CompletableDeferred，
 *   引擎发 [DispatchEvent.UserInputRequired]，答复回流 [reply]：
 *   - **once** → 本次放行；
 *   - **always** → 记忆 save 模式为 allow 则例 + **自动复评其余悬置票**
 *     （新则例下已可通行者即时放行）；
 *   - **拒绝带反馈** → 纠正性失败（模型可见回执，模型可改姿势）；
 *   - **拒绝无反馈** → 缺陷隧道（[CourierDecline]——趟次中断，模型不再
 *     重试同一路径）；拒一张 = 拒本轮全部悬置票；
 * - **deny** → 直接拒付（模型可见失败回执，非隧道）；
 * - **整枝剪除**：`* deny` 压轴的动作从工具面整体摘除（模型根本看不到）。
 */
class CourierPermitGate(
    private val snapshotSource: CourierPermissionSnapshotSource,
    private val commandProbe: CourierCommandProbe,
    private val logger: CourierLogger,
    /** 工具 id → 动作串的手工覆盖（宿主注册表口径对齐用）。 */
    private val toolActionHints: Map<String, String> = emptyMap(),
    /** 子站模式：ask 自动拒付（子驿无交互窗口——主站问答权专属）。 */
    private val autoDenyAsks: Boolean = false
) {

    /** 一次核验请求（action × resource × always 记忆模式）。 */
    data class PermitRequest(
        val action: String,
        val resource: String,
        val savePattern: String? = null
    )

    /** 核验结论。 */
    sealed interface PermitOutcome {
        /** 放行。 */
        data object Granted : PermitOutcome

        /** 拒付（则例 deny / 命中不可通行段）——模型可见失败。 */
        data class Blocked(val message: String) : PermitOutcome

        /** 用户拒付（feedback 非空 = 纠正性回执；空 = 缺陷隧道）。 */
        data class Declined(val feedback: String?) : PermitOutcome

        /** 悬置超时 / 会话收口（按未决处理——趟次中断）。 */
        data object Expired : PermitOutcome
    }

    /** 悬置票信息（引擎事件面只读快照）。 */
    class PendingTicket internal constructor(
        val ticket: String,
        val prompt: String,
        internal val deferred: CompletableDeferred<AskDecision>,
        internal val request: PermitRequest
    ) {
        val action: String get() = request.action
        val resource: String get() = request.resource
    }

    internal sealed interface AskDecision {
        data object ApprovedOnce : AskDecision
        data object ApprovedAlways : AskDecision
        data class Rejected(val feedback: String?) : AskDecision
        data object TimedOut : AskDecision
    }

    /** ask 出票监听（引擎接线——发 UserInputRequired 事件）。 */
    @Volatile
    var askListener: ((ticket: String, prompt: String) -> Unit)? = null

    private val savedRules = CopyOnWriteArrayList<DispatchPermissionRule>()
    private val pending = ConcurrentHashMap<String, PendingTicket>()
    private val ticketSeq = AtomicLong(0)

    // ═══════════════════════════ 核验入口 ═══════════════════════════

    /**
     * 工具调用核验：动作分类 → （终端类）逐段展开 → 聚合判定。
     * 悬置票**不在此等待**——返回 [PermitOutcome.Blocked]/新建票后由
     * 调用方决定等待方式（引擎用 [awaitDecision]）。
     */
    fun inspect(
        toolId: String,
        arguments: String,
        role: DispatchAgentRole,
        mode: DispatchMode
    ): Inspection {
        val requests = expandRequests(toolId, arguments, role, mode)
        if (requests.isEmpty()) {
            return Inspection(requests = emptyList(), precomputed = PermitOutcome.Granted)
        }
        val rules = stackedRules(role)
        val effects = requests.map { CourierRuleBook.evaluate(rules, it.action, it.resource) }
        return when (CourierRuleBook.aggregate(effects)) {
            DispatchRuleEffect.ALLOW ->
                Inspection(requests = requests, precomputed = PermitOutcome.Granted)

            DispatchRuleEffect.DENY -> {
                val first = requests[effects.indexOf(DispatchRuleEffect.DENY)]
                Inspection(
                    requests = requests,
                    precomputed = PermitOutcome.Blocked(
                        "Permit denied: action '${first.action}' on '${first.resource}' " +
                            "is blocked by the standing rules"
                    )
                )
            }

            DispatchRuleEffect.ASK ->
                if (autoDenyAsks) {
                    val first = requests.first()
                    Inspection(
                        requests = requests,
                        precomputed = PermitOutcome.Blocked(
                            "Permit declined: sub-station couriers cannot ask the user " +
                                "for permission (action '${first.action}' on '${first.resource}'). " +
                                "Choose a different route or report back."
                        )
                    )
                } else {
                    Inspection(requests = requests, precomputed = null)
                }
        }
    }

    /**
     * 开出悬置票（inspect 结论为 null——需要用户勘验时调用）。
     * 返回票句柄，随后 [awaitDecision] 等答复。
     */
    fun issueTicket(inspection: Inspection, prompt: String): PendingTicket {
        val ticket = "permit-${ticketSeq.incrementAndGet()}"
        val handle = PendingTicket(
            ticket = ticket,
            prompt = prompt,
            deferred = CompletableDeferred(),
            request = inspection.requests.firstOrNull() ?: PermitRequest("ask", "*")
        )
        pending[ticket] = handle
        logger.info(TAG, "permit ask issued: $ticket for ${handle.request.action}:${handle.request.resource}")
        askListener?.invoke(ticket, prompt)
        return handle
    }

    /** 等待悬置票答复（askListener 事件发出后由引擎协程调用）。 */
    suspend fun awaitDecision(ticket: PendingTicket): PermitOutcome {
        val decision = try {
            ticket.deferred.await()
        } catch (e: Exception) {
            AskDecision.TimedOut
        }
        return when (decision) {
            AskDecision.ApprovedOnce,
            AskDecision.ApprovedAlways -> PermitOutcome.Granted

            is AskDecision.Rejected -> PermitOutcome.Declined(decision.feedback)
            AskDecision.TimedOut -> PermitOutcome.Expired
        }
    }

    /** 答复悬置票。 */
    fun reply(ticket: String, approved: Boolean, always: Boolean, feedback: String?): Boolean {
        val handle = pending.remove(ticket) ?: return false
        settleReply(handle, approved, always, feedback)
        return true
    }

    /**
     * 答复**首张**悬置票（引擎单通道答复路由——宿主答复不携带票号；
     * 无悬置票返回 false）。
     */
    fun replyFirstPending(approved: Boolean, always: Boolean, feedback: String?): Boolean {
        val handle = pending.values.firstOrNull() ?: return false
        pending.remove(handle.ticket)
        settleReply(handle, approved, always, feedback)
        return true
    }

    private fun settleReply(
        handle: PendingTicket,
        approved: Boolean,
        always: Boolean,
        feedback: String?
    ) {
        when {
            approved && always -> {
                val save = handle.request.savePattern ?: handle.request.resource
                savedRules += DispatchPermissionRule(
                    action = handle.request.action,
                    resource = save,
                    effect = DispatchRuleEffect.ALLOW
                )
                logger.info(TAG, "permit always saved: ${handle.request.action} → $save")
                resolveSiblingsUnderSavedRules()
                handle.deferred.complete(AskDecision.ApprovedAlways)
            }

            approved -> handle.deferred.complete(AskDecision.ApprovedOnce)

            else -> {
                // 拒一张 = 拒本轮全部悬置票（批语义）
                rejectAllPending(feedback)
                handle.deferred.complete(AskDecision.Rejected(feedback))
            }
        }
    }

    /** 会话收口 / 超时：全部悬置票按未决兑现（缺陷隧道）。 */
    fun expireAllPending() {
        val tickets = pending.keys.toList()
        for (t in tickets) {
            pending.remove(t)?.deferred?.complete(AskDecision.TimedOut)
        }
    }

    /** 清空会话勘合记忆（新会话）。 */
    fun clearSessionMemory() {
        savedRules.clear()
        expireAllPending()
    }

    /** 当前会话记忆则例快照（只读）。 */
    fun savedRulesSnapshot(): List<DispatchPermissionRule> = savedRules.toList()

    /** 悬置票清单（只读）。 */
    fun pendingTickets(): List<PendingTicket> = pending.values.toList()

    /** 工具面剪除判定：该工具在当前则例下是否整枝不可用。 */
    fun isWhollyDenied(
        toolId: String,
        role: DispatchAgentRole,
        mode: DispatchMode
    ): Boolean {
        val action = classifyAction(toolId)
        if (action == null) return false
        return CourierRuleBook.whollyDenies(stackedRules(role), action)
    }

    // ── 内部 ──

    /** 核验中间态：requests + 预判（null = 需要开票勘验）。 */
    class Inspection internal constructor(
        internal val requests: List<PermitRequest>,
        internal val precomputed: PermitOutcome?
    )

    private fun expandRequests(
        toolId: String,
        arguments: String,
        role: DispatchAgentRole,
        mode: DispatchMode
    ): List<PermitRequest> {
        val action = classifyAction(toolId) ?: return emptyList()
        val command = commandProbe.commandOf(toolId, arguments)
        return if (command != null) {
            // 终端类：复合命令逐段核验 + 会话命令则例并入
            val subs = CourierCommandSplitter.split(command)
            if (subs.isEmpty()) {
                listOf(PermitRequest(action, toolId, savePattern = toolId))
            } else {
                subs.map { PermitRequest(action, it.resource, savePattern = it.savePattern) }
            }
        } else {
            val resource = resourceFor(toolId, arguments)
            listOf(PermitRequest(action, resource, savePattern = resource))
        }
    }

    /** 工具 id → 动作串（分类器：宿主注册表命名口径 + 手工覆盖）。 */
    private fun classifyAction(toolId: String): String? {
        toolActionHints[toolId]?.let { return it }
        val id = toolId.lowercase()
        return when {
            id.startsWith("courier_") -> when {
                id.endsWith("_task") -> "task"
                id.endsWith("_question") -> "ask"
                id.endsWith("_todos") -> null // 清单簿写：纯会话态，不核勘合
                else -> null
            }

            // 终端 / shell 族（宿主 ShellExecute 等）
            id.contains("shellexecute") || id.endsWith("_bash") || id.endsWith("_shell") ||
                id.endsWith("_terminal") || id.endsWith("_exec") || id.endsWith("_command") ->
                "bash"

            // 读族
            id.endsWith("_read") || id.endsWith("_glob") || id.endsWith("_grep") ||
                id.endsWith("_list") || id.endsWith("_search") || id.endsWith("_ls") ||
                id.endsWith("_find") || id.endsWith("_view") || id.endsWith("_status") ||
                id.endsWith("_log") || id.endsWith("_diff") || id.endsWith("_show") ->
                "read"

            // 写族（文件系统效应）
            id.endsWith("_write") || id.endsWith("_edit") || id.endsWith("_patch") ||
                id.endsWith("_delete") || id.endsWith("_remove") || id.endsWith("_mkdir") ||
                id.endsWith("_rename") || id.endsWith("_move") || id.endsWith("_apply") ||
                id.endsWith("_commit") || id.endsWith("_push") || id.endsWith("_pull") ||
                id.endsWith("_checkout") || id.endsWith("_stage") || id.endsWith("_init") ->
                "edit"

            // 网络族
            id.endsWith("_fetch") || id.endsWith("_web") || id.endsWith("_browse") ||
                id.endsWith("_net") || id.startsWith("web_") -> "net"

            // 技能 / MCP / 子代理
            id.startsWith("skill_") || id.endsWith("_skill") -> "skill"
            id.startsWith("mcp__") -> "mcp"
            id.endsWith("_task") || id.endsWith("_subagent") || id.endsWith("_agent") -> "task"

            else -> null // 未分类工具不核勘合（宿主侧环境门兜底）
        }
    }

    /** 非终端工具的资源串：参数里的 path/url/target 尽力提取，缺省工具 id。 */
    private fun resourceFor(toolId: String, arguments: String): String {
        extractJsonStringField(arguments, "path")?.let { return it }
        extractJsonStringField(arguments, "file")?.let { return it }
        extractJsonStringField(arguments, "url")?.let { return it }
        extractJsonStringField(arguments, "target")?.let { return it }
        extractJsonStringField(arguments, "command")?.let { return it }
        return toolId
    }

    private fun extractJsonStringField(json: String, field: String): String? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
        (obj as? kotlinx.serialization.json.JsonObject)
            ?.get(field)?.let { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
                    it.isString
                }?.content
            }
    } catch (e: Exception) {
        null
    }

    /** 则例堆叠：画像默认 + 会话快照 + 命令则例 + always 记忆。 */
    private fun stackedRules(role: DispatchAgentRole): List<DispatchPermissionRule> {
        val snapshot = snapshotSource.snapshot()
        return CourierRuleBook.stack(
            roleRules = role.permissionRules,
            sessionRules = snapshot.rules + snapshot.commandRules,
            savedRules = savedRules
        )
    }

    /** always 落位后：复评其余悬置票，新则例下可通行者即时放行。 */
    private fun resolveSiblingsUnderSavedRules() {
        val tickets = pending.values.toList()
        if (tickets.isEmpty()) return
        // 复评需要完整堆叠（画像不可知——用通用缺省堆叠：快照 + 记忆）
        val snapshot = snapshotSource.snapshot()
        val rules = CourierRuleBook.stack(
            roleRules = emptyList(),
            sessionRules = snapshot.rules + snapshot.commandRules,
            savedRules = savedRules
        )
        for (t in tickets) {
            val effect = CourierRuleBook.evaluate(rules, t.request.action, t.request.resource)
            if (effect == DispatchRuleEffect.ALLOW) {
                if (pending.remove(t.ticket) != null) {
                    t.deferred.complete(AskDecision.ApprovedOnce)
                    logger.info(TAG, "permit sibling auto-resolved: ${t.ticket}")
                }
            }
        }
    }

    private fun rejectAllPending(feedback: String?) {
        val tickets = pending.keys.toList()
        for (t in tickets) {
            pending.remove(t)?.deferred?.complete(AskDecision.Rejected(feedback))
        }
    }

    private companion object {
        const val TAG = "PermitGate"
    }
}
