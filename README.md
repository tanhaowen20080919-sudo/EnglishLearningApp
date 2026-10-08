# English Learning

个人高考英语 Android App，本地学习，无需账号和服务器。当前版本 **0.4.0**（versionCode 4），applicationId 保持 `com.tanhaowen.contextenglish`，支持 Android 8.0 及以上。

## 0.4.0 更新

- 沉浸式、无图片的四选一学习：学习时隐藏主导航，一题一屏；即时本地判题、颜色反馈、可关闭的短音效和轻振动。正确后默认自动继续，错误后展示解释并手动继续。
- 新词和复习穿插；错词延后重现且限制重试次数。支持提示、拼写模式、暂停续学和本轮结果。提示后的答对不算独立掌握；答题记录原子保存且防重复。
- 单词详情使用原生底部面板，释义、例句、收藏、薄弱词和 AI 解释不再跳网页。学习偏好移入“我的”。
- 自动 AI 仅在前台学习且反复出错时按需排队，最多合并 3 词、单并发；不阻塞本地学习。结果缓存后可加入后续情境练习。退出学习或 App 进入后台后不发新请求；已发送请求可完成并记账。
- 自动 AI 默认开启，可关闭；需先配置模型、密钥及正数输入/输出价格。每日最多 5 次，默认本地预算为配置货币的 0.10；费用仅按填写价格估算，不是服务商硬限额。用量不明时当天暂停，失败不自动重试；任务和费用可查看。
- 保留 1,640 词及现有学习数据，SQLite v1/v2/v3 增量迁移到 v4；备份包含会话和自动 AI 记录，密钥仍不导出。
- 点击单词可朗读，优先设备离线 en-US 语音，语速可调。美式真人语音包尚未提供；预留 `assets/pronunciation/manifest.json` 接口，当前为空并回退到系统 TTS。

下面为旧版更新记录；其中“AI 全部手动触发”自 0.4.0 起由上述按需自动机制替代。

## 0.3.0 更新

- 词库资源保持上一版不变：1,640 词（原有筛选词表 1,633 词和 7 个旧情境词），保留释义、音标、中英例句及许可署名。
- AI 学习中心新增通用英语问答、单词讲解（允许词库之外的词）、句子结构分析、阅读翻译/逐句解释/重点词/长难句/主旨/解题思路，以及作文批改、语法检查、用词优化、句式升级、参考评分、改写和参考范文。
- 保留今日建议、薄弱分析、例句、词汇短文、可作答小测、错词诊断、学习报告和本地回复历史。支持手动重试、复制结果和清空显示。
- AI 请求全部由用户手动触发；启动、页面切换、生命周期恢复和计时任务均不调用 AI。设置中的“保存并测试”会发送一条小请求并统计用量；读取模型列表只做 GET 请求。
- Base URL、API Key、模型和接口类型可配置，支持 Chat Completions / Responses。新安装不预设模型名称；保留旧版已保存设置。密钥继续使用 Android Keystore 加密，不进入普通备份和日志。
- 统一解析 OpenAI、DeepSeek 和兼容网关的输入、输出、缓存写入、缓存读取 Token。缺失字段显示 0，并提示用量不完整。支持自动判断及手动设置输入是否已包含缓存。
- 模型价格统一为每 1M Tokens，支持普通输入、输出、Cache Creation、Cache Read 和货币设置；0 表示该项不计费。缓存 Token 从普通输入中排除，不重复计费。费用是根据用户填写价格计算的本地估算，不自动查价或换汇。
- 每次请求保存 Token、分项费用及当时价格快照；结果可展开查看。今日和累计统计按货币分别合计，提供最近请求详情和需确认的统计重置。后续改价不会修改历史费用。
- 页面使用 160ms 淡入淡出和独立状态保存，切页保留输入、筛选和滚动位置。去掉整库的每分钟轮询；本地数据在需要时和恢复到前台时更新。语音引擎按需初始化并在页面之间复用。
- 底部导航统一为单行“今日、学习、词库、AI、我的”，去掉重复的简称层。
- 新词同优先级随机排序，新增未掌握词优先的随机学习入口；既有词条与学习算法仍保留。
- SQLite v1/v2 → v3 增量迁移只扩展 AI 用量与缓存关联，不重新导入旧版词库、不清空学习数据。
- 新版 JSON 备份包含学习数据、AI 回复、AI 用量费用、学习设置和不含密钥的 API 配置。支持导入旧版 v2 备份，旧备份缺少的 AI 用量与配置保留当前值。无效数据通过事务回滚，费用小数不截断。

## 构建和验证

推送 main 或向 main 提交 PR 自动运行 **Build APK**，执行词表检查、v1/v2/v3 数据迁移检查、学习队列与 AI 限额及费用/接口单元测试、Android Lint 和 APK 构建；另一个 Android 模拟器任务检查沉浸式答题、详情面板、暂停续学、主要页面和真实数据库备份恢复，并保存测试截图。

```sh
python tools/verify_vocabulary.py
python tools/verify_migration.py
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
gradle :app:connectedDebugAndroidTest
```

APK 位于对应运行的 **Artifacts → Context-English-v0.4.0-debug-apk**。验证报告也会上传。

## 安装和签名

保留原有 Debug 构建。流水线可从加密 Secret `ANDROID_DEBUG_KEYSTORE_BASE64` 恢复固定 Debug 签名文件；没有该 Secret 时沿用原有临时 Debug 签名。签名私钥不进入源码或 APK。

旧版云端 Debug 证书未固定，不能保证直接覆盖安装。若 Android 提示签名不一致，务必先在旧 App 的“我的 → 导出数据”备份，再处理安装，随后恢复备份并重新填写密钥。不要卸载尚未备份的数据。

## 技术信息

Kotlin 2.0.21 + Compose + Material 3，原生 SQLite，compileSdk/targetSdk 35，Java 17，Gradle 8.9，AGP 8.7.3。词库文件 `app/src/main/assets/vocabulary.json` 本次未修改。ECDICT（MIT）和 Tatoeba（CC BY 2.0 France）许可及署名继续保存在 assets。

缓存字段规则参考供应商文档：
- [OpenAI Prompt Caching](https://developers.openai.com/api/docs/guides/prompt-caching)
- [DeepSeek Context Caching](https://api-docs.deepseek.com/guides/kv_cache)
- [Claude Prompt Caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)

验证过程不使用用户的付费 API Key；真实生成需用户配置服务商。发音目前依赖设备的英语 TTS 语音包，不宣称是真人录音。
