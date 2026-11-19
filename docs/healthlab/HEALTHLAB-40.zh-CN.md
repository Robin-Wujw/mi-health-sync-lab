# HealthLab .40：当前改动 review 与 APK 交付

日期：2026-11-17。版本 **1.0.6-healthlab.40（1000046）**。

## Review 结论

**本轮确认的两处功能缺口已修正，完整 Android 本地验证通过，可以交付安装。** 该结论不是手机实际同步、长期保活或所有历史待办的验收。

工作区基于 `c4889ce`（.20），包含 .21～.39 的累计未提交改动及 11 月 17 日新增的 Zepp 自动接入、状态展示、后台调度调整。本轮检查最新调用链并复核账本预留/独立读回、超时取消、前台服务和会话续期等高风险路径；没有重置工作区、提交或新建分支。开始修正前已保存原始改动快照。

## 已修正的问题

1. **[P2] 仅选择睡眠导入时，正常同步漏请求睡眠体征依赖权限。**
   - 旧协调器只请求睡眠读取；实际睡眠准备阶段还会读取同来源心率和血氧，缺少授权会退化为不含这些明细的导入。
   - [SyncCoordinator:245–256](<../../composeApp/src/commonMain/kotlin/com/bettermifitness/sync/sync/SyncCoordinator.kt#L245-L256>) 统一复用现有 `SyncSelection.inboundMetrics`，与设置/同步页的权限依赖一致。不会启用独立心率或血氧同步任务，也不请求无关写入。
   - 普通执行与 Android 服务交接前的手动准备均已回归：修正前实际只得到 `[SLEEP]`，修正后得到 `[SLEEP, HEART_RATE, OXYGEN_SATURATION]`。HRV 单独导入依然只需要 HRV 与真实睡眠上下文。
2. **[P2] 旧安装的会话保活任务可能继续保留 12 小时间隔。**
   - 新请求虽然设为 3 小时，但 `ExistingPeriodicWorkPolicy.KEEP` 不更新已有同名任务。
   - [AutoSyncPlatform.android:69–87](<../../composeApp/src/androidMain/kotlin/com/bettermifitness/sync/AutoSyncPlatform.android.kt#L69-L87>) 对独立会话续期任务恢复 `UPDATE`，不取消正在执行的旧任务。普通数据定时任务仍为 1 小时；恢复调度不新增立即同步，仅用户开启自动同步时创建一次立即任务。
   - 真实 WorkManager/Robolectric 数据库回归在修正前观察到 43,200,000ms，修正后为 10,800,000ms，保留同一任务 ID。

关联一致性修正：[SettingsViewModel:204–210](<../../composeApp/src/commonMain/kotlin/com/bettermifitness/sync/ui/settings/SettingsViewModel.kt#L204-L210>) 的手动导入授权也请求平台支持的后台读取，不再依赖自动同步开关。底层能力/授权检查保留，没有强行授予权限。

## 对现有最新改动的验证

- 已连接 Zepp 自动参与正常同步，不再被旧评分开关阻挡；断开后忽略残留的旧开关。
- 权限准备与服务实际执行之间若断开 Zepp，会重新检查连接状态。
- 仅 Zepp 云任务不要求 Health Connect；混合 HC 任务仍检查可用性。
- 冷启动恢复调度不会重复创建立即同步；显式开启自动同步才创建，关闭时取消两个数据任务、保留独立会话保活。
- 继承 .39 的发送前持久预留、未知写入不盲目重发、独立读回、阶段预算与状态收尾，没有清空旧 pending、备份或归属。

本轮新增 8 项测试，并加强 1 项既有睡眠权限断言。新增覆盖见 [AutoSyncScheduleTest](<../../composeApp/src/androidHostTest/kotlin/com/bettermifitness/sync/sync/AutoSyncScheduleTest.kt>)、[ZeppAutomaticSelectionTest](<../../composeApp/src/commonTest/kotlin/com/bettermifitness/sync/zepp/ZeppAutomaticSelectionTest.kt>) 与 [SyncCoordinatorTest](<../../composeApp/src/commonTest/kotlin/com/bettermifitness/sync/sync/SyncCoordinatorTest.kt>)。

## 完整本地验证

使用工作区已有 Gradle 9.7.1、JDK 17 和 Android SDK，以 `--offline --rerun-tasks` 重新执行：

```text
:composeApp:testAndroidHostTest
:miclient:androidTest
detekt
:androidApp:lintRelease
:androidApp:assembleRelease
:androidApp:assembleReleaseAndroidTest
```

| 检查 | 结果 |
| --- | --- |
| Compose/common + Android host 测试 | 1041 通过，0 失败/错误/跳过 |
| miclient 测试 | 74 通过，0 失败/错误/跳过 |
| 本轮重新执行的测试合计 | **1115**（未把历史 Python 6 项计入） |
| Detekt | 通过 |
| Android Lint | 0 错误、0 Fatal、24 个既有警告 |
| Release / 独立测试 APK | 构建成功；测试 APK 未安装/执行 |
| 包名、版本、min/target SDK | 正确；28 / 36 |
| 非 debuggable | 通过 |
| APK 签名 | 验证通过，与 .39 同签名 |
| ZIP 完整性 / 16KB 对齐 | 应用和测试 APK 均通过 |
| 最终合并清单 | dataSync 前台服务、非导出、保留任务后台运行及所需权限检查通过 |
| 最终源码 diff | 已复核；`git diff --check` 通过 |

原始构建证据：[完整验证日志](<../../../.build-tools/review-20261117-full-validation.log>)。
包及测试明细：[validation-healthlab40.json](<validation-healthlab40.json>)。
修正前可复现失败：[回归复现日志](<../../../.build-tools/review-20261117-repro3.log>)。
本轮精确差异：[review-fixes.diff](<../../../.build-tools/review-20261117-fixes.diff>)。

## 安装包

[下载 .40 APK](<../../../deliverables/healthlab40/mi-health-sync-lab-1.0.6-healthlab.40.apk>)。

- 大小：44,935,368 字节，约 42.9 MiB。
- SHA-256：`fd1e1cb31f7299c68ce9cf57bc84bfed8afc954036ba1cf619b9a65a37a3047d`。
- 沿用 HealthLab 包名和签名，可覆盖旧 HealthLab 版本；**无需卸载，不要清空应用数据/账本**。
- 安装后执行正常同步，若系统请求睡眠体征/后台读取授权，请按需要授权。
- 仍沿用项目现有公开 debug keystore 签署非调试 release；这是现有实验分发签名，不是新的私有正式发布密钥。

## 验收边界

- 本轮 **ADB 0、真实账号访问 0、健康云写入 0**，未读取/修改手机旧账本，没有操作手机或安装 APK。
- 本机回归不能证明手机锁屏、Doze、后台周期调度、进程回收重投递或跨多天登录已通过。
- HRV 原生个人基准和睡眠效率仍未支持；历史运动评分回填、旧睡眠归属、手机 pending 等仍需实际主流程验收。
- iOS 未构建；本轮主交付与完整构建验证针对 Android。

## 构建环境说明

最初工作区写入被 Windows 文件权限阻挡；获准非沙箱运行诊断后，仅为构建工具目录补充当前登录用户的完全控制，重新检查通过。文件内容和所有者未因权限修复改变，没有移除外来应用包项。之后用户将会话切为完全访问。上级目录仅检查，未扩大修复范围；子树扫描到 2000 项截断，未声称遍历全部目录。

[权限诊断报告](<../../../../test-acl-recovery/acl-report-bdbb3b3481864ec2be44ad77604d1f46.jsonl>) 和 [恢复脚本](<../../../../test-acl-recovery/acl-backup-f43f6d6b51864f14bab11e66d233cacf.json.ps1>) 已保留。需要撤销该权限变更时执行：

```powershell
pwsh -NoProfile -File 'D:\MyCodes\test-acl-recovery\acl-backup-f43f6d6b51864f14bab11e66d233cacf.json.ps1' -Path 'D:\MyCodes\test\.build-tools' -AllowRoot 'D:\MyCodes\test' -Restore 'D:\MyCodes\test-acl-recovery\acl-backup-f43f6d6b51864f14bab11e66d233cacf.json'
```
