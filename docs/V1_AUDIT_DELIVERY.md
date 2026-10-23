# v1.0 严格审计与修复交付

## 结论

完整重读原始 DOCX 的 22 章、204 个非空段落及表格，拆成 318 条验收项。首轮发现的问题已落实为实际代码修改，二轮结果为 310 项已完整实现、8 项部分实现、0 项未实现。**尚不能宣布原始 v1.0 规格全部完成**：部分词条资源覆盖不足，真机点击延迟和声音未获设备验收。完整“原始需求 → 文件/函数/逻辑 → 测试断言 → 状态”见 [318 项验收表](V1_REQUIREMENTS_AUDIT.md) 和 [JSON 清单](audit/acceptance.json)。

## 主要改动

- 到期复习优先，学习量同时使用新词真实批次、抽查、拼写、语境、小测、完成率及时间预算；缺少样本使用明确的初始参数，不伪造实际正确率。
- 历史遗忘和有间隔的连续正确真正影响复习间隔。客观错误能推翻乐观自评，稳定掌握要求多形式、多语境和间隔证据。
- 学习中快速/标准/强化/自定义切换保留已完成内容、反馈和所有到期复习；延长时间能够恢复后续阅读、小测和训练。
- 移除旧设置页重复的连续学习天数展示，避免断签归零压力；保留历史数据，继续使用诊断页的真实统计。
- 主入口置于标题下，避免任务摘要把开始按钮挤出小屏首屏；Android 流程测试断言首屏可见。
- 完整日闭环及次日本地计划自动生成；小测错误实际写入记忆模型并改变次日任务。最近七篇阅读按篇而非按天/问题聚合，长期数据另存；阅读错因、所选答案、生词理解影响可恢复。
- 增加 4 篇完整原创高中阅读、8 类共 32 题语法；阅读句法困难可插入真实知识点验证题，证据达到阈值后次日自动加入专项。
- 统一 AI 调度接入严格校验的阅读解析、真实错因解释、长难句、语境义、例句辨析及实际可评分的针对性练习。预生成只由手动按钮触发；本地证据充足时跳过不需要的生成。缓存不能泄漏尚未作答的阅读答案。
- 真实响应模型、计时、缺失与零 Token 分项、当次价格和费用分别保留；JSON 内容错误仍保存上游已返回的实际用量。AI 取消/失败不阻塞本地学习。
- 音频文件加载放到 IO；答题刷新不再反复装载整个词库；数据库和大列表筛选在后台，页面只做 90ms 轻量过渡。用户原有“关闭自动发音”设置保留。

## 架构与数据迁移

主入口 `MainActivity → AdaptiveApp → AdaptiveViewModel`。`DailyPlanner / AdaptiveReview / WorkflowFactory` 为可确定性测试的本地算法；`AdaptiveRepository` 把诊断、词记忆、事件和流程快照放在同一个 SQLite 事务。`StudyClock` 累积前台作答时间并排除后台及答案反馈阶段，供学习/阅读统计使用。

SQLite 升级为 v6；保留 v1～v5 增量迁移路径。v6 补充阅读篇目、新词批次、实际选择与 Token 字段可用性，可靠信息从旧快照回填。升级前保存可恢复的数据库与偏好备份，完整性校验和完成标记通过后才迁移；失败中止或回滚，不清空旧数据。JSON 导出 v6，兼容导入 v2～v6，并在导入中验证记忆范围、流程快照和外键关系。

原 1,640 词、音标、例句与翻译、音频映射及 1,640 个 MP3 按固定哈希核验，未替换原资源。附加语境/词形另存在 enrichment 文件，附 ECDICT 和 Tatoeba 来源、作者及许可。

## AI 调度与缓存

`AiTaskScheduler` 是内容请求的统一入口，单并发并支持取消；生产传输为 `AiClient` 的 HTTPS Chat/Responses。缓存键包含接口、模型、内容类型、输入 SHA-256、版本，有效期 30 天。结构化字段、原文引文、题目 ID、选项和已知正确词义经过校验才进入有效缓存；失败继续使用完整本地题库。准备明日内容逐项保留成功结果，次日先读缓存，不自动联网。

测试中的外部响应在传输边界注入，用于验证生产请求体、解析和调度；没有进行用户中转站的付费 API 联调，不能据此宣称特定服务商已验收。

## 测试与构建结果

