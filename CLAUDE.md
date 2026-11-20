# Mi Health Sync Lab — Agent SOP

> 接手前必读此文件。所有操作顺序和约束以本文件为准。

---

## 项目概况

**App 名称：** Mi Health Sync Lab
**包名：** `com.bettermifitness.sync.healthlab`
**当前分支：** `healthlab`（在 `fork` remote，即 `Robin-Wujw/mi-health-sync-lab`）
**上游：** `origin`，即 `ilyasaftr/better-mi-fitness-sync`（不往上游推，见约束）

小米健康云 ↔ Health Connect（Android）双向多指标同步 App。
主要指标：心率、静息心率、睡眠、HRV、血氧、步数、距离、热量、体重、运动、血压、体温、VO₂Max；
Zepp Cloud 评分反向导入。

---

## 目录结构

```
better-mi-fitness-sync/
├── androidApp/          Android 应用入口、MainActivity、build.gradle.kts
├── composeApp/          共享 KMP 代码（commonMain / androidMain / iosMain）
│   └── src/
│       ├── commonMain/  业务逻辑、UI、同步协调器
│       ├── androidMain/ Android 平台实现
│       └── androidHostTest/  本地测试（Robolectric）
├── miclient/            小米 API 客户端库
├── apk/                 ★ 最新 APK 单独放这里（*.apk 被 gitignore，目录本身 tracked）
├── deliverables/        历史交付物归档（APK + validation JSON，全部 untracked）
├── docs/healthlab/      版本说明、验收记录、协议证据
└── CLAUDE.md            本文件
```

---

## 构建环境（Windows）

**JDK：** `D:\MyCodes\test\.build-tools\jdk17\jdk-17.0.20.1+1`
**Gradle：** `D:\MyCodes\test\.build-tools\gradle-9.7.1\bin\gradle.bat`（不用 `./gradlew`，wrapper 下载在此机器不可用）
**代理：** 其他仓库走 `127.0.0.1:7897`；`dl.google.com` 直连

**所有构建命令通过 batch 脚本执行**（PowerShell 5.1 中 `|` 在参数里会被解析为管道）：

```bat
D:\MyCodes\test\.build-tools\run-build.bat test          # 仅跑测试
D:\MyCodes\test\.build-tools\run-build.bat build         # 仅构建 APK
D:\MyCodes\test\.build-tools\run-build.bat test-and-build # 测试 + 构建
```

通过 PowerShell 调用：
```powershell
cmd /c "D:\MyCodes\test\.build-tools\run-build.bat test-and-build"
```

---

## 标准操作流程（SOP）

每次做改动都严格按以下顺序执行，不得跳步：

### Step 1：理解需求，定位代码

- 读清楚要求，确认改哪个指标/流程
- 核心文件路径（`composeApp/src/commonMain/kotlin/com/bettermifitness/sync/`）：
  - `data/repository/HealthRepository.kt` — 同步编排、各指标 syncXxx 方法
  - `sync/SyncCoordinator.kt` — 单飞锁、会话检查、同步调度
  - `ui/sync/SyncViewModel.kt` — 同步页 UI 状态
  - `ui/home/HomeViewModel.kt` / `HomeScreen.kt` — 首页
  - `zepp/ZeppSyncService.kt` — Zepp Cloud 评分导入
  - `data/preferences/SyncPreferences.kt` — 指标键常量

### Step 2：修改代码

- 只改必要文件，不做无关重构
- 每个新常量/key 对应的 `when`/`stateForKey` 分支必须同步更新
  （历史 bug：`zeppCloudImport` 漏加 `withMetric()` case 导致状态静默丢弃）
- UI 状态改动需同时检查 `SyncUiState`、`LocalSyncState`、`combine` 映射三处

### Step 3：运行测试

```powershell
cmd /c "D:\MyCodes\test\.build-tools\run-build.bat test"
```

- 测试通过标准：**0 Failures，0 Errors**
- 测试数量不能减少（当前基准 ~1143）
- 若测试失败：**必须先修测试，不得跳过直接构建**

### Step 4：清理缓存文件

测试通过后，构建前先清理：

```powershell
Remove-Item -Recurse -Force "D:\MyCodes\test\better-mi-fitness-sync\androidApp\build" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "D:\MyCodes\test\better-mi-fitness-sync\composeApp\build" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "D:\MyCodes\test\better-mi-fitness-sync\miclient\build" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "D:\MyCodes\test\better-mi-fitness-sync\build" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "D:\MyCodes\test\better-mi-fitness-sync\.gradle" -ErrorAction SilentlyContinue
```

### Step 5：更新版本号

在 `androidApp/build.gradle.kts` 中更新：
```kotlin
val appVersionName: String = appVersionProp("versionName", "VERSION_NAME", "1.0.6-healthlab.XX")
val appVersionCode: Int = appVersionProp("versionCode", "VERSION_CODE", "100004X").toInt()
```
versionCode 规律：`.40` = 1000046，`.41` = 1000047，依此类推。

### Step 6：构建 APK

