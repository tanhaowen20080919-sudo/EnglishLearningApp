# v1.0 交付报告

> 历史记录：用户随后授权 v1.0 全新安装并建立新的固定正式证书。最新签名、备份及发布流程以 [PERMANENT_RELEASE_SIGNING.md](PERMANENT_RELEASE_SIGNING.md) 为准。

日期：2026-10-23。工作分支：`feat/adaptive-v1`。回滚分支：`backup/pre-v1-upgrade-20261023`（原提交 `fb11df4`）。应用 ID 保持不变，版本 1.0.0 / code 8。

## 主要改动与架构

- 首页以今日学习为主入口；词库、错词、诊断、AI 和设置进入二级页面，学习时隐藏导航。保留蓝/绿主题与原生详情页。
- 新增 `AdaptiveEngine`、`DailyWorkflow`、`AdaptiveRepository`、`AdaptiveViewModel` 与 Compose 界面，复用原词库、SQLite、设置、语音、API 兼容解析和费用算法。
- 完整本地流程包含到期复习、新词主动回忆、随机客观抽查、诊断阅读、语法弱项专项及综合小测。快/标准/强化/自定义时间可调；复习优先削减新内容。
- 本地掌握度、稳定性、难度、近期正确率、遗忘次数、间隔正确次数、题型与语境共同决定复习间隔。自评不能直接形成稳定掌握；抽查错误会显著降权。
- 新词量使用近期新词、抽查、拼写、语境、小测、完成率、积压量和时间预算。小测结果实际写回记忆模型和后续任务输入。
- 阅读按五类题型记录正误、耗时、生词、理解影响及自选错因；语法知识点达到样本和错误阈值后插入专项。统计提供最多三个优先弱项。
- 作答、记忆模型和流程快照事务提交，题目键去重；题目选项、展开状态与反馈持久化。数据库、题目生成和筛选均在后台线程执行。

## 数据库与迁移

SQLite 从 v1～v4 增量升级到 v5，新增 `word_memory`、`learning_days`、`diagnostic_attempts`、`reading_words`、`ai_tasks`，扩展 AI 用量耗时和请求状态。

在迁移事务开始前复制并完整性校验旧数据库，同时保留偏好设置，保存到应用私有 `files/recovery/`。备份失败停止迁移，SQL 失败事务回滚。词库、音标、1,640 份音频及映射不变，可靠的学习状态、错词和 API/模型/价格/费用历史保留。旧页面会话继续保存在兼容表中，新今日流程使用新快照。

JSON 导出升级为 v5，兼容 v2～v4 导入。导入前创建恢复备份；非法状态或快照会使整个导入回滚。普通导出不含 API Key，导入保留本机密钥。旧版备份恢复时清理无法可靠映射的新诊断/流程数据。

## AI 调度、缓存与费用

统一 `AiTaskScheduler` 负责内容请求：本地检查缓存/排队 → 用户点击 → 单并发请求 → 记录真实 usage → 校验 → 缓存。学习事件、启动、切页和计时器不发起内容请求，旧自动联网分发器已关闭。

缓存包含类型、输入哈希、接口/模型、版本、时间、有效期及用量关联；输入或模型变化、版本变化、30 天过期后失效。JSON 缺字段或格式错误不进入有效缓存，核心流程继续本地执行。支持中断网络连接与手动重试。

“准备明日内容”先本地计算计划，再手动批量准备阅读解析、必要的语法讲解。次日读取有效缓存，未开始的任务按最新本地表现核对。“本周分析”发送最近七天的结构化统计。

每次内容请求记录实际响应模型、耗时、四类 Token 与价格快照，按真实 usage 计算费用。请求前建立用量未知记录，进程退出/取消/失败不会伪造用量；未配置 API 导致的本地校验失败不冒充网络调用。缓存命中不重复计费。费用是按配置单价计算的本地金额，服务商实际账单仍以其记录为准。

## 验证结果

- 96 项 JVM/Robolectric 测试全部通过，0 失败、0 错误、0 跳过。覆盖算法、题型结构、弱项专项、费用、缓存命中/失效/校验/取消、离线完整流程、数据库迁移保护、事务回滚、备份恢复和无 AI 配置的 Activity 启动。
- `verify_vocabulary.py` 通过：原筛选 1,633 词全部保留，内置共 1,640 词。
- `verify_migration.py` 通过：实际 v1→v2→v3→v4→v5 SQL 保留核心旧数据。
- Android Lint：0 错误、12 警告、1 提示；警告主要是已有依赖更新建议、资源及版本限定提示。
- Debug APK、unsigned Release APK 构建通过；Android 设备测试源码编译通过。
- Gradle Wrapper 8.9 实际用于最终检查，分发包 SHA-256 固定。GitHub Actions YAML 解析通过，流水线使用 Wrapper 执行测试、Lint、双版本构建及独立模拟器测试。

最终验证命令：

```sh
python3 tools/verify_vocabulary.py
python3 tools/verify_migration.py
./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin -PunsignedRelease=true
```

APK 路径：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release-unsigned.apk`。测试报告：`app/build/reports/tests/testDebugUnitTest/`；Lint：`app/build/reports/lint-results-debug.html`。

GitHub Actions 的 **Build APK** 支持 main 推送、PR 和手动执行，成功后下载 `Context-English-v1.0.0-apks` artifact，包含 Debug/unsigned Release 与 SHA256SUMS。覆盖旧版安装时必须使用原证书签署 Release；本次 Debug 证书不保证与原安装一致。

## 已知限制与待验收

- 本环境没有 Android 模拟器和 KVM，未执行 `connectedDebugAndroidTest` 或真机验收。Robolectric 启动通过不能替代真机点击延迟、音频、手势和系统杀进程测试；相关 UI 测试已更新并放入 CI。
- 未使用真实中转 API 或付费密钥；AI 通过模拟响应验证调度、解析、缓存和记账。真实服务兼容与账单需配置后验收。
- 未推送远端或触发 GitHub Actions，不能把本地流水线对应命令成功等同于远端运行已通过。
- 阅读初始题库为 3 篇；语法为定语从句、时态、非谓语共 12 题，20 个常见词补充搭配/词形。内容会轮换，尚不是完整高考真题库。
- 掌握模型为可解释的本地启发式参数，尚无长期效果校准。稳定掌握对语境证据要求较严；只有原单一例句的词会较慢达到该状态。
- Release 为待签名 APK，未访问或更换生产签名私钥。
