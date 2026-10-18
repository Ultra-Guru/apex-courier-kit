package com.apex.courierkit.dispatch

/**
 * # Courier Rule Book — 通行则例（勘合法则库）
 *
 * 则例三定律（通行证核验的判定核心）：
 *
 * 1. **通配**：resource 模式支持 `*`（任意串）与 `?`（单字符），其余
 *    字符按字面；`\` 折叠为 `/`；**尾部 `" *"` 后缀可选**——`git push *`
 *    既匹配 `git push` 也匹配 `git push origin main`；
 * 2. **后例覆盖**（findLast 优先）：评估顺序 = 画像默认 → 会话则例 →
 *    勘合记忆（always 记忆），**最后一条命中者生效**——画像给底、
 *    会话收窄、记忆落位；
 * 3. **聚合**（deny > ask > allow）：一次核验涉及多资源时，任一拒付
 *    → 拒付；否则任一勘验 → 勘验；否则放行。**未命中任何则例 = ASK**
 *    （保守缺省）。
 */
object CourierRuleBook {

    /** 资源规范化：`\` → `/`、去首尾空白、折叠连续空格。 */
    fun normalizeResource(raw: String): String =
        raw.replace('\\', '/').trim().replace(Regex("\\s+"), " ")

    /**
     * 通配匹配（模式 → 资源）。大小写敏感（Linux 宿主）。
     */
    fun matches(pattern: String, resource: String): Boolean {
        val p = normalizeResource(pattern)
        val r = normalizeResource(resource)
        if (p.isEmpty()) return r.isEmpty()

        // 尾部 " *" 可选后缀：git push * ≡ git push + 任意后继参数
        val optionalSuffix = p.endsWith(" *")
        val body = if (optionalSuffix) p.removeSuffix(" *") else p
        if (body == "*") return true
        if (optionalSuffix) {
            if (r == body || r.startsWith("$body ")) return true
        }

        val regex = buildString {
            append('^')
            for (c in body) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }.toRegex()
        return regex.matches(r)
    }

    /**
     * 单则例匹配判定（action 与 resource 双匹配）。
     */
    fun ruleMatches(
        rule: DispatchPermissionRule,
        action: String,
        resource: String
    ): Boolean = rule.action == action && matches(rule.resource, resource)

    /**
     * 评估：则例列表中**最后一条**同时匹配 action 与 resource 者生效；
     * 无命中 → ASK（保守缺省）。
     */
    fun evaluate(
        rules: List<DispatchPermissionRule>,
        action: String,
        resource: String
    ): DispatchRuleEffect {
        for (i in rules.indices.reversed()) {
            val rule = rules[i]
            if (ruleMatches(rule, action, resource)) return rule.effect
        }
        return DispatchRuleEffect.ASK
    }

    /**
     * 聚合：任一 DENY → DENY；否则任一 ASK → ASK；否则 ALLOW。
     * 空列表 = 全放行（调用方负责把「无资源」视为无核验需求）。
     */
    fun aggregate(effects: List<DispatchRuleEffect>): DispatchRuleEffect = when {
        effects.contains(DispatchRuleEffect.DENY) -> DispatchRuleEffect.DENY
        effects.contains(DispatchRuleEffect.ASK) -> DispatchRuleEffect.ASK
        else -> DispatchRuleEffect.ALLOW
    }

    /**
     * 则例展开顺序：画像默认（底）→ 会话快照（中）→ 勘合记忆（顶）。
     * 同序追加即实现「后例覆盖」。
     */
    fun stack(
        roleRules: List<DispatchPermissionRule>,
        sessionRules: List<DispatchPermissionRule>,
        savedRules: List<DispatchPermissionRule>
    ): List<DispatchPermissionRule> = roleRules + sessionRules + savedRules

    /** 通配则例是否全拒（工具面整枝剪除判定：`* deny` 压轴 = 该动作不可用）。 */
    fun whollyDenies(
        rules: List<DispatchPermissionRule>,
        action: String
    ): Boolean {
        for (i in rules.indices.reversed()) {
            val rule = rules[i]
            if (rule.action == action) {
                return rule.effect == DispatchRuleEffect.DENY && rule.resource == "*"
            }
            // `*` 通配动作的压轴则例同样参与判定
            if (rule.action == "*") {
                return rule.effect == DispatchRuleEffect.DENY && rule.resource == "*"
            }
        }
        return false
    }
}
