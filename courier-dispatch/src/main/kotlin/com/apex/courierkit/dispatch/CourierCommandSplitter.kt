package com.apex.courierkit.dispatch

/**
 * # Courier Command Splitter — 复合指令拆解器（便携扫描器）
 *
 * 勘合核验终端类调用时，把一条复合命令拆成**逐段子命令**：
 * `cd /a && git push; ls | head` → 3 段。每段独立核验（复合命令可产生
 * 多张通行证行），"always 记忆"按段模式持久。
 *
 * 扫描器为便携实现（引号感知的分隔符切分 + 环境前缀剥离 + 路径引用
 * 抽取），不追求完整 shell 文法——误拆的后果只是**多问一次**（更严
 * 不会更松），安全方向正确。
 */
object CourierCommandSplitter {

    /** 拆出的一段子命令。 */
    data class SubCommand(
        /** 段原文（引号保留）。 */
        val text: String,
        /** 首词（剥掉 VAR=VAL 前缀后的命令词，如 `git`）。 */
        val head: String,
        /** 剩余参数串（首词之后，已 trim）。 */
        val argsTail: String
    ) {
        /** 勘合资源串（head + 首个参数——`git push` 而非 `git push -f origin`）。 */
        val resource: String
            get() = if (argsTail.isBlank()) {
                head
            } else {
                head + " " + argsTail.split(WHITESPACE).first()
            }

        /** always 记忆模式（head + 首参 + 尾通配——`git push *`）。 */
        val savePattern: String
            get() = if (argsTail.isBlank()) head else resource + " *"
    }

    /** 引号 / 转义感知的复合命令切分（&& || ; | & 换行为界）。 */
    fun split(command: String): List<SubCommand> {
        val raw = command.trim()
        if (raw.isEmpty()) return emptyList()
        val segments = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index < raw.length) {
            val c = raw[index]
            if (escaped) {
                current.append(c)
                escaped = false
                index++
                continue
            }
            when {
                c == '\\' && quote != '\'' -> {
                    // 反斜杠转义（单引号内为字面）
                    escaped = true
                }

                quote != null -> {
                    if (c == quote) quote = null
                    current.append(c)
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }

                // 双字符分隔符：&& 与 ||
                c == '&' && raw.startsWith("&&", index) -> {
                    segments += current.toString()
                    current.setLength(0)
                    index++ // 吃掉第二个 &
                }

                c == '|' && raw.startsWith("||", index) -> {
                    segments += current.toString()
                    current.setLength(0)
                    index++
                }

                // 单字符分隔符：; | & 换行
                c == ';' || c == '|' || c == '&' || c == '\n' -> {
                    segments += current.toString()
                    current.setLength(0)
                }

                else -> current.append(c)
            }
            index++
        }
        segments += current.toString()
        return segments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toSubCommand() }
    }

    /** 命令引用到的路径（绝对路径 / ~ 开头词——外部目录授权核验用）。 */
    fun referencedPaths(command: String): List<String> {
        val paths = LinkedHashSet<String>()
        for (token in tokenize(command)) {
            val t = token.trim()
            if (t.length > 1 && (t.startsWith("/") || t.startsWith("~/") || t == "~")) {
                paths += t.trimEnd(',', ';', ':')
            }
        }
        return paths.toList()
    }

    // ── 内部 ──

    private fun String.toSubCommand(): SubCommand {
        val tokens = tokenize(this)
        // 剥掉 VAR=VAL 环境前缀（连续多个）
        var start = 0
        while (start < tokens.size && tokens[start].matches(ENV_ASSIGN)) {
            start++
        }
        if (start >= tokens.size) {
            // 整段都是环境赋值——没有实际命令词
            return SubCommand(text = this, head = this, argsTail = "")
        }
        val head = tokens[start].trimEnd(',', ';')
        val tail = tokens.subList(start + 1, tokens.size).joinToString(" ")
        return SubCommand(text = this, head = head, argsTail = tail)
    }

    private fun tokenize(command: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in command) {
            if (escaped) {
                current.append(c)
                escaped = false
                continue
            }
            when {
                c == '\\' && quote != '\'' -> escaped = true

                quote != null -> {
                    if (c == quote) quote = null
                    current.append(c)
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }

                c.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }

                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    private val WHITESPACE = Regex("\\s+")
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")
}
