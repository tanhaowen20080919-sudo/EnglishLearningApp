# v1.0 词条校核与词义评分修复（code 10）

本轮继续处理上一轮词条资源缺口，并修复实际出题链路中的词义排序问题。**仍不能宣布原始 v1.0 全规格完成。** 全词库的教学内容审校及真机验证仍未完成，完整验收状态以 `docs/V1_REQUIREMENTS_AUDIT.md` 为准。

## 实际修复

旧 `StudyEngine.meaning` 直接取字典第一行。原字典里 automatic 的第一行是名词“自动手枪”，swift 是“雨燕”，natural 是陈旧的名词义；这些顺序不适合作为高中学习主提示。本轮以独立资源提供常用义，词卡、拼写提示、词义题答案、干扰项排歧、阅读查词和 AI 词条提示使用同一解析器。

`WordStudyResources.note` 仅在单词及原始完整释义都与内置词库一致时使用整理资料；用户导入的自定义释义继续保留。原词库文件、SQLite 中的完整释义、音标、例句及 1640 个 MP3 不改写。词库详情与学习页可展开查看原资料，含旧义/专业义的文本明确标为备查。

新增 **243** 条原创高中学习卡，每条包含常用义、搭配、英文短例句和中文译文，源文件为 `content/word-study-notes.tsv`。来源明确为 Codex 辅助编写并进行语言核对的原创内容，不是高考真题，不宣称经过独立教师认证。连同原有 WordNotes，实际离线搭配覆盖 **261/1640**。

原 ECDICT 1376 条词形数据保留，另对缺少 exchange 字段的 **264** 词逐词分类，明确不可数、通常不变形、比较级、情态动词及特殊一致关系等用法。例如 basis→bases、status→statuses；information/furniture 不机械加复数，ought 不构造普通动词变化。来源记录在 `content/reviewed-morphology.json`。全部 1640 词均有字典词形或明确用法说明；但原字典汇总的多词性形式尚未全部按高中常用义筛选，因此 **R064 仍为部分实现**，不以非空字段冒充教学内容全覆盖。

## 升级与真实评分

新增资料编译为独立的 `word-study-notes.json`，数据库保持 v6。资源在既有数据库 IO 打开路径加载，解析失败保留旧词库回退，不依赖网络或 AI。

对已开始的当天计划，`WorkflowFactory.refreshReviewedVocabulary` 只更新尚未作答、与旧词头及释义模式匹配的词义题及旧拼写提示；已答前缀、当前已评分题、反馈、选择、掌握度、计时和任务索引不变。修复后重复加载不再改变题目。

新核心义同时加入干扰项的近义排除，避免新增“荣幸”义后仍将同义项当错误答案。语境题反馈引用实际抽到的填词句，不再显示另一条默认例句。

## 验证及正式 APK

- 本地与云端最终源码通过 **146 项 JVM/Robolectric 测试**，0 失败、0 错误、0 跳过；6 项签名恢复安全测试通过。逐例断言结果见 [code10 测试记录](audit/test-results-code10.json)。
- Lint：0 错误、12 警告、1 信息；Debug、明确未签名的验证 Release 和 Android 测试 APK 编译通过。
- API35 模拟器实际执行 **5 项 UI/数据测试**，0 失败、0 跳过：完整快速日、真实评分/拼写/语境、Activity 重建、次日计划、0 次 AI、单层导航与草稿/备份保护。
- 显示本地释义五次原始耗时 **[103, 113, 72, 64, 58] ms**，经验最近秩 P95 **113 ms**。这是该模拟器动作的小样本，不代表全局真机性能。
- [正式 Build APK](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/40045531503) 的验证、UI、正式签名三项任务全部通过；PR #5 独立验证也通过。源码提交 `7c85e176205c00e4da8f03370f3ca645a8b58892`，应用树 `b9c337987a9ca23280658a881ffc7cd767a903ca`；合并后的文档更新不改变受测应用代码。
- 下载后独立核验：固定证书、包名、版本、非调试、APK ZIP 完整性、zipalign、原1640音频和新增资料资产 SHA-256 均通过。机器记录见 [code10 APK](audit/release-code10.json) 与 [云端证据](audit/cloud-verification-code10.json)。
- 正式 APK：`EnglishLearning-v1.0.0-code10-release.apk`；[Actions 下载页](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/40045531503/artifacts/12541766040)。文件 SHA-256：`16e89f74c8bafd1c5f40ba368661d8166c9589d44fbbeb41299c534b7946967e`。

新增测试核对罕见名词排序修复、自定义释义保护、1640 条词形/用法记录、真实 SQLite 评分、旧题修正的幂等性与历史保护、新核心义的干扰项排歧、实际语境反馈。

```sh
python3 tools/build_word_study_notes.py
python3 tools/verify_learning_resources.py
python3 tools/verify_acceptance_coverage.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease -PunsignedRelease=true
```

正式构建继续由 GitHub Actions **Build APK** 使用既有四个 Secrets；不生成、替换私钥。applicationId 为 `com.tanhaowen.contextenglish`，versionName `1.0.0`，versionCode **10**。证书 SHA-256 固定为 `e13229f272357c3d270ae903c1a93bf3b902138282cf24aabe9e8e172d87fbf1`。Release 校验器还核对 APK 内整理资料与源码资产的 SHA-256、实际卡片数和词形记录数。

## 收紧的验收边界

上一轮“核心义显示已完整实现”的判断覆盖了调用链，却没有充分验证原词条的义项排序。本轮发现实际问题后修复，并将 **R061 调整为部分实现**：243/1640 词具有本轮核对的高中常用义，其余仍按原词库取义，不能宣称全部审校。

R062 的原创高中双语例句覆盖243词，R063搭配覆盖261词，R064全量有资料但按常用词义的配套筛选仍不完整。真机全局延迟/声音/系统回收行为没有设备证据；真实中转站未进行付费联调。尚未审校的内容不会靠程序套句、统计行数或构建成功标为完成。

上一正式 code9 的构建与审计记录保留在 `docs/V1_AUDIT_DELIVERY.md` 及 Git 历史。本轮源码回滚点为 `398274cf28c04c339aa1bc32d9cb69924c76433b`，分支 `backup/pre-lexical-completion-20261023`；后续版本继续沿用同一签名并递增 versionCode。
