package com.apex.courierkit.dispatch

/**
 * # Courier Call Assembler — 调用装配器（原生工具调用流式增量装配）
 *
 * OpenAI 风格并行工具调用的流式增量协议：**首片段携带 id + name + index**，
 * 后续片段只携带 **index**（id 空）——以 `id` 为键、`index` 为回退键累积
 * 参数 JSON 片段，流尾一次成器。非流式响应的完整调用直接落位。
 */
class CourierCallAssembler {

    private class Slot(
        var id: String,
        var name: String,
        val index: Int,
        val args: StringBuilder
    )

    private val slots = LinkedHashMap<String, Slot>()

    /** 喂入一帧（chunk.toolCalls 逐个喂入）。 */
    fun feed(call: DispatchToolCall) {
        val key = when {
            call.id.isNotBlank() -> call.id
            call.index >= 0 -> "idx-${call.index}"
            else -> return // 无 id 无 index：不可归属，丢弃
        }
        val existing = slots[key]
        if (existing == null) {
            slots[key] = Slot(
                id = call.id.ifBlank { key },
                name = call.name,
                index = call.index,
                args = StringBuilder(call.arguments)
            )
            return
        }
        // 增量片段：补 id/name（首片段可能乱序到达），追加参数
        if (call.id.isNotBlank() && existing.id.startsWith("idx-")) {
            existing.id = call.id
        }
        if (call.name.isNotBlank()) {
            existing.name = call.name
        }
        existing.args.append(call.arguments)
    }

    /** 是否已有部分装配（流中断判定用）。 */
    fun hasPending(): Boolean = slots.isNotEmpty()

    /** 成器（流尾 / finishReason=tool_calls 时调用；装配后复位）。 */
    fun settle(): List<DispatchToolCall> {
        val settled = slots.values.map { slot ->
            DispatchToolCall(
                id = slot.id,
                name = slot.name,
                arguments = slot.args.toString(),
                index = slot.index
            )
        }
        slots.clear()
        return settled
    }

    /** 复位（新趟起点）。 */
    fun reset() {
        slots.clear()
    }
}
