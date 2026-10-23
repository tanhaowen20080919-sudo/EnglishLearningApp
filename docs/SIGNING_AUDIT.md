# 原签名调查与 v1.0 覆盖更新状态

调查日期：2026-10-23。本次实际读取 GitHub 远端源码、历史提交、Actions 运行记录/日志及旧 APK。

**当前尚未生成可确认覆盖原发布版的 v1.0 APK。** 原私钥未在当前环境找到，历史签名 Secret 在本次 Actions 中不可用。未卸载应用、修改应用 ID、生成新正式证书或更换 Gradle 签名设置。

## 远端配置及历史

| 证据 | 结果 |
| --- | --- |
| [远端 main 工作流](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/blob/main/.github/workflows/build-apk.yml) | 构建 `assembleRelease -PunsignedRelease=true`，发布未签名 APK；当前没有签名 Secrets 引用 |
| [远端 Gradle](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/blob/main/app/build.gradle.kts) | 没有配置专用 release keystore；未传 unsignedRelease 时沿用 `signingConfigs.debug`，即当时构建机的默认 Android debug keystore |
| [2026-10-02 引入固定密钥恢复](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/commit/2f49481468d8ba659f51434ab49fc2f5ca77ec86) | 历史 Secret 名称仅发现 `ANDROID_DEBUG_KEYSTORE_BASE64`；Base64 解码到 runner 的 `~/.android/debug.keystore` |
| [2026-10-11 切换本地签名](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/commit/e13bfe03c2f285003d03d80755d34d236eb34282) | 移除 Actions 中的上述密钥恢复步骤，改为云端构建未签名 Release，再本地签名 |
| [签名交付过程文档](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/commit/30f60235fae3b5f993a5c952bd77bf950de33852) | 明确称使用 0.4.1 的固定个人证书本地签名；未将签名私钥上传源码或 APK |
| [0.6.0 远端 README](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/blob/main/README.md) | 继续说明本地构建、原证书签名；记录固定证书指纹 |

完整本地 Git 历史不是 shallow clone；未发现被追踪的 `.jks`、`.keystore` 或签名私钥文件，`.gitignore` 明确忽略这些文件。

## 证书指纹

| 来源 | SHA-256 |
| --- | --- |
| 0.4.1 以后发布文档记录的原固定证书（本次目标） | `99278f5e7fbe0bc2392a2fe3c2afa304f61a5fa438423b1ff3d670d26dcbb31a` |
| 实际下载并验证的 0.3.0 Actions APK | `150c051b3cca5d8d7fa2af9fdf8c579eb840acaa7a958db00b94d7b60300270e` |
| 当前环境已有测试 keystore | `732c0ad0ffa6d58b1e51eaa9761468498c2d4760bcc4706c0f80735bc1cab65a` |

[0.3.0 构建运行](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/37004121227)的日志中 `ANDROID_DEBUG_KEYSTORE_BASE64` 环境值为空，因此该次构建回退到了 runner 的临时调试签名；它不能证明原固定私钥存在于 GitHub。APK artifact ZIP 的 SHA-256 已与 GitHub digest 核对，签名由 apksigner 验证。

最终覆盖兼容性应以手机已安装 APK 的实际证书为准。本次 `99278…bb31a` 目标来自 0.4.1 及以后发布记录，未假定所有旧云端调试包都使用同一证书。

## GitHub Secrets 及实际尝试

- GitHub 连接可读写代码并触发 Actions，但列出 repository Secrets 名称的接口返回 `403 Resource not accessible by integration`；这不等于证明 Secret 已删除。
- 仓库没有配置 Actions Environments；仓库属于个人账号，未发现另一套 environment signing 配置。
- 独立分支 `build/v1-original-signing-audit` 已推送，提交 `d5b32b2`，包含 v1.0 源码及严格签名门禁。远端 main 保持原版本。
- 使用已有的 **Build APK / workflow_dispatch** 实际运行：[39947108742](https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/actions/runs/39947108742)。
- 门禁结果：`Original signing Secret ANDROID_DEBUG_KEYSTORE_BASE64 is unavailable to this run. No APK will be signed and no replacement key will be generated.`
- 构建与正式签名 job 均被跳过，没有生成替代证书或错误签名的正式 APK。公开审计 artifact 为 `original-signing-audit`，不包含 keystore、密码或 Secret 值。

门禁只把原 Secret 解码到新建的 runner 临时文件，验证公钥证书 SHA-256。匹配 `99278…bb31a` 才允许 unsigned Release 构建和 apksigner 签名；缺失、打不开或指纹不符均停止，无自动生成/测试签名降级。恢复原私钥后，可再次手动运行本分支的同一工作流，成功时将产生 `EnglishLearning-v1.0.0-original-signed-release` artifact。

## 原私钥最可能的位置与恢复下一步

1. **当时实际完成 0.4.1／0.5／0.6 本地签名的电脑或旧 Codex 环境的 `~/.android/debug.keystore`。** 虽然文件名叫 debug.keystore，作为固定发布证书的私钥仍可能保存在其中；是否正确只看指纹，不按文件名判断。
2. **当时导出/下载的签名备份文件或包含 keystore 的备份压缩包。** 原文档明确提到“原签名备份”，但没有记录具体备份文件名或路径，不能推断其已保存到当前工作区。
3. **GitHub repository Secret `ANDROID_DEBUG_KEYSTORE_BASE64` 的原始来源或备份。** 当前运行无法使用它；有管理权限的账号可在仓库 Settings → Secrets and variables → Actions 核对是否仍存在。GitHub 不提供读取 Secret 明文的接口，恢复需使用原备份，不能从旧 APK 反推出私钥。

当前找到的 `/workspace/.cloud-setup/android-user/debug.keystore` 是不同证书，不是目标原私钥。对当前 `/home`、`/root`、`/tmp`、`/mnt`、共享目录等常见签名保存位置的文件名检查没有找到其他候选；未读取无关凭据。当前未连接 Android 设备。

下一步需要定位原 keystore 或签名备份（只需先提供位置/文件名，不要在聊天中发送密码），验证其公钥指纹后再使用它签署 v1.0。确认匹配原安装证书后执行覆盖安装，无需卸载、换包名或清空原数据。当前未触碰手机旧版及其本地数据。
