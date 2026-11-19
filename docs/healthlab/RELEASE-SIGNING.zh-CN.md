# HealthLab 版本与发布签名

普通开发用 `./gradlew :androidApp:assembleDebug`。Debug 包使用独立的 `.debug` 应用 ID，可与已安装的 HealthLab `.40` 共存，但不会继承它的同步设置或数据。不要把 Debug APK 作为发布附件。

## 版本规则

| 输入 | Android versionName | Android versionCode | 发布类型 |
| --- | --- | ---: | --- |
| 分支或普通手动构建 | `1.0.6-healthlab.40` | `1000046` | 不发布 |
| `v1.0.6-healthlab.40` | `1.0.6-healthlab.40` | `1000046` | 历史编号，仅用于校验 |
| `v1.0.6-healthlab.41` | `1.0.6-healthlab.41` | `1000047` | Android 预发布 |
| `v1.0.6` | `1.0.6` | `100006999` | 正式版 |
| `v1.0.7-healthlab.1` | `1.0.7-healthlab.1` | `100007001` | Android 预发布 |

旧 `1.0.6-healthlab.N` 系列保留 `1000006 + N`。其他版本用 `major × 100000000 + minor × 1000000 + patch × 1000 + N`，正式版占用 `N = 999`。发布任务比较仓库中所有带 APK 的公开 Release 标签，拒绝等于或低于任一历史版本号的构件；无法解析历史标签时也会阻断。正式版之后应使用更高的基础版本发预发布。HealthLab 标签不附带 IPA。正式版仅在该提交完成 iOS 设备验收、且仓库变量 `IOS_HEALTHKIT_VERIFIED_COMMIT` 与该提交匹配时附带 IPA。

## 签名与迁移

本地 Release 构建需要 `ANDROID_RELEASE_STORE_FILE`、`ANDROID_RELEASE_STORE_PASSWORD`、`ANDROID_RELEASE_KEY_ALIAS` 和 `ANDROID_RELEASE_KEY_PASSWORD`，缺失时该构建失败。GitHub Actions 使用 `ANDROID_RELEASE_STORE_BASE64` 代替文件路径，其他三个值同名配置为 Secrets；另设置 `ANDROID_RELEASE_CERT_SHA256` 仓库变量（64 位十六进制指纹）。勿将密钥或密码提交到仓库。

已发布的 `.40` APK 与 tag 不变。不要用旧的公开实验密钥签新的 Release。新密钥签名的同包名 APK 默认不能直接覆盖旧安装；应在确定数据保留和安装迁移方案后再发布，不要为升级而先卸载或清除数据。

## 本地检查

```sh
bash scripts/test-resolve-version.sh
./gradlew :composeApp:testAndroidHostTest :miclient:androidTest :androidApp:assembleDebug detekt
```
