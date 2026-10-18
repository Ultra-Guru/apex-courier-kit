package com.apex.courierkit.dispatch

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # Courier Engine — 信使引擎门面
 *
 * 库内核唯一装配点：收发匣 / 在驿卷宗 / 示廊 / 勘合门 / 档房 / 计划门 /
 * 题匾 / 下站前哨 / 趟次步进机全部在此组装；宿主经适配器只面向本类。
 *
 * ```
 * 宿主适配器 ──► CourierEngine ──┬─► CourierInbox（steer/queue/控制件）
 *            （端口注入）        ├─► CourierSession（落账 + 模型视图）
 *                               ├─► CourierBulletin（常设指令增量）
 *                               ├─► CourierPermitGate（通行证三态法则）
 *                               ├─► CourierArchivist（checkpoint 卷宗）
 *                               ├─► CourierPlanGate（PLAN 人控门）
 *                               ├─► CourierOutpost（子驿会话）
 *                               └─► CourierTripRunner（趟次步进机）
 * ```
 *
 * 执行协调（单飞排水）：execute 把输入落匣后驱动排水；运行中
 * [pushSteer] 插 steer 件（折叠进当前排水），不做第二次排水。
 */
class CourierEngine(
    val modelClient: CourierModelClient,
    private val hostToolSource: CourierToolSource = CourierToolSource { emptyList() },
    private val hostToolRunner: CourierToolRunner = CourierToolRunner { _, _ -> flowOf() },
    private val memory: CourierMemory? = null,
    private val logger: CourierLogger = CourierLogger.Silent,
    private val estimator: CourierTokenEstimator = CourierHeuristicEstimator,
    private val failureClassifier: CourierFailureClassifier = CourierFailureClassifier.Default,
    private val environmentSource: CourierEnvironmentSource? = null,
    private val modelInfoSource: CourierModelInfoSource? = null,
    private val workspaceContextSource: CourierWorkspaceContextSource? = null,
    private val agentCatalog: CourierAgentCatalog = CourierAgentCatalog.Builtin,
    private val skillCatalog: CourierSkillCatalog = CourierSkillCatalog.Empty,
    private val commandProbe: CourierCommandProbe = CourierCommandProbe.None,
    private val permissionSnapshotSource: CourierPermissionSnapshotSource =
        CourierPermissionSnapshotSource.NONE,
    private val clock: CourierClock = CourierClock.System,
    private val engineConfig: CourierConfig = CourierConfig()
) {

    private val config = engineConfig.sanitized()

    val abortSignal = CourierAbortSignal()

    // ── 引擎态（运行中可切换——经门面写入口） ──

    @Volatile
    private var currentRole: DispatchAgentRole = DispatchAgentRole.BUILD

    @Volatile
    private var currentMode: DispatchMode = DispatchMode.BUILD

    @Volatile
    private var thinking: DispatchThinkingLevel = DispatchThinkingLevel.STANDARD

    @Volatile
    private var forcedToolIds: Set<String> = emptySet()

    @Volatile
    private var forcedExposeAll: Boolean = false

    @Volatile
    private var extrasText: String? = null

    @Volatile
    private var personaOverride: String? = null

    @Volatile
    private var workspace: DispatchWorkspaceInfo? = null

    @Volatile
    private var globalRulesCache: String = ""

    // ── 子系统 ──

    private val session = CourierSession(memory, estimator, config)
    private val inbox = CourierInbox()
    private val bulletin = CourierBulletin(clock)
    private val permitGate = CourierPermitGate(
        snapshotSource = permissionSnapshotSource,
        commandProbe = commandProbe,
        logger = logger,
        autoDenyAsks = false
    )
    private val runtime = CourierToolRuntime(config)
    private val todoBoard = CourierTodoBoard()
    private val questionBox = CourierQuestionBox()
    private val archivist = CourierArchivist(
        config, modelClient, estimator, failureClassifier, logger
    )
    private val planGate = CourierPlanGate(config, clock)
    private val backoff = CourierBackoff(config)
    private val titler = CourierTitler(config, modelClient, logger)
    private val engineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val outpost = CourierOutpost(
        config = config,
        logger = logger,
        scope = engineScope,
        parentInbox = inbox,
        depth = 0
    ) { request, stationId, childDepth ->
        runChildStation(request, stationId, childDepth)
    }

    private val localTools = CourierLocalTools(
        config = config,
        runtime = runtime,
        todoBoard = todoBoard,
        questionBox = questionBox,
        taskLauncher = outpost
    )

    private val stateProvider = object : CourierTripRunner.StateProvider {
        override val role: DispatchAgentRole
            get() = personaOverride?.let { currentRole.withSystem(it) } ?: currentRole

        override val mode: DispatchMode get() = currentMode
        override val thinkingLevel: DispatchThinkingLevel get() = thinking
        override val forcedTools: Set<String> get() = forcedToolIds
        override val forcedExposeAll: Boolean get() = forcedExposeAll
        override val workspaceLabel: String? get() = workspace?.root?.path
        override val extras: String? get() = extrasText
    }

    private val runner = CourierTripRunner(
        config = config,
        clock = clock,
        modelClient = modelClient,
        toolSource = hostToolSource,
        toolRunner = hostToolRunner,
        runtime = runtime,
        localTools = localTools,
        permitGate = permitGate,
        session = session,
        inbox = inbox,
        bulletin = bulletin,
        archivist = archivist,
        planGate = planGate,
        estimator = estimator,
        modelInfoSource = modelInfoSource ?: CourierModelInfoSource { null },
        failureClassifier = failureClassifier,
        backoff = backoff,
        logger = logger,
        abortSignal = abortSignal,
        todoBoard = todoBoard,
        state = stateProvider,
        childMode = false
    )

    private val inputSeq = AtomicLong(0)
    private val drainMutex = Mutex()

    @Volatile
    private var drainActive: Boolean = false

    init {
        registerBulletinSources()
        permitGate.askListener = { _, prompt ->
            // 出票即广播——事件经引擎侧事件队列（engineEvents）直通当前收集器
            offerEngineEvent(
                DispatchEvent.UserInputRequired(prompt, DispatchInputType.CONFIRMATION)
            )
        }
        session.load()
        restoreTodoBoardFromHistory()
    }

    /** 引擎侧事件通道（勘合出票 / 题匾——非排水路径事件广播）。 */
    private val engineEventQueue = ArrayDeque<DispatchEvent>()

    // ═══════════════════════════ 执行入口 ═══════════════════════════

    fun execute(input: String): Flow<DispatchEvent> =
        execute(DispatchUserInput(text = input))

    fun execute(input: DispatchUserInput): Flow<DispatchEvent> = channelFlow {
        admitInput(input)
        drainMutex.withLock {
            drainActive = true
            abortSignal.reset()
            val startedAt = clock.nowMillis()
            var trips = 0
            var toolCalls = 0
            try {
                emitPump()
                val outcome = kotlinx.coroutines.coroutineScope {
                    val ticker = launch {
                        while (true) {
                            kotlinx.coroutines.delay(ENGINE_EVENT_PUMP_MS)
                            emitPump()
                        }
                    }
                    try {
                        runner.drain { event ->
                            if (event is DispatchEvent.IterationStart) trips++
                            if (event is DispatchEvent.ToolCallComplete) toolCalls++
                            send(event)
                        }
                    } finally {
                        ticker.cancel()
                    }
                }
                emitPump()
                when (outcome) {
                    is CourierTripRunner.DrainOutcome.Completed -> {
                        val summary = session.rawMessages()
                            .filterIsInstance<DispatchMessage.Assistant>()
                            .lastOrNull()?.content?.take(200) ?: ""
                        send(
                            DispatchEvent.Complete(
                                summary = summary,
                                totalIterations = trips,
                                totalToolCalls = toolCalls,
                                totalDurationMs = clock.nowMillis() - startedAt
                            )
                        )
                    }

                    is CourierTripRunner.DrainOutcome.Interrupted -> {
                        session.appendSynthetic(
                            CourierInbox.Item.SyntheticItem(
                                id = "abort-${inputSeq.incrementAndGet()}",
                                text = CourierPrompts.abortedMarker,
                                description = "abort marker"
                            )
                        )
                        send(DispatchEvent.Aborted)
                    }

                    is CourierTripRunner.DrainOutcome.Failed -> {
                        send(DispatchEvent.Error(outcome.message, outcome.recoverable))
                    }
                }
            } finally {
                drainActive = false
                emitPump()
            }
        }
    }

    /** 运行中转向（未运行返回 false——调用方改走常规发送）。 */
    fun pushSteer(text: String): Boolean {
        if (!drainActive) return false
        val id = "steer-${inputSeq.incrementAndGet()}"
        inbox.admit(CourierInbox.Item.UserItem(id, text), CourierInbox.Delivery.STEER)
        return true
    }

    private fun admitInput(input: DispatchUserInput): Boolean {
        val id = "in-${inputSeq.incrementAndGet()}"
        return inbox.admit(
            CourierInbox.Item.UserItem(
                id = id,
                text = input.text,
                images = input.images,
                files = input.files
            ),
            CourierInbox.Delivery.STEER
        )
    }

    /** 引擎侧事件泵（勘合出票等非排水路径事件）。 */
    private suspend fun ProducerScope<DispatchEvent>.emitPump() {
        while (true) {
            val event = synchronized(engineEventQueue) {
                if (engineEventQueue.isEmpty()) null else engineEventQueue.removeFirst()
            } ?: return
            send(event)
        }
    }

    private fun offerEngineEvent(event: DispatchEvent) {
        synchronized(engineEventQueue) { engineEventQueue += event }
    }

    // ═══════════════════════════ 中止 / 用户答复 ═══════════════════════════

    suspend fun abort() {
        abortSignal.raise()
        permitGate.expireAllPending()
        questionBox.cancelAll()
        planGate.cancelPending()
    }

    fun submitUserInput(answer: String) {
        // 路由顺序：问答表单 → 勘合票 → 计划门
        if (questionBox.current() != null && questionBox.answer(answer)) return
        val trimmed = answer.trim()
        val positive = trimmed.lowercase() in POSITIVE_ANSWERS
        if (permitGate.pendingTickets().isNotEmpty()) {
            when {
                trimmed.equals("always", true) || trimmed.equals("always allow", true) ->
                    permitGate.replyFirstPending(approved = true, always = true, feedback = null)

                positive ->
                    permitGate.replyFirstPending(approved = true, always = false, feedback = null)

                else -> {
                    // 拒付：带具体反馈则纠正性回执，否则缺陷隧道
                    val feedback = trimmed
                        .removePrefix("no").removePrefix("No")
                        .removePrefix("NO")
                        .removePrefix(":").removePrefix("：")
                        .trim()
                    permitGate.replyFirstPending(
                        approved = false,
                        always = false,
                        feedback = feedback.takeIf { it.isNotBlank() }
                    )
                }
            }
            return
        }
        if (planGate.isAwaiting()) {
            planGate.confirm(approved = positive, feedback = trimmed.takeIf { !positive })
        }
    }

    fun cancelUserInput() {
        questionBox.cancelAll()
        permitGate.expireAllPending()
        planGate.cancelPending()
    }

    // ═══════════════════════════ 会话 / 上下文门面 ═══════════════════════════

    fun setActiveWorkspace(workspaceId: String, name: String, root: java.io.File?, activeFile: String?) {
        workspace = DispatchWorkspaceInfo(workspaceId, name, root)
        session.bindWorkspace(workspaceId)
        session.load()
        restoreTodoBoardFromHistory()
    }

    fun setActiveFile(activeFile: String?) {
        // 轻量缓存：示廊工作区段经 source 即时读取——无需额外状态
    }

    fun prepareForTask() {
        bulletin.refresh()
    }

    fun updateGlobalRules(rules: String) {
        globalRulesCache = rules
    }

    fun updateMode(mode: DispatchMode) {
        currentMode = mode
        if (mode == DispatchMode.PLAN) {
            currentRole = DispatchAgentRole.PLAN
        } else if (currentRole.id == "plan") {
            currentRole = DispatchAgentRole.BUILD
        }
    }

    fun submitPlanConfirmation(confirmed: Boolean): Boolean =
        planGate.confirm(confirmed)

    fun updateSessionExtras(extras: String?) {
        extrasText = extras?.takeIf { it.isNotBlank() }
    }

    fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        personaOverride = roleDefinition.takeIf { it.isNotBlank() }
            ?: rolePrompt?.takeIf { it.isNotBlank() }
    }

    fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        this.forcedToolIds = forcedToolIds
        this.forcedExposeAll = exposeAll
    }

    fun updateThinkingLevel(level: DispatchThinkingLevel) {
        thinking = level
    }

    fun thinkingLevel(): DispatchThinkingLevel = thinking

    fun currentThinkingDecision(): String? = null

    fun clearConversation() {
        inbox.clear()
        session.clear()
        bulletin.reset()
        permitGate.clearSessionMemory()
        planGate.cancelPending()
        questionBox.cancelAll()
        todoBoard.clear()
        outpost.shutdownAll()
        titler.reset()
        synchronized(engineEventQueue) { engineEventQueue.clear() }
    }

    fun historyCount(): Int = session.messageCount()

    fun currentWorkspace(): DispatchWorkspaceInfo? = workspace

    fun currentTokenCount(): Int = session.estimateTokens()

    fun maxContextTokens(): Int = modelInfoSource?.current()?.contextWindow ?: 0

    fun pendingInboxSize(): Int = inbox.size()

    fun activeRole(): DispatchAgentRole = currentRole

    fun switchRole(roleId: String): Boolean {
        val catalog = agentCatalog.roles() + DispatchAgentRole.Builtins
        val target = catalog.firstOrNull { it.id == roleId } ?: return false
        if (target.mode == DispatchRoleMode.HIDDEN) return false
        currentRole = target
        return true
    }

    /** 引擎关闭（宿主生命周期收口——取消后台子驿）。 */
    fun shutdown() {
        outpost.shutdownAll()
        engineScope.cancel()
    }

    // ═══════════════════════════ 子驿工厂 ═══════════════════════════

    /** 子驿排水：独立组件集（纯内存会话 / 自动拒付勘合 / 无计划门）。 */
    private suspend fun runChildStation(
        request: CourierTaskLauncher.TaskRequest,
        stationId: String,
        childDepth: Int
    ): String {
        val catalog = agentCatalog.roles() + DispatchAgentRole.Builtins
        val role = catalog.firstOrNull { it.id == request.roleId }
            ?: DispatchAgentRole.GENERAL
        val childSession = CourierSession(null, estimator, config)
        val childInbox = CourierInbox()
        childInbox.admit(
            CourierInbox.Item.UserItem(
                id = "$stationId-brief",
                text = CourierPrompts.childPreamble(request.description) + "\n\n" + request.prompt
            ),
            CourierInbox.Delivery.QUEUE
        )
        val childGate = CourierPermitGate(
            snapshotSource = permissionSnapshotSource,
            commandProbe = commandProbe,
            logger = logger,
            autoDenyAsks = true
        )
        val childState = object : CourierTripRunner.StateProvider {
            override val role: DispatchAgentRole get() = role
            override val mode: DispatchMode get() = DispatchMode.BUILD
            override val thinkingLevel: DispatchThinkingLevel get() = thinking
            override val forcedTools: Set<String> get() = emptySet()
            override val forcedExposeAll: Boolean get() = true
            override val workspaceLabel: String? get() = workspace?.root?.path
            override val extras: String? get() = null
        }
        val childRunner = CourierTripRunner(
            config = config,
            clock = clock,
            modelClient = modelClient,
            toolSource = hostToolSource,
            toolRunner = hostToolRunner,
            runtime = runtime,
            localTools = CourierLocalTools(
                config, runtime, CourierTodoBoard(), CourierQuestionBox(),
                CourierTaskLauncher.Unavailable // 子驿不再递归委派
            ),
            permitGate = childGate,
            session = childSession,
            inbox = childInbox,
            bulletin = bulletin,
            archivist = CourierArchivist(config, modelClient, estimator, failureClassifier, logger),
            planGate = CourierPlanGate(config, clock),
            estimator = estimator,
            modelInfoSource = modelInfoSource ?: CourierModelInfoSource { null },
            failureClassifier = failureClassifier,
            backoff = CourierBackoff(config),
            logger = logger,
            abortSignal = abortSignal,
            todoBoard = CourierTodoBoard(),
            state = childState,
            childMode = true
        )
        val outcome = childRunner.drain { }
        return childSession.rawMessages()
            .filterIsInstance<DispatchMessage.Assistant>()
            .mapNotNull { it.content.takeIf { c -> c.isNotBlank() } }
            .lastOrNull() ?: when (outcome) {
                is CourierTripRunner.DrainOutcome.Failed -> "Sub-station failed: ${outcome.message}"
                is CourierTripRunner.DrainOutcome.Interrupted -> "Sub-station interrupted: ${outcome.cause}"
                else -> "Sub-station produced no final answer."
            }
    }

    // ═══════════════════════════ 内部 ═══════════════════════════

    /** 示廊源注册（稳定顺序：环境 → 日期 → 工作区 → 全局规则 → 项目规则 → 技能索引）。 */
    private fun registerBulletinSources() {
        bulletin.register("environment", "Environment") {
            val info = environmentSource?.current()
            buildString {
                if (info != null) {
                    appendLine("- platform: ${info.platformText}")
                    info.workdirLabel?.let { appendLine("- working directory: $it") }
                    info.isGitRepo?.let { appendLine("- git repository: $it") }
                }
                workspace?.root?.let { appendLine("- station root: ${it.path}") }
            }.trim().takeIf { it.isNotEmpty() }
        }
        bulletin.registerSource(bulletin.dateSource())
        bulletin.register("workspace", "Workspace") {
            workspaceContextSource?.workspaceSection()
        }
        bulletin.register("global-rules", "Global rules") {
            workspaceContextSource?.globalRulesSection()
        }
        bulletin.register("project-rules", "Project rules") {
            workspaceContextSource?.projectRulesSection()
        }
        bulletin.register("skills", "Available skills") {
            val skills = skillCatalog.list()
            if (skills.isEmpty()) {
                null
            } else {
                skills.joinToString("\n") { s -> "- ${s.id}: ${s.title}" }
            }
        }
    }

    /** 从历史恢复清单（最近一次 courier_todos 回执）。 */
    private fun restoreTodoBoardFromHistory() {
        val last = session.rawMessages()
            .filterIsInstance<DispatchMessage.ToolResult>()
            .lastOrNull { it.content.startsWith("Trip checklist:") }
            ?: return
        todoBoard.restore(todoBoard.parseRendered(last.content))
    }

    private fun DispatchAgentRole.withSystem(system: String): DispatchAgentRole =
        copy(system = system)

    private companion object {
        const val TAG = "CourierEngine"
        const val ENGINE_EVENT_PUMP_MS = 200L

        /** 单通道肯定答复词表（勘合/计划门共用——中英双语）。 */
        val POSITIVE_ANSWERS = setOf(
            "yes", "y", "ok", "okay", "confirm", "approve",
            "是", "好", "好的", "确认", "同意", "批准"
        )
    }
}