- 本地与云端均通过 139 项 JVM/Robolectric 测试，0 失败、0 跳过；6 项签名安全测试通过。逐例本地 XML 摘要见 [测试记录](audit/test-results.json)。
- Lint：0 错误、12 警告、1 信息；Debug、明确未签名的 Release 验证包及 Android 测试 APK 构建通过。
- Android API 35 模拟器实际执行 5 项 UI/数据测试，0 失败、0 跳过，包括完整快速日、Activity 重建、拼写/语境落库、自动次日计划、0 次 AI。原始 XML、截图及耗时文件保存在云端 artifact。
- 显示本地释义的模拟器 P95：214 ms（5 词卡样本、经验最近秩；独立 PR 运行同样 5/5 通过，P95 为 421 ms）；原始样本 [146, 214, 202, 91, 60]。它是此动作的测试测量，不能替代全局真机触控指标。
- 原 1640 词和 MP3 固定哈希、附加语境及署名、v1→v6 迁移保护、完整318项文档追踪均通过。
- [正式构建与全部任务](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/40033105250)：全部成功。APK 的签名、包名、版本、不可调试、ZIP 完整性、1640 MP3 数量、zipalign 和文件 SHA-256 已在云端及下载后独立核验。
- 本轮早期失败及真实修复原因保留在验收清单 `execution.android_ui_attempts`，没有跳过失败测试。外部中转站未做付费联调，真机未连接。

复现命令：

```sh
python3 tools/verify_vocabulary.py
python3 tools/verify_learning_resources.py
python3 tools/verify_migration.py
python3 tools/verify_acceptance_coverage.py
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease=true
./gradlew :app:connectedDebugAndroidTest
```

GitHub Actions → **Build APK** → **Run workflow** → `build_release=true`。Release 依赖 JVM/lint/资源检查和 Android UI 两组任务全部通过；从已有四个 Secrets 还原固定签名，核验证书，构建后验证 APK 并上传 `EnglishLearning-permanent-signed-Release`。任何缺失凭据都会失败，不回退为 debug 或临时签名。

## APK 与固定正式签名

正式 APK：`EnglishLearning-v1.0.0-code9-release.apk`；[Actions 下载页](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/40033105250/artifacts/12536485846)。下载后的完整核验数据见 [release-code9.json](audit/release-code9.json)。

APK 文件 SHA-256：`86d97de3d7ba5496b2874fb7e0cdf762bba0a7dd5f74fc11785051d320c2f584`。正式源码提交：`6e7d952e41039a385c6fba92a95a95e4d873bc24`；PR #4 合并，后续交付文档提交不改变受测应用代码。


- applicationId：`com.tanhaowen.contextenglish`（未修改）。
- versionName：`1.0.0`；versionCode：`9`，高于上一正式构建 code 8。
- 证书 SHA-256：`e13229f272357c3d270ae903c1a93bf3b902138282cf24aabe9e8e172d87fbf1`。
- 本轮只复用已固定的正式 keystore 和已有 Secrets，没有生成、替换、提交任何私钥或密码。
- 同证书的 v1.0 code 8 可直接覆盖为 code 9；更早采用其他证书的旧版按用户先前选择先卸载再装。实际覆盖更新仍以安装设备的签名一致、版本号允许为准。

## 已知问题与未完成边界

1. R062：1640 词保留原中英例句并有额外语境，但尚未逐词完成“简短高考适配例句”的人工校核，通用语料不能冒充审核过的高考词条。
2. R063：可靠离线常见搭配仅审核 20/1640；手动 AI 可以补充，但不能替代全量离线词条库。
3. R064：1376 词有 ECDICT 变形字段，余下 264 词需区分不变形、无必要变形和资料缺失；不能将空字段全部判为完整。
4. R006/R243/R304：已做实际线程/刷新优化和模拟器验收，尚无真机设备验证全局点击延迟、声学质量及厂商后台回收行为。
5. R001/R002：整体交付受上述资源/设备边界约束，保留“部分实现”。
6. 题库为原创及入门内容，规模有限，重复度高时提供手动 AI 补充入口；不声称有官方高考真题/七选五题库。复习参数具备算法和集成测试，尚未完成长期学习效果校准。

首轮基线为 `19bd6d46a216c400de6cb1fdc81d8d4537abbbea`；回滚分支 `backup/pre-v1-functional-audit-20261023` 保留原可运行版本。具体首轮问题见 [第一轮审计](V1_AUDIT_FIRST_PASS.md)。
