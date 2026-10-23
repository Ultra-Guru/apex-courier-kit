# apex-kit-courier

**Pure-Kotlin dispatch-relay agent-loop engine（`courier-dispatch`）** —— 信使
思考逻辑线：收发匣投递语义 · 趟次步进机 · 勘合通行证 · 档房压缩 · 下站
子代理。为移动端 Coding 工作区而生，**零 Android 依赖**，经 composite
build 供 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)
Coding 模式作为可切换的思考逻辑线消费。

## 范式一页

信使线把一次任务循环建模为一座**驿站**：

| 驿传词 | 引擎构件 | 语义 |
|---|---|---|
| 收发匣 `CourierInbox` | steer / queue / 控制件 | 用户输入**先落匣后投递**：steer 在趟次安全边界插队送达、queue 排队等空闲、复合控制件（压缩/调度）自成边界；投递幂等（按 id 去重） |
| 趟次 `CourierTripRunner` | 逻辑步 vs 物理尝试 | 一趟 = 一条助手消息（多次重试/压缩可发生多次物理尝试）；**终止判据 = 本趟无本地工具调用**（needsContinuation）；同趟多工具**并行纤程**、逐调用独立回执落账 |
| 勘合 `CourierPermitGate` | allow / ask / deny | 工具调用前的通行证核验：`*`/`?` 通配则例、**后例覆盖**（画像底 → 会话收窄 → always 记忆顶）、聚合 deny>ask>ask 缺省勘验；ask 悬置成用户问答，**always 记忆落位后自动放行同类悬置**；拒付带反馈 = 模型可见纠正、无反馈 = 缺陷隧道中断趟次 |
| 指令拆解 `CourierCommandSplitter` | 复合命令逐段核验 | `cd /a && git push; ls` 拆三段各发通行证——"always" 按段模式（`git push *`）记忆 |
| 示廊 `CourierBulletin` | 指令价值增量 | 常设指令源（环境 / 日期 / 工作区 / 全局规则 / 项目规则 / 技能索引）逐源读取 → 规范哈希 → **单次增量准入**：epoch 基线进系统提示词，后续变更渲染一次冻结更正行；checkpoint 落位推进 epoch |
| 档房 `CourierArchivist` | 结构化压缩 | 触发线 = 窗口 − max(10%, 16k)；近期 keep 窗口**逐字保留**（用户消息边界切分、调用与回执同段）；远期摘要为**结构化卷宗**（使命/约束/决策/进度/下一步/关键文件/备注）+ 一次纠偏再失败；投递阶梯：原文 → 扁平转写 → 70%/50%/35% 收缩 |
| 下站 `CourierOutpost` | 子代理 = 子驿站会话 | `courier_task` 委派子站（深度受限、前台等待可中断、后台立即回执 + 完成作为合成输入唤醒母站、按站续派）；问答表单 `courier_question`、行程清单 `courier_todos` 同为本地工具 |
| 题匾 `CourierTitler` | 会话命名（可选） | 首次投递后一次性小模型命名（≤50 字符、同语言、CAS 落位） |

容错口径：抖动指数重递（2s 翻倍封顶 10s、≤10 次、retryAfter 采信封顶
15 分钟、传输类紧口 3 次）；**输出已开始的半截流走续跑**（新趟 + 合成
续令）；输出截断续跑上限 2 次（连续 3 条截断即失败）；上下文溢出在
可回滚窗内先压缩再重试一次。

## 模块坐标

```kotlin
// settings.gradle.kts（composite build）
includeBuild("apex-kit-courier")

// 消费方
implementation("com.apex.courierkit:courier-dispatch:1.0.0")
```

库内核**只面向端口编程**（模型客户端 / 工具目录 / 工具执行 / 记忆 /
日志 / 失败分类 / 环境源 / 模型信息源 / 画像目录 / 技能目录 / 命令探针 /
勘合快照源 / 时钟）——宿主侧适配器把 ModelRuntime / ToolRegistry /
CodeConversationMemory 等接进来，循环语义零宿主依赖。

## 构建

```bash
./gradlew :courier-dispatch:assemble   # 编译
./gradlew :courier-dispatch:test       # 纯 JVM 单测
./gradlew publishToMavenLocal          # 本地发布冒烟
```

## 兼容矩阵

| Kotlin | Gradle | JDK | 消费宿主 |
|---|---|---|---|
| 2.2.21 | 9.8.1（wrapper） | 17（toolchain） | Android-Guru-Agent（composite build，`gradle/courier-kit.lock` 钉版） |

宿主工具链升级后本库必须同步（否则下游复合构建解析失败）。

## CI

- **CI**：`build-test`（编译 + 纯 JVM 单测 + mavenLocal 发布冒烟）与
  `consumer-check`（手动触发：检出宿主与其余 kit 钉版 → 宿主
  `:app:compileDebugKotlin` + `:core:code-engine:test`，防下游崩）；
- **Guard Rails**：词汇护栏（改名衍生合规零容忍）+ 公共 API 面快照；
- **Notify Host**：main 全绿 → `repository_dispatch`（`courier-kit-updated`）
  通知宿主即时 bump lock（未配 PAT 时宿主定时轮询兜底）。

## 许可

MIT
