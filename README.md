# English Learning v1.0

在原 Android 项目上升级的高考英语自适应学习系统。应用 ID 保持 `com.tanhaowen.contextenglish`，版本 `1.0.0` / code `10`，支持 Android 8.0 及以上。

## 学习方式

首页只有“今日学习”主线：到期复习 → 新词主动回忆 → 随机抽查 → 诊断阅读 → 必要的语法专项 → 综合小测。词库、错词、诊断、AI、设置和备份放在“更多”。蓝色及绿色主题保留，学习时隐藏底部导航。

- 新词先显示单词、原音标和发音，点击“显示释义”后再展开。保留 1,640 词及原离线美式音频，进入词卡按设置播放两遍。可靠的原词义、例句、翻译与授权文件保持不变；常见词补充搭配和词形。
- 自评提供弱信号；随机词义、拼写和语境测试提供客观证据。客观错误显著降低掌握度、缩短间隔。稳定掌握要求多次有间隔的正确、多种题型和不同语境。
- 默认约 20 新词，根据近期新词、抽查、拼写、语境、小测及完成率在 15～25 内调整。时间不足或复习积压时可进一步减少。全部到期复习保留，并明确提示预计时长可能超出目标。
- 快速 15 分钟、标准 45 分钟、强化 60 分钟和自定义时间。标准/强化小测 15 题：5 新词、4 历史词、2 拼写、2 语境、2 语法；首次没有历史词时相应题位用于词库巩固。
- 阅读记录主旨、细节、推断、词义和指代五类题型的正误、耗时、生词、理解影响和自选错因。最近七篇阅读用于题型诊断；语法按知识点统计，至少三次样本且正确率低于 65% 才识别弱项。
- 题目、选项、自评展开状态、反馈和进度存入 SQLite；作答、掌握度和快照通过一个事务提交，重复点击不重复计分。AI 未配置或断网不影响学习。

## AI

所有 AI 内容请求经过 `AiTaskScheduler`，仅由明确的手动按钮触发。旧自动调用入口已禁用。调度器单并发，缓存键包含类型、输入 SHA-256、接口、模型及版本，缓存有效期 30 天。结构化解析先校验再入库；失败保留本地流程，可取消并手动重试。

“准备明日内容”先由本地计算次日任务，再手动按真实需要批量生成阅读解析、弱项语法讲解和针对性练习；近期题库重复过高时可补充小测。次日直接读取有效缓存，未开始的计划会按最新本地表现重新核对。“AI 分析本周表现”仅发送最近七天的结构化真实统计。

每次内容请求记录实际响应模型、耗时、输入/输出/缓存写入/缓存读取 Token、当时每百万 Token 单价和费用；请求发送前建立未知用量记录，进程中断、失败或取消时不会伪造用量。费用根据真实 usage 和用户配置单价计算，服务商未返回的分项标记不完整。缓存命中不增加请求或费用。API Key 继续使用 Android Keystore 加密，普通 JSON 备份不导出密钥。

## 数据与升级

SQLite v1～v5 增量迁移到 v6。升级前在应用私有目录 `files/recovery/v<旧版本>-<时间戳>/` 保存旧库和偏好设置，并校验数据库完整性；`COMPLETE` 标记代表备份完成。备份失败中止升级，SQL 迁移失败由事务回滚。保留词库、音标、音频映射、学习状态、错词、API/模型/价格配置及费用历史。v5 增加记忆模型、每日流程、诊断作答、生词记录与 AI 任务缓存；v6 保存真实作答选项、阅读篇目身份、新词批次和 Token 分项是否由上游返回，并回填可可靠恢复的 v5 数据。

“更多 → 设置与备份”可以导出/导入 v6 JSON，兼容 v2～v5 备份。导入前同样备份现有数据库，导入失败回滚。恢复旧版 JSON 时，新的诊断和流程数据会清理，旧词汇状态仍可作为新算法的初始信号。密钥保留本机现有值。

恢复升级前的原始备份需先停止应用，再将备份的数据库和偏好设置恢复到同一应用的原位置；需保留原 Android Keystore。普通导出备份更适合跨设备恢复。调试包可用 `adb run-as com.tanhaowen.contextenglish` 访问私有恢复目录。

## 构建

Java 17、Android SDK 35 / build-tools 35.0.0；Gradle Wrapper 固定 8.9，AGP 8.7.3，Kotlin 2.0.21。

```sh
python3 tools/verify_vocabulary.py
python3 tools/verify_learning_resources.py
python3 tools/verify_acceptance_coverage.py
python3 tools/verify_migration.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease=true
# 连接 Android 手机或模拟器后执行 UI 测试
./gradlew :app:connectedDebugAndroidTest
```

输出：

- `app/build/outputs/apk/debug/app-debug.apk`：调试签名，可用于测试安装。
- `app/build/outputs/apk/release/app-release-unsigned.apk`：明确的未签名验证构建，不能直接安装。
- `app/build/outputs/apk/release/app-release.apk`：配置正式凭据后构建的长期固定签名 Release。

GitHub Actions 的 **Build APK** 在 main/PR 上执行验证；手动运行或 `v*` 标签会使用固定正式证书构建 Release。正式 artifact 为 `EnglishLearning-permanent-signed-Release`；必须先配置四项 `ANDROID_RELEASE_*` Secrets。详见 [长期正式签名与 Secrets 配置](docs/PERMANENT_RELEASE_SIGNING.md)。

v1.0 起的正式证书指纹固定为 `e13229f272357c3d270ae903c1a93bf3b902138282cf24aabe9e8e172d87fbf1`。v1.0 起采用新建且长期固定的正式证书，首次按用户选择卸载旧版后全新安装，后续版本沿用同一证书覆盖更新。

## 验证与限制

测试覆盖掌握度、自适应间隔、动态学习量、弱项排序、费用、离线闭环、缓存失效、取消、实际模型记录、v4 迁移备份及失败回滚、JSON 恢复事务和 Activity 启动。最新词条评分修复见 [词条跟进](docs/V1_LEXICAL_UPDATE.md)。本轮严格审计见 [318 项完整验收表](docs/V1_REQUIREMENTS_AUDIT.md) 和 [本轮修改与验证结果](docs/V1_AUDIT_DELIVERY.md)；此前交付报告保留为历史记录。正式签名与备份规则见 [长期正式签名](docs/PERMANENT_RELEASE_SIGNING.md)。

内置 4 篇 260～420 词原创高中阅读和 3 篇入门阅读，覆盖五类题型；语法为 8 个知识点 / 32 道本地题。原创题不称官方高考真题。1,640 词各有至少两个附加离线语境，1,376 词有 ECDICT 词形资料；本轮243词有原创高中常用义/双语例句/搭配，连同旧资料搭配覆盖261词。原264个空词形字段已逐词分类，全部1640词有字典词形或用法说明，但按高中常用义的全量配套筛选尚未完成。其余词的高考适配例句、可靠搭配及词形必要性还未逐词校核，因此不能宣布原始 v1.0 规格全部完成。算法参数集中、可测试，尚未经长期学习效果研究校准。真机触控、声音和系统退出行为需设备验收；真实中转 API 需配置后验收，开发测试使用模拟响应、不产生付费调用。

原有版本记录见 [历史说明](docs/HISTORY.md)。ECDICT、Tatoeba 与离线发音许可及映射继续随原 assets 提供。
