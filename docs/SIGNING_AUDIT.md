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

完整本地 Git 历史不是 shallow clone；进一步逐对象检查了 38 个提交中的 1,820 个历史文件对象、2 段长 Base64 候选及 5 个 ZIP/JAR 对象，未发现 JKS/JCEKS、PKCS#12 或 PEM 私钥，也未发现内嵌 Base64 的 keystore 备份。`.gitignore` 明确忽略 `.jks`、`.keystore`。扫描只输出对象 ID、路径和是否匹配，不输出密钥内容。

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

1. **此前实际完成 0.4.1／0.5／0.6 签名的旧 Codex / Work 环境中的 `~/.android/debug.keystore`。** 用户已确认没有在自己的电脑管理过 keystore；“本地签名”在项目文档中很可能指旧 Codex / Work 的构建环境，而不是用户电脑。 虽然文件名叫 debug.keystore，作为固定发布证书的私钥仍可能保存在其中；是否正确只看指纹，不按文件名判断。
2. **当时导出/下载的签名备份文件或包含 keystore 的备份压缩包。** 原文档明确提到“原签名备份”，但没有记录具体备份文件名或路径，不能推断其已保存到当前工作区。
3. **GitHub repository Secret `ANDROID_DEBUG_KEYSTORE_BASE64` 的原始来源或备份。** 当前运行无法使用它；有管理权限的账号可在仓库 Settings → Secrets and variables → Actions 核对是否仍存在。GitHub 不提供读取 Secret 明文的接口，恢复需使用原备份，不能从旧 APK 反推出私钥。

当前找到的 `/workspace/.cloud-setup/android-user/debug.keystore` 是不同证书，不是目标原私钥。对当前 `/home`、`/root`、`/tmp`、`/mnt`、共享目录等常见签名保存位置的文件名检查没有找到其他候选；未读取无关凭据。当前未连接 Android 设备。

下一步需要定位原 keystore 或签名备份（只需先提供位置/文件名，不要在聊天中发送密码），验证其公钥指纹后再使用它签署 v1.0。确认匹配原安装证书后执行覆盖安装，无需卸载、换包名或清空原数据。当前未触碰手机旧版及其本地数据。


## 扩展检查：全部旧构建和实际 APK 对比

已列出仓库全部 21 次 Actions 运行（含本次签名验证）和 29 个现存 artifacts，没有找到单独的 keystore/签名备份 artifact。存在的 14 条 Actions cache 名称全部为 Gradle 发行版、依赖、transforms 或 Gradle home；历史工作流没有缓存 `~/.android` 的步骤。缓存结论来自名称和配置检查，并未声称下载检查了缓存内容。

已实际下载校验全部 6 份可安装的历史云端 APK，并检查 0.5/0.6 主分支未签名 Release。每份下载 ZIP 的 SHA-256 均与 GitHub artifact digest 一致。详细文件散列及 artifact ID 见 [SIGNING_APK_CERTIFICATES.json](SIGNING_APK_CERTIFICATES.json)。

| 实际版本 / 构建 | APK 签名 SHA-256 |
| --- | --- |
| 0.1.0 bootstrap（11049419312） | `6755863cb3f7aae2706074f5e3c2625803ec1e2c4605563d766a861f615f89dc` |
| 0.1.0 bootstrap fix（11049998098） | `af95e399c635de102af7f5479cfd1454fbe1e65cc03af0c58ccbad1bb44d8d01` |
| 0.1.0 main（11066005059） | `bfc1c3fc519f63d6e5c299bc9c594d378025f31224a288c78240998a518dcf54` |
| 0.2.0（artifact 名称误标为 0.1） | `2e00bcc1cec1dfe7f7bf854fead43d0c3e268905484279e0a323239a63a1f84e` |
| 0.3.0 | `150c051b3cca5d8d7fa2af9fdf8c579eb840acaa7a958db00b94d7b60300270e` |
| 0.4.0 | `070dad6cd4a866ab74f867d04b6b55498e69f5280c6ea75c6376fab430c29a3a` |
| 0.5.0 main Release | 未签名，无可用 signer certificate |
| 0.6.0 main Release（artifact 名称误标为 0.5） | 未签名，无可用 signer certificate |

这组实际指纹说明：早期云端构建没有稳定复用同一签名；较新云端 Release 原文件本身不能直接安装。不能从构建成功、artifact 名称或相同包名推出可直接覆盖安装。

**仍缺手机当前旧版 APK 的实际证书。** 当前没有手机连接，也没有用户手机旧 APK 文件；上表只能证明 GitHub 历史产物，不能冒充已对手机当前安装包完成比对。已请求旧版版本号和原 APK 文件名作为对应线索；准确匹配最终需要旧安装包的公开证书指纹。

若能重新访问原 Codex / Work 环境，应先只读保全并核对其原 `~/.android/debug.keystore` 及签名备份，不运行会创建默认 keystore 的构建命令、不覆盖旧文件。只有证书与手机旧版一致时才继续签署 v1.0；若原私钥既未备份、又不再存在于原环境或 Secrets，无法从 APK 的公钥证书恢复该私钥。


## 用户补充与手机指纹获取

用户确认旧版主要来自 GitHub Actions / 以前的 Codex / Work 开发流程，没有在个人电脑手动保存或管理过 keystore；不应依赖用户回忆版本号或要求用户查看/发送 Secrets。

0.4.0 构建运行 `37816140845` 的 build job `113445154848` 中，历史 `ANDROID_DEBUG_KEYSTORE_BASE64` 环境值同样为空。其不同证书来自实际 APK 校验，而不是仅依据可选的“Restore fixed debug signing key”步骤显示成功。GitHub Releases 列表为空；Git 中可见源码版本依次为 0.1.0/code1、0.2.0/code2、0.3.0/code3、0.4.0/code4、0.5.0/code6、0.6.0/code7、1.0.0/code8，0.4.1/code5 的已签包未在 Git 历史或现存 Actions APK artifacts 中找到。

手机可使用 App Manager（F-Droid 官方收录：<https://f-droid.org/packages/io.github.muntashirakon.AppManager/>）在无 Root 模式下查看当前已安装应用的签名信息，找到包名 `com.tanhaowen.contextenglish`，复制签名证书 SHA-256；也可以只分享/导出该应用的 APK，再由开发端读取公开证书。不要把 APK 文件自身的 SHA-256 当成签名证书 SHA-256。查看或导出 APK 不需要卸载旧 App、清空其数据或提供密码。

仍需实际旧安装的公开证书来确定目标；证书指纹本身不能恢复私钥。若对应私钥只存在于已经销毁的旧 runner/Work 环境且没有任何备份，目前无法仅凭源码、Git 历史或旧 APK 再造同一签名。本次没有采取卸载、换包名、新建签名或绕过 Android 签名校验的替代方案。
