# English Learning

个人高考英语 Android App，本地学习，无需账号和服务器。当前版本 **0.2.0**（versionCode 2）。

## 本次升级

- 内置 1,640 词：用户此前的《高考重点词汇表_乱序.txt》全部 1,633 词，加上保留的 7 个原有情境词。词表完整保留，不宣称覆盖官方全部考纲词。
- 每个词都有中文释义、词性、音标、英文例句和中文翻译。词典补充来自 ECDICT（MIT），部分例句来自 Tatoeba（CC BY 2.0 France）；许可和作者署名保存在 assets 中。
- SQLite v1 → v2 增量迁移，保留旧版学习状态和记录。新增错误次数、掌握度、连续正确次数、收藏、复习时间等字段。
- 每日自动安排到期词、薄弱词、学习中词及新词；计划按日期保存在本机，默认每天 20 个新词。
- 单词三档反馈：认识、模糊、不认识；答对按 1/3/7/14/30 天延长间隔，不认识 5 分钟后到期并在本轮重新出现，模糊 4 小时后到期。连续四次正确才进入已掌握。
- 保留情境阅读及阅读练习，新增词汇搜索、状态筛选、收藏和单词详情。
- AI 助手：今日建议、薄弱分析、单词讲解、例句、词汇短文、小测、错词诊断、今日/7天报告。全部手动触发，最多发送 20 个重点词，短文最多 12 词。
- AI 小测解析为可作答的选择题，本地评分；错误单词进入薄弱词；生成内容和小测结果保存本机历史。
- 自定义 Provider、HTTPS Base URL、API Key、模型名称。Key 使用 Android Keystore 加密，迁移旧版明文配置。GET 模型列表测试不调用生成接口。
- JSON 数据备份/恢复包括单词状态、收藏、学习记录、每日计划、AI 历史、学习设置；不包含 API Key。导入使用事务，无效文件不会覆盖原数据。
- “我的”页面支持每日新词量、每日作答目标、音标、自动发音、统计、备份、清空记录和版本信息。新词量变更从下一天计划生效。

## 构建和下载

推送 main 自动运行 **Build APK**。现有 workflow 已验证成功，未为改名调整它，因此下载包仍叫 `Context-English-v0.1-debug-apk`；包内 APK 的应用版本是 **0.2.0**。Android 8.0 及以上可安装。

当前流水线沿用 Android debug 签名。不同云端运行的 debug 证书可能不同，不能保证覆盖安装旧 APK。如果系统提示签名不匹配，先在旧 App 导出备份再处理安装；后续应配置固定签名证书。不要卸载尚未备份的数据。

## 验证

```sh
python tools/verify_vocabulary.py
python tools/verify_migration.py
gradle :app:assembleDebug
```

本地静态和 SQLite 迁移验证不调用 AI。AI 实际生成需用户自行配置服务商并手动触发；开发期间不使用用户密钥测试付费请求。发音依赖设备英语 TTS 语音包。

## 技术信息

Kotlin + Compose + Material 3，原生 SQLite，applicationId `com.tanhaowen.contextenglish`，minSdk 26，compileSdk/targetSdk 35，Java 17，Gradle 8.9，AGP 8.7.3。

正式词库资源：`app/src/main/assets/vocabulary.json`。替换词库资源并提升数据库版本即可通过相同导入结构更新词库，无需改写学习核心代码。
