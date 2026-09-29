# Context English

一个为高考英语薄弱学习者设计的 Android 原生 App：先在语境中理解，再用短练习和本地记录完成复习闭环。

当前版本：`V0.1.0`

## V0.1 已实现

- “今日学习”首页：今日任务、掌握进度、薄弱词和正确率。
- 内置情境阅读：点击文中重点词，直接查看当前语境义、常见义和例句。
- 阅读后练习：3 道选择题、答案、中文解析与逻辑标签。
- 本地词库：30 个高考高频词，支持搜索、学习中、已掌握和薄弱标记。
- 本地学习记录：SQLite 保存单词状态、答题数据、AI 缓存和调用统计。
- 按需 AI：自定义 OpenAI 兼容 Base URL、API Key 和模型；支持测试连接、读取模型、手动生成情境阅读。
- AI 内容生成后缓存到本机；统计调用次数、Token 和可选费用估算。
- 不需要账号或自建服务器；核心学习功能离线可用。
- 不会后台调用 AI，API Key 不写入源码或 GitHub。

## 默认模型

- 日常生成：`gpt-5.6-sol`
- 深度任务预留：`gpt-6-astra`

模型名、Base URL 和价格均可在 App 内修改。只有用户主动点击“测试连接”“读取模型”或“手动生成”时才会联网。

## 构建 APK

仓库已配置 GitHub Actions：

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build APK**。
3. 每次推送到 `main` 会自动构建；也可以点击 **Run workflow** 手动触发。
4. 构建完成后，在运行详情底部下载 `Context-English-v0.1-debug-apk`。

该 Artifact 中的 `Context-English-v0.1-debug.apk` 可直接安装到 Android 8.0（API 26）及以上设备。

## 技术信息

- Kotlin + Jetpack Compose + Material 3
- Android 原生 SQLite
- `applicationId`: `com.tanhaowen.contextenglish`
- `minSdk`: 26
- `targetSdk` / `compileSdk`: 35
- Java 17 / Gradle 8.9 / Android Gradle Plugin 8.7.3

## 隐私说明

学习数据和 AI 设置只保存在本机。为避免密钥进入系统云备份，`ai_settings` 已从数据提取规则中排除。卸载 App 会删除本机数据。
