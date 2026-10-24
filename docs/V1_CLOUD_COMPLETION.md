# v1.0 Cloud 最终补齐与外部验收边界（code11）

从 main `d106b5f` / code10 继续，回滚点 `backup/code10-before-final-completion`。完整原文仍为22章、204段及表格、318项，无删减。当前环境内的代码、资源和自动化验证持续复查，外部条件不得伪造；最终执行值见验收清单 `execution`。

## 本轮代码与资源

- 全部 **1640词**各有独立常用义、明确词性、常见搭配、自然英文短句及中文译文，源文件 `content/word-study-notes*.tsv`；没有统一套句补数。原词库、原音标、原例句、1640个MP3及映射固定哈希保持不变。
- `build_word_study_notes.morphology` 按本卡词性/义项筛选词形，剔除词根元数据与无关名词/动词变化；不可数、集合名词、单复数同形、多义词分别有显式政策。补全标准比较级/最高级配对，缺来源的形容词逐词明确分级政策（含well→better/best），不默认给所有形容词套more/most；保留必要变体并说明语境。automatic不显示automatics；wind的风义不套wound；found区分find；lie区分lay/lain与lied；medium媒介用media；focus一般重点用focuses；curious不用文学用法curiouser作为常规比较级。
- `WordStudyResources.load/note` 真实加载format2资产，只有英文词及完整原释义匹配时使用内置资料，保护用户自定义释义。词卡、词义/拼写/语境题、查词、AI输入共用同一常用义解析器。
- `AdaptiveRepository.answer` 在事务内要求已保存计划、当前索引/题目一致且未评分。计划调整后旧UI即使停留在同一索引也不能提交；拒绝时不改掌握度、诊断、计时或快照。已答历史不重判。
- `LearningEnhancements.displayedContext` 将语境解释绑定到屏幕上实际抽中的句子，恢复挖空答案，禁止用另一条默认例句充当当前语境。
- `AdaptiveRepository.performance/weeklySummary` 准确限定七天，周AI数据排除未来日期；`UsageParser` 拒绝整数/加法溢出，缓存分项超过总输入时不伪称完整用量。
- 手动“测试连接”使用独立任务类型并跳过缓存，每次实际验证当前凭据/模型；普通内容请求仍复用有效缓存。真实HTTPS回归先命中内容缓存，再强制刷新收到401，不能伪报成功。
- SQLite保持v6，未增加破坏性迁移；复用既有迁移备份、回滚、事务及恢复测试。applicationId固定，versionName1.0.0，versionCode11，签名证书未变。

## 独立质量检查

英文全部例句在本地运行LanguageTool6.6默认en-GB规则；可修正的语法/表达提示已处理，剩余仅有效英美/Oxford拼写变体，逐条记录理由，不自动应用建议。见[语言质量原始记录](audit/language-quality-code11.json)。规则检查不能认证中文翻译或所有语义，全部原创双语卡另经Codex语言核对，不冒称独立教师认证或高考真题。

词形与Simple English Wiktionary/Kaikki独立快照交叉核对，覆盖683个有可比较范式的动词，以及名词/程度范式；差异逐项辨别同形词、变体、原数据提取瑕疵及本卡义项。见[交叉核对记录](audit/inflection-crosscheck-code11.json)。没有盲目照搬另一词典或复制其定义/例句。

`StudyResourceQualityTest` 验证全量集合、目标词唯一出现、例句去重、双语内容、词性、搭配匹配、词形语义分类、同形词及比较级配对；对错目标、重复条目、漏翻译、错误词性/搭配等回归输入明确失败。`WordStudyResourcesTest` 使全部1640词实际进入生产词义、拼写、语境评分器，不只验证JSON字段。

## 核心链路再次复核

| 原始核心要求 | 实际生产位置/逻辑 | 有意义的验证 |
|---|---|---|
| 今日学习闭环、无AI离线 | DailyWorkflow.create、AdaptiveViewModel.rate/answer/next、AdaptiveRepository.answer，生成并持久化真实步骤 | AuditViewModelIntegrationTest整日真实ViewModel；OfflineDayUiTest真实Android整日、重建、明日计划、0次AI |
| 动态新词量、强度、复习优先 | DailyPlanner.calculate/choose、WorkflowFactory.resize、LearningParameters，信号权重与预算决定任务量 | AuditAlgorithmTest逐个独立改变各信号、缩短后不删到期复习；WorkflowTest |
| 认识/模糊/不认识、随机纠偏、自适应复习 | WorkflowFactory.audit、AdaptiveReview.update、applyEvidence，客观错误改掌握度和到期时间 | AdaptiveEngineTest乐观自评后客观错误、间隔/形式/语境/遗忘次数独立影响；AuditAlgorithmTest |
| 阅读五题型、最近七篇、生词/耗时/错因 | ReadingBank、AdaptiveRepository.skills/readingWord/errorReason/summary，实际落库聚合 | AuditPersistenceTest跨篇/同日多篇区分，真实选择与错因重启后进入AI提示 |
| 约15题小测、次日调整、语法专项 | WorkflowFactory.create、calculateTomorrow、GrammarBank、WeaknessDetector | WorkflowTest检查结构，ViewModel整日错误后次日新词减少/复习与专项增加；新词样本按实际cohort |
| 诊断页、最高1～3项弱项 | AdaptiveRepository.summary、WeaknessDetector.priorities，真实数据与样本阈值排序 | 无样本不显示虚构正确率；词汇可高于阅读/语法；七天与未来记录边界 |
| 手动统一AI、预生成、缓存、失败降级 | AiTaskScheduler.request/queue/cached、prepareTomorrow、LearningEnhancements.plan | AiSchedulerTest缓存/模型/输入/过期/取消/失败费用；ViewModel次日无网络读缓存、无自动请求 |
| 当前语境、长难句、解析、针对练习 | LearningEnhancements各typed schema，真实原文证据校验，生成题进入同一评分路径 | EnhancementContractTest拒绝伪造证据/ID/标签/非整数答案；生成练习实际作答更新诊断 |
| Token/费用/实际模型 | AiClient生产HTTPS、UsageParser、recordAiUsage、价格快照 | UsageAndCompatibilityTest四类用量/实际分区/未知字段；AiSchedulerTest实际返回模型、耗时、单次/今日/累计 |
| 安全迁移、持久化、异常退出 | EnglishDatabase迁移备份和事务、WorkflowCodec快照、StudyClock | v1～v6迁移与失败回滚，旧JSON恢复事务，重复/旧UI评分拒绝，重建恢复 |
| 点击延迟、单层导航、轻过渡 | IO数据库/音频/大列表、AdaptiveApp、SpeechController | Android导航/草稿/搜索/备份及响应时间证据；真机指标不冒认 |

## 最终执行记录

本轮最终完整JVM/Android云端验证、固定证书Release与下载后独立校验完成后，在此和`acceptance.json.execution`写入实际记录。未完成执行不得当作通过。

## 仅保留外部验收

详见[外部验收清单](audit/external-acceptance-code11.json)：真实手机的触控/发音/系统回收体验，用户个人API/中转站实际付费联调，以及wind/wound/tear/live/lead/lie/bear等少量多义/异读教学与原录音终审。它们不阻塞当前Cloud可执行的代码、资料和自动化检查，也不被伪造为已完成。