```powershell
cmd /c "D:\MyCodes\test\.build-tools\run-build.bat build"
```

### Step 7：验证 APK

```powershell
$apk = "D:\MyCodes\test\better-mi-fitness-sync\androidApp\build\outputs\apk\release\androidApp-release.apk"
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $apk).Length
"Size: $size  SHA256: $hash"
& "D:\MyCodes\test\.build-tools\android-sdk\build-tools\36.0.0\aapt2.exe" dump badging $apk 2>&1 |
  Select-String "versionCode|versionName" | Select-Object -First 1
```

必须确认：
- `versionCode` 和 `versionName` 与 `build.gradle.kts` 一致
- `debuggable` 不为 true
- 包名 `com.bettermifitness.sync.healthlab`

### Step 8：复制 APK 到 `apk/` 目录

```powershell
Copy-Item $apk "D:\MyCodes\test\better-mi-fitness-sync\apk\mi-health-sync-lab-1.0.6-healthlab.XX.apk"
```

同时存一份到 `deliverables/healthlabXX/`（含 validation JSON）。

### Step 9：Git commit

```powershell
git add <修改的文件> androidApp/build.gradle.kts apk/.gitkeep
git commit -m @'
Publish Mi Health Sync Lab 1.0.6-healthlab.XX

<改动描述>

Tests: XXXX passed, 0 failures
APK: mi-health-sync-lab-1.0.6-healthlab.XX.apk
SHA256: <hash>

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
'@
```

### Step 10：同步远端并推送

推送前先检查远端是否有新 commit：

```powershell
git fetch fork healthlab
git log --oneline HEAD..fork/healthlab  # 若有输出则需要 merge
```

**若远端有新 commit：**
1. `git merge fork/healthlab --no-commit --no-ff` 查看冲突文件
2. 逐文件手动解决（优先保留远端新功能，把我们的修复 port 进去）
3. `git add <resolved files>`
4. 重新跑测试（Step 3），通过后 `git commit`

**无冲突则直接推送：**
```powershell
git push fork healthlab
```

---

## 不可触碰的约束

| 约束 | 原因 |
|---|---|
| 不往 `origin`（上游）推任何 fork 专有代码 | fork 包名、Zepp 私有接入、反向导入不在上游范围内 |
| 不清空/重置健康账本（`HealthImportLedger`） | 会导致 pending 数据重复上传 |
| 不删除运动记录再普通同步 | 会触发不确定的恢复逻辑 |
| 不输出 token / ssecurity / serviceToken | 凭据保留在本机加密存储 |
| `test_mode=true` 不是 dry-run | 是有界单条真实上传，不能随便用 |
| `SyncRunResult.from()` 中 `succeeded/failed/noData` 不能随意加减 | 影响整体成功/失败判断逻辑 |

---

## 关键 bug 历史（避免重犯）

| 文件 | 问题 | 修复 |
|---|---|---|
| `HealthRepository.kt` `withMetric()` | 新增指标 key 忘加 `when` case → 状态静默丢弃 | 每次新增 key 必须同时加 `withMetric`、`stateForKey`、`SyncProgress` 字段 |
| `SyncViewModel.kt` `runSync` | `NoData`/`CompletedWithSkips` 把 `outcomeMessage = null` → 用户看不到任何反馈 | 现在通过 `outcomeFeedback()` 显示消息 |
| `SyncViewModel.kt` finally | 每次同步后都重检 Health 可用性 → 按钮抖动 | 仅在 `Failed`/`HealthUnavailable` 结果后才重检 |
| `UserSyncPlatform.android.kt` | prepare→service 间隙 `isRunning=false` → UI 短暂闪烁 | `startSync()` 加 `optimisticSyncing` 乐观 flag |
| `miclient` passToken Cookie | 默认 URI 编码改变 token 值 → 续期 70016 | `.33` 改用 RAW 编码（`seedPassTokenCookie`） |

---

## 未解决 / 待实现

| 项目 | 类型 | 说明 |
|---|---|---|
| HRV 原生个人基准 | 功能开发 | 需补 `hrv_analysis_timestamp`、`hrv_base_min/max` 字段到 `MiHrvImportMapper` |
| 睡眠效率来源 | 无数据源 | Zepp 无可靠效率值，不猜算 |
| 血氧 pending 恢复 | 操作项 | 用"恢复一条待确认血氧"专用入口，普通同步无法补传 |
| 跨多日登录保持 | 真机验收 | .33 已修 passToken 编码，需真机长期观察 |
| 后台/Doze/定时 | 真机验收 | 代码已有保护，需真机验收 |

---

## 文档位置

- 各版本说明：`docs/healthlab/HEALTHLAB-XX.zh-CN.md`
- 验收记录：`docs/healthlab/validation-healthlabXX.json`
- 协议证据：`docs/healthlab/import-contract-evidence.json` 等
- 完整交接：`D:\MyCodes\test\MI_HEALTH_SYNC_AGENT_HANDOFF.zh-CN.md`
- 用户反馈：`D:\MyCodes\test\HEALTHLAB8_USER_FEEDBACK.zh-CN.md`
