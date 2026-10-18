package com.apex.courierkit.dispatch

/**
 * # Courier Inbox — 收发匣（用户输入投递调度）
 *
 * 用户输入**先落匣、后投递**（落匣即受理，投递在趟次安全边界）：
 *
 * - **steer**（缺省）：插队件——在下一安全边界**按入匣顺序整批先行**送达；
 *   运行中途到达的 steer 折进当前 execute 的后续趟次；
 * - **queue**：排队件——等空闲边界（趟次间无 steer 待投时）逐条投递，
 *   steer 永远先于 queue；
 * - **控制件**（[Item.CompactNow]）：自成边界——steer 不跨越控制件；
 *   手动压缩控制件**置顶**（先压缩再投递早先的 steer，保其原文在
 *   checkpoint 之后逐字保留）；
 * - **幂等**：按 id 去重——首次落匣受理，重复 admit 静默忽略；
 * - **批语义**：一次投递 ≥1 条用户/合成件 → 趟次计数器归零（整批只
 *   归零一次）。
 */
class CourierInbox {

    /** 投递模式。 */
    enum class Delivery { STEER, QUEUE }

    /** 匣内件。 */
    sealed interface Item {
        val id: String

        /** 用户可见输入件（落账为 User 消息）。 */
        data class UserItem(
            override val id: String,
            val text: String,
            val images: List<DispatchImageContent> = emptyList(),
            val files: List<DispatchFileRef> = emptyList()
        ) : Item

        /** 合成输入件（后台完成通知 / 续令等——落账为 User 消息但不进 UI 用户气泡）。 */
        data class SyntheticItem(
            override val id: String,
            val text: String,
            val description: String = ""
        ) : Item

        /** 控制件：手动压缩（自成边界，置顶）。 */
        data class CompactNow(
            override val id: String,
            val reason: String = "manual"
        ) : Item
    }

    /** 一次投递决断。 */
    data class Promotion(
        /** 本次投递的可见件（User/Synthetic，按序）。 */
        val items: List<Item>,
        /** 本次命中的控制件（null = 无）。 */
        val control: Item.CompactNow?,
        /** 趟次计数器是否归零（投递了 ≥1 可见件）。 */
        val resetTripCounter: Boolean
    )

    private class Slot(
        val item: Item,
        @Volatile var delivery: Delivery
    ) {
        var cancelled = false
    }

    private val slots = ArrayList<Slot>()

    /** 落匣（幂等 by id；控制件置顶）。 */
    @Synchronized
    fun admit(item: Item, delivery: Delivery = Delivery.STEER): Boolean {
        if (slots.any { !it.cancelled && it.item.id == item.id }) return false
        val slot = Slot(item, delivery)
        when (item) {
            is Item.CompactNow -> slots.add(0, slot) // 置顶（先压缩后投 steer）
            else -> slots.add(slot)
        }
        return true
    }

    /** 改投递模式为 steer（唤醒边界变更——引擎据此可立即开闸）。 */
    @Synchronized
    fun steer(id: String): Boolean = mutate(id) { it.delivery = Delivery.STEER }

    /** 改投递模式为 queue（不唤醒）。 */
    @Synchronized
    fun queue(id: String): Boolean = mutate(id) { it.delivery = Delivery.QUEUE }

    /** 取消匣内件。 */
    @Synchronized
    fun cancel(id: String): Boolean = mutate(id) { it.cancelled = true }

    /**
     * 下一投递决断（趟次安全边界调用）：
     *
     * 1. 匣首为控制件 → 单独返回控制件；
     * 2. 匣首为 steer → 取**连续 steer 批**（遇控制件停）；
     * 3. 空闲（匣首为 queue）→ 取 1 条 queue + 其后连续 steer；
     * 4. 匣空 → null。
     */
    @Synchronized
    fun nextPromotable(): Promotion? {
        pruneCancelled()
        val head = slots.firstOrNull() ?: return null
        return when (head.item) {
            is Item.CompactNow -> {
                slots.removeAt(0)
                Promotion(items = emptyList(), control = head.item as Item.CompactNow, resetTripCounter = false)
            }

            else -> {
                val batch = ArrayList<Item>()
                if (head.delivery == Delivery.STEER) {
                    // 连续 steer 批（控制件为止）
                    while (slots.isNotEmpty()) {
                        val s = slots.first()
                        if (s.delivery != Delivery.STEER || s.item is Item.CompactNow) break
                        batch += slots.removeAt(0).item
                    }
                } else {
                    // 空闲边界：1 条 queue + 随后到达的连续 steer
                    batch += slots.removeAt(0).item
                    while (slots.isNotEmpty()) {
                        val s = slots.first()
                        if (s.delivery != Delivery.STEER || s.item is Item.CompactNow) break
                        batch += slots.removeAt(0).item
                    }
                }
                Promotion(items = batch, control = null, resetTripCounter = batch.isNotEmpty())
            }
        }
    }

    /** 是否有待投件。 */
    @Synchronized
    fun isIdle(): Boolean {
        pruneCancelled()
        return slots.isEmpty()
    }

    /** 待投件数（含控制件）。 */
    @Synchronized
    fun size(): Int {
        pruneCancelled()
        return slots.size
    }

    /** 待投 steer 件数（运行中转向可插入的判据）。 */
    @Synchronized
    fun pendingSteers(): Int {
        pruneCancelled()
        return slots.count { it.delivery == Delivery.STEER && it.item !is Item.CompactNow }
    }

    /** 清空（新会话 / 收口）。 */
    @Synchronized
    fun clear() {
        slots.clear()
    }

    /** 只读快照。 */
    @Synchronized
    fun snapshot(): List<Pair<Item, Delivery>> {
        pruneCancelled()
        return slots.map { it.item to it.delivery }
    }

    // ── 内部 ──

    private fun mutate(id: String, block: (Slot) -> Unit): Boolean {
        val slot = slots.firstOrNull { !it.cancelled && it.item.id == id } ?: return false
        block(slot)
        return true
    }

    private fun pruneCancelled() {
        slots.removeAll { it.cancelled }
    }
}
