package com.apex.courierkit.dispatch

import kotlinx.coroutines.flow.Flow

/**
 * # Courier Tool Runtime — 工具经手运行时
 *
 * 工具调用的**契约执行层**（参数解码 → 校验 → 执行 → 三值回执 → 边界
 * 裁剪）。本地工具与宿主工具共用同一套经手纪律：
 *
 * - **坏参数不执行**：校验问题**至多列 5 条**（字段路径 + 原因），并
 *   回显原始参数 JSON（截 [CourierConfig.inputEchoLimit]）；
 * - **三值回执**：machine（机器值 JSON——不进模型上下文）/
 *   model（模型面文本——落账进上下文）/ metadata（UI 摘要 JSON）；
 * - **输出边界**：[CourierConfig.outputLineLimit] 行 /
 *   [CourierConfig.outputByteLimit] 字节——超限保头裁尾 + 截尾注记
 *   `（前 N 行 / 共 M 行——完整输出已略）`；
 * - **流式转发**：经手宿主工具流时 Output/Progress 逐帧转发（进度
 *   不裁剪），终值走边界。
 */
class CourierToolRuntime(private val config: CourierConfig) {

    /** 三值回执。 */
    data class Outcome(
        /** 机器值（JSON 串——本地工具的结构化产物；宿主工具为 null）。 */
        val machine: String? = null,
        /** 模型面文本（边界裁剪后——落账进上下文的回执正文）。 */
        val model: String,
        /** UI 摘要（JSON 串；null = 无）。 */
        val metadata: String? = null
    )

    /** 参数校验问题（至多 5 条进入回执）。 */
    data class Issue(
        val path: String,
        val reason: String
    )

    /** 参数解析产物：JsonObject 或坏参数回执。 */
    sealed interface Decoded {
        data class Valid(
            val fields: Map<String, String>,
            val raw: Map<String, String>
        ) : Decoded

        /** 坏参数回执（模型可见——不执行工具）。 */
        data class Invalid(val modelRecept: String) : Decoded
    }

    // ═══════════════════════════ 参数解码与校验 ═══════════════════════════

    /**
     * 解码 + 校验：期望字段表（名 → 必填）。字符串 / 数值 / 布尔统一取
     * 文本值（本地工具全用字符串语义字段）。
     */
    fun decode(
        toolName: String,
        arguments: String,
        required: Set<String>,
        optional: Set<String> = emptySet()
    ): Decoded {
        val echo = arguments.take(config.inputEchoLimit)
        val parsed = try {
            kotlinx.serialization.json.Json.parseToJsonElement(arguments) as?
                kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        }
        if (parsed == null) {
            return Decoded.Invalid(
                "Invalid dispatch for $toolName: arguments are not a JSON object " +
                    "(raw input: $echo)"
            )
        }
        val fields = HashMap<String, String>()
        val raw = HashMap<String, String>()
        val issues = ArrayList<Issue>()
        for ((key, value) in parsed) {
            raw[key] = value.toString()
            val text = when (value) {
                is kotlinx.serialization.json.JsonPrimitive -> value.content

                is kotlinx.serialization.json.JsonArray ->
                    value.joinToString(",") { el ->
                        (el as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
                    }

                else -> "?"
            }
            fields[key] = text
        }
        for (name in required) {
            if (!fields.containsKey(name) || fields[name].isNullOrBlank()) {
                issues += Issue("\$$name", "required field is missing or blank")
            }
        }
        for (name in fields.keys) {
            if (name !in required && name !in optional) {
                issues += Issue("\$$name", "unknown field for this dispatch")
            }
        }
        return if (issues.isEmpty()) {
            Decoded.Valid(fields, raw)
        } else {
            val listed = issues.take(MAX_ISSUES).joinToString("; ") { "${it.path}: ${it.reason}" }
            Decoded.Invalid(
                "Invalid dispatch for $toolName: $listed " +
                    "(up to $MAX_ISSUES issues listed; raw input: $echo)"
            )
        }
    }

    // ═══════════════════════════ 输出边界 ═══════════════════════════

    /**
     * 输出边界裁剪：行数与字节双限，保头裁尾 + 截尾注记。
     */
    fun bound(raw: String): String {
        if (raw.isEmpty()) return raw
        val lines = raw.split('\n')
        val byLines = if (lines.size > config.outputLineLimit) {
            lines.subList(0, config.outputLineLimit).joinToString("\n") +
                "\n[showing first ${config.outputLineLimit} of ${lines.size} lines — remainder elided]"
        } else {
            raw
        }
        val bytes = byLines.toByteArray(Charsets.UTF_8)
        if (bytes.size <= config.outputByteLimit) return byLines
        // 字节超限：按字符安全截断（不撕裂多字节字符）
        var cut = config.outputByteLimit
        while (cut > 0 && cut < byLines.length &&
            (byLines[cut - 1].code and 0xC0) == 0x80
        ) {
            cut--
        }
        val head = byLines.take(cut.coerceAtMost(byLines.length))
        val total = bytes.size
        return "$head\n[output capped at $config.outputByteLimit bytes of $total — remainder elided]"
    }

    /** 便捷：三值回执构造（machine 默认 stringify）。 */
    fun outcome(
        machine: String?,
        model: String,
        metadata: String? = null
    ): Outcome = Outcome(
        machine = machine,
        model = bound(model),
        metadata = metadata
    )

    // ═══════════════════════════ 宿主工具流经手 ═══════════════════════════

    /**
     * 宿主工具流经手：转发增量/进度帧到 [sink]（进度帧原样、增量帧累计），
     * 流尾取终值走边界。失败帧 → success=false 回执。
     */
    suspend fun relayHostStream(
        source: Flow<DispatchToolStream>,
        sink: (DispatchToolStream) -> Unit
    ): RelayFinal {
        val buffer = StringBuilder()
        var finalOutput: String? = null
        var failure: String? = null
        source.collect { event ->
            when (event) {
                is DispatchToolStream.Output -> {
                    buffer.append(event.chunk)
                    sink(event)
                }

                is DispatchToolStream.Progress -> sink(event)
                is DispatchToolStream.Complete -> finalOutput = event.output
                is DispatchToolStream.Error -> failure = event.message
            }
        }
        val raw = finalOutput ?: buffer.toString()
        return if (failure != null) {
            RelayFinal(
                success = false,
                modelContent = "Tool stream failed: $failure",
                fullContent = raw
            )
        } else {
            RelayFinal(
                success = true,
                modelContent = bound(raw),
                fullContent = raw
            )
        }
    }

    /** 宿主工具经手终值。 */
    data class RelayFinal(
        val success: Boolean,
        /** 模型面回执（边界后）。 */
        val modelContent: String,
        /** 全量输出（UI fullOutput 用）。 */
        val fullContent: String
    )

    private companion object {
        const val MAX_ISSUES = 5
    }
}
