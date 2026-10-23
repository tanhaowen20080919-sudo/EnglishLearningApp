# v1.0 起的长期正式 Release 签名

用户已明确同意首次卸载旧版后全新安装 v1.0，不再延续此前丢失的旧签名。此后 **v1.1、v1.2、v2.0 等必须沿用本次私钥**，不得因换环境或密码遗失自动生成替代证书。

## 固定身份

- applicationId：`com.tanhaowen.contextenglish`，不变。
- 首个正式版本：1.0.0，versionCode 8。
- alias：`englishlearning-release`。
- keystore：JKS，RSA-4096，SHA256withRSA，证书有效期 36,500 天（约 100 年）。JKS 支持独立的 store/key 密码。
- 证书 SHA-256：`e13229f272357c3d270ae903c1a93bf3b902138282cf24aabe9e8e172d87fbf1`。
- 公共证书及指纹记录：[release-certificate.pem](../config/release-certificate.pem)、[release-signing.json](../config/release-signing.json)。它们不含私钥，可以公开。

正式私钥在本次任务中一次性创建；仓库、Gradle 和工作流都不包含生成私钥的命令。正式 Release 不再使用 `signingConfigs.debug`，缺失凭据、alias 不符、私钥密码错误或证书指纹变化均停止。不要为了让错误消失而修改固定指纹或生成新的 keystore。

## 本次正式构建结果（2026-10-23）

- APK：`app/build/distributions/v1.0.0-permanent/EnglishLearning-v1.0.0-release.apk`，1.0.0 / code 8，包名及证书均与上述固定身份一致。
- APK 文件 SHA-256：`726f24a2299e7bb4f1b54fe71333fc2bf9ae86b22bac6d0e8c362502bf965c82`。文件哈希与证书指纹用途不同；后续 APK 文件哈希会随版本改变。
- `apksigner verify`、包名/版本/不可调试检查、APK ZIP 完整性、`zipalign` 均通过；包含 1,640 份离线音频。
- 96 项 JVM/Robolectric 测试、6 项签名安全测试通过；Lint 0 错误（原有 12 警告）。本地正式 Release 构建及增量重建通过。
- 备份 ZIP 解出同一份加密私钥，Base64、密码文件与固定公共证书一致；实际恢复脚本通过。缺失签名凭据的 Gradle 校验按预期拒绝继续，APK 校验器拒绝调试证书。
- 没有连接用户手机，未执行卸载或真机安装。GitHub Secrets 的人工配置仍需按下文完成，不能将本地签名成功视作云端 Secrets 已保存。

## 必须下载并长期备份的材料

本次私有恢复材料位于仓库之外 `/workspace/private-release-signing/EnglishLearningApp-v1/`：

1. `EnglishLearningApp-release-keystore-backup.zip`：含加密的 `englishlearning-release.jks`、`keystore-base64.txt`、公共证书、身份记录和备份说明；**不含密码**。
2. `EnglishLearningApp-release-passwords.txt`：store 密码、alias 和独立 key 密码；请与 keystore 分开保存到密码管理器。

务必将这两份材料下载到 Codex 环境之外；至少保存两份持久备份，例如加密云盘和离线介质。GitHub Secrets 不能当作唯一备份，因为其明文不能再下载。丢失私钥或密码将导致不能正常覆盖更新；APK 和公开证书不能恢复私钥。不要将备份包、Base64 文件或密码作为公开 Git 文件、Issue 附件或 Actions artifact 上传。

## GitHub Actions Secrets

仓库设置入口：<https://github.com/tanhaowen20080919-sudo/EnglishLearningApp/settings/secrets/actions>。

在 **Repository secrets** 中创建以下四项，名称必须完全一致，不要沿用旧的 `ANDROID_DEBUG_KEYSTORE_BASE64`：

| Secret 名称 | 应保存的值 |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | 备份 ZIP 中 `keystore-base64.txt` 的全部内容。它是本次正式 JKS 文件的单行 Base64，不是 ZIP、不是路径，也不是测试 keystore |
| `ANDROID_RELEASE_STORE_PASSWORD` | 单独密码备份中同名 `=` 后的完整值 |
| `ANDROID_RELEASE_KEY_ALIAS` | `englishlearning-release` |
| `ANDROID_RELEASE_KEY_PASSWORD` | 单独密码备份中同名 `=` 后的完整值，与 store 密码分开保存 |

当前 GitHub 集成调用仓库 Secrets 公钥/写入所需接口返回 **403 Resource not accessible by integration**，因此本次无法自动把值存入 GitHub；不是已经配置成功。用户只需在 GitHub 页面填写，不需要在聊天中发送任何 Secret 或密码。

有 Secrets 写入权限且已登录 `gh` 的电脑，也可用 Java 17 + Python 3 自动导入（使用现有备份，不生成新证书）：

```sh
python3 tools/upload_release_secrets.py \
  --repo tanhaowen20080919-sudo/EnglishLearningApp \
  --keystore /安全位置/englishlearning-release.jks \
  --passwords /安全位置/EnglishLearningApp-release-passwords.txt
```

脚本先核对公共证书，再通过标准输入向 `gh secret set` 提供值；不把密码或 Base64 放到命令行，也不打印其内容。

## Actions 构建方式

- main 推送及 PR：执行词库、迁移、签名安全测试、96 项 JVM/Robolectric 测试、Lint 和明确标注的验证构建；PR 和手动运行另外执行 Android 模拟器 UI 测试。PR 不接触正式签名 Secrets。
- **Build APK → Run workflow**（保持 `build_release` 勾选，默认开启）或推送 `v*` 标签：验证通过后，恢复上述四项 Secrets，核对固定指纹，再构建正式签名 Release。仅做云端验证时可取消勾选，不读取正式 Secrets。
- 正式 job 不使用 Gradle 缓存或 configuration cache；keystore 只恢复到 runner 临时目录，任务结束清理，不进入 APK、源码或 artifact。
- 下载 artifact：`EnglishLearning-permanent-signed-Release`，含正式 APK、`RELEASE.json`、`SHA256SUMS` 和公共证书。
- Secret 未配置或证书不符时，正式 job 失败，不会用调试签名补位，也不会将 unsigned APK 冒充正式包。

本地使用同一密钥：设置 `ANDROID_RELEASE_KEYSTORE_FILE` 为 JKS 路径，并从安全存储注入上述三个密码/alias 环境变量，然后执行：

```sh
./gradlew --no-daemon -Dorg.gradle.configuration-cache=false :app:assembleRelease
python3 tools/verify_release_apk.py app/build/outputs/apk/release/app-release.apk
```

离线验证明确使用 `-PunsignedRelease=true`；这只生成带 unsigned 名称的验证文件，不代表正式发布。日常 Debug 构建继续使用测试证书，不具有正式升级身份。

## 后续升级约束

每次发布只递增 `versionName` 和 `versionCode`：例如 v1.1 code9、v1.2 code10、v2.0 使用更大的 code。applicationId、正式私钥和固定证书指纹保持相同；正常安装更新即可保留 v1.0 起的应用数据。数据格式变化仍需独立执行数据库迁移测试。

本次 v1.0 与旧签名不兼容，按用户选择由用户先卸载旧版再全新安装；开发环境没有连接手机，也未执行卸载。后续不要重复卸载或清空数据。
