# Mi Health Sync Lab

[Better Mi Fitness Sync](https://github.com/ilyasaftr/better-mi-fitness-sync) 的非官方实验性 fork，当前源码版本为 `1.0.6-healthlab.40`（版本号 `1000046`）。

## 这个分支有什么不同？

- 以 Android 为主要验证平台，扩展小米运动健康与 Health Connect 的双向同步，以及连接 Zepp 后的同步流程。
- 调整睡眠和运动记录的处理、后台同步与小米登录会话续期。
- 各类数据、地区和平台的支持程度不同；双向同步不等于所有指标都能完整读写。iOS 源码保留，但此实验版本未验证 iOS 构建。

## 构建 Android

安装 JDK 17 和项目所需的 Android SDK，然后在仓库根目录执行：

```sh
./gradlew :androidApp:assembleDebug
```

Windows 上可用 `gradlew.bat :androidApp:assembleDebug`。APK 输出到 `androidApp/build/outputs/apk/debug/`。安装后按需登录服务并授权 Health Connect；在验证同步前，先确认方向和数据范围。

这是实验版源码，不是上游官方发行包。此仓库未随源码上传 APK 或真机验证资料。

## 上游与许可

基于 Ilyasa Fathur Rahman 的 [Better Mi Fitness Sync](https://github.com/ilyasaftr/better-mi-fitness-sync)，保留原始 [MIT 许可](LICENSE)。第三方声明见 [`androidApp/src/main/assets/`](androidApp/src/main/assets/)。
