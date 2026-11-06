# 用电脑连接真机做同步检查（healthlab.7）

这套入口运行在手机上的 **Mi Health Sync Lab 原应用进程**，复用已经登录的小米账号、已授予的 Health Connect 权限及现有官方 Zepp 来源限制。电脑只负责启动测试并保存去除敏感内容的结果。它不依赖 root 或 Shizuku，也不需要把账号令牌发送到电脑或远程服务器。

需要配套主应用 **`1.0.6-healthlab.7 (1000013)`** 和同签名的 `.7` 测试 APK。`.5` 已有只读检查和普通单目标同步，`.6` 增加单条恢复，`.7` 增加 `reconcile-pending`：一次读取并核对当前范围内的待确认血氧，只更新已经实际匹配的本地记录，**不上传健康数据**。旧测试包不能调用新模式。测试 APK 没有独立同步产品界面；主应用仍为非 debuggable 的 release 构建，也没有新增可供其他应用调用的上传 Activity 或 receiver。

本次构建和单元测试不能代替你的手机与小米账号的在线验证。只有下面的命令在连接了手机的电脑上运行并返回报告，才算完成该设备上的一次检查。

## 四种模式的区别

| 模式 | 健康数据上传 | 本地待确认状态 | 写入开关 |
|---|---|---|---|
| `inspect`（默认） | 无 | 不改变，只返回观察结果 | 不传 `-AllowCloudWrite` |
| `reconcile-pending`（仅血氧） | 无 | 已实际匹配的目标完成本地核对，其余保留 | 不传 `-AllowCloudWrite` |
| `sync-one` | 最多一个普通原始目标 | 遵循普通同步日志；不强行重传 pending | 必须传 `-AllowCloudWrite` |
| `recover-one-pending`（仅血氧） | 最多额外尝试一个既有 pending 目标 | 保留每目标一次恢复额度和失败状态 | 必须传 `-AllowCloudWrite` |

`reconcile-pending` 的“只读”指小米云端健康数据；它会更新本地核对状态。它不等同于完全不改日志的 `inspect`。两种无上传模式都拒绝多余的写入开关，避免误解这次操作的作用。

## 准备一次

1. 在主应用里正常登录，确认 Health Connect 中有官方 Zepp 来源的数据；当前入站来源仍固定为 `com.huami.watch.hmwatchmanager`。
2. 在系统 Health Connect 中授予主应用对应指标的读取权限。HRV 还需要实际睡眠上下文；脚本不会自动授予或扩大权限。
3. 连接 USB，打开 USB 调试，解锁手机，接受电脑的调试授权。
4. 准备配套主 APK、测试 APK 和 `scripts/Invoke-MiHealthLiveTest.ps1`。更新使用 `adb install -r`，不卸载主应用或清除数据。

源码构建命令（已有本地 Android 构建环境时）：

```powershell
.\gradlew.bat :androidApp:assembleRelease :androidApp:assembleReleaseAndroidTest
```

## 先检查血氧：默认不写健康数据

以下命令在项目根目录的 PowerShell 中执行，ADB 路径对应你之前使用的目录。APK 路径是 Gradle 默认输出位置：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -AppApk ".\androidApp\build\outputs\apk\release\androidApp-release.apk" -TestApk ".\androidApp\build\outputs\apk\androidTest\release\androidApp-release-androidTest.apk" -Metric spo2 -RangeDays 3
```

`ExecutionPolicy Bypass` 只作用于这次 PowerShell 进程，不改变电脑的全局执行策略。也可按你现有的脚本执行方式直接运行 `.ps1`。

配套 APK 已经安装后，后续不用再传 APK 路径：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -Metric spo2 -RangeDays 3
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -Metric resting_heart_rate -RangeDays 3
```

默认模式为 `inspect`，默认指标为 `spo2`，默认范围为最近 3 天。`-RangeDays` 支持 `1` 到 `27`，应覆盖发生问题的记录；它不以手机午夜自动切整天。预留一天边界是为了避免多次 HC 读取时“恰好 28 天”的起点越界，不会静默缩短传入范围。可选指标为 `spo2`、`resting_heart_rate`、`heart_rate`、`hrv`、`sleep`。连接多台设备时必须用 `-Serial` 明确指定。

检查会读取 Health Connect、生成同一套映射计划，并只对计划内已有 pending 做云端探测。**没有 pending 时不执行该探测的云端数据读取；它不是所有新目标的完整云端预检查。** 它不上传健康数据、不清除 pending、不把 missing 直接当作可以重新上传的证据。必要的账户会话刷新仍可能发生；“不写健康数据”不等于没有任何网络请求，也不等于 HTTP 一律使用 GET。

测试期间保持手机解锁、应用在前台。测试进程暂停正常手动与自动同步，前台页也不启动普通首页资料刷新，避免它们混入检查结果；已有用户设置不变。命令结束后会停止该进程，**重新打开主应用即可恢复正常同步**。

## 报告能回答什么

脚本会在自身目录保存 `mi-health-live-日期时间.txt`，也可用 `-OutputPath` 指定文件。只保存仪器化测试主动返回的诊断块，不抓取 logcat、截图、完整数据库或接口原文。

- `runner_outcome=COMPLETED` 只表示命令完成；实际导入结果仍以内部诊断的 `outcome`、`stage`、`failure_reason` 和数量为准。
- `inspect` 的计划诊断停留在 `PREPARE`，没有执行上传 runner；此时 `outcome=INCOMPLETE` 或 `verified_raw_rows=0` 不代表命令失败或云端没有记录。看 `runner_outcome`、来源问题和 `pending_probe.*` 评估检查结果。
- `runner_failure` 表示权限、登录、版本、超时等检查入口失败；异常原文不会导出。
- pending 的宽窗口和最多 3 个独立窄窗口查询，可帮助区分“未返回匹配记录”“内容不同”“查询不确定”等情况。窄窗口查到而宽窗口没有查到，提示读取窗口或云端可见性值得继续检查，不能据此直接认定服务端分页规则。
- `inspect` 不会使应用里的 pending 状态消失。`.7` 的 `reconcile-pending` 是另一个显式操作，可以把已实际匹配的目标完成本地核对。

报告不包含账号、设备标识、令牌、来源记录 ID、健康数值或称量/心率等记录的时间戳。文件名中的时间只是本次检查的运行时间。

## 一次核对全部待确认血氧（.7 新增）

**手机可直接完成这一步。** 覆盖安装 `.7`，设置中开启“血氧 → 小米”，选择覆盖问题记录的 1、7 或 14 天，再到同步页点“核对全部待确认血氧”。结果在“本次待确认血氧处理结果”中单独显示并可复制，普通同步的历史统计不变。“恢复一条待确认血氧”仍在同一区域，是另一项最多上传一条的操作；核对按钮不会自动触发它。

电脑调用同一个核对流程：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -Mode reconcile-pending -Metric spo2 -RangeDays 3
```

不加 `-AllowCloudWrite`。该模式仅支持 `spo2`，范围仍是 1～27 天。执行时读取所选来源计划和对应 pending，对照云端返回的原始记录。只有来源、内容和目标身份一致，且没有删除标记、其他来源占用或冲突的记录，才能完成本地核对；随后以原子比较方式保存结果。已有恢复次数记录保留，不会重新发放补传机会。

宽窗口读取有组数上限，原生历史读取最多 32 页。已明确匹配的目标可以核对；历史未读完且未返回的目标归为 `unqueried`，不能当作“服务器没有保存”。另一窗口查到匹配，也不会抹掉本轮已观察到的冲突或删除证据。没有自动补传、改时间、改来源或全局清锁。

主要看这些字段：

```text
health_data_writes_requested=false
local_journal_changes_requested=true
pending_reconcile.cloud_read_only=true
pending_reconcile.updates_local_journal=true
pending_reconcile.candidate_rows=...
pending_reconcile.reconciled_rows=...
pending_reconcile.unresolved_rows=...
attempted_raw_rows=0
newly_verified_raw_rows=0
```

`reconciled_rows` 是本次实际完成本地核对的数量；它们属于已有云端记录，不是本次新上传。`unresolved_rows` 是本次候选里没有完成核对的数量，不是整个账号的全部 pending 数，也不代表所有这些记录都已被服务器拒绝。

| `pending_reconcile.status` | 含义 |
|---|---|
| `COMPLETE` | 本次候选全部完成本地核对；不证明范围外已成功或小米 App 已显示 |
| `PARTIAL` | 只完成其中一部分，查看各分类计数 |
| `INCOMPLETE` | 没有完成任何目标，查看来源、云端、读取或本地状态分类 |
| `NO_PENDING` | 当前来源范围内没有候选，不代表全历史完成 |
| `LOCAL_SAVE_FAILED` | 本地结果未能保存，不能视为完成；没有健康数据上传 |

核对 132 条时可能一次解决多条，也可能解决 0 条。只有在实际查询找到可靠匹配时才会推进，不能把 `.5` 的 `missing=132` 或 `.6` 的单条模式推断为这次一定能够全部解决。

## 有需要再做单个原始目标的在线同步

普通单目标同步需要明确指定 `-Mode sync-one -AllowCloudWrite`：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -Mode sync-one -AllowCloudWrite -Metric spo2 -RangeDays 3
```

它通过既有映射器、导入日志和 runner，最多选择 **1 个原始目标、0 个每日汇总目标**。HRV 的一个原始目标可能汇总了多个真实来源点。已有、pending 或冲突的目标仍按原规则处理，不强行覆盖或重传。一次命令可以有多次读取/校验请求；“一个目标”不代表“只有一个 HTTP 请求”。

没有全量写入模式。失败时脚本不自动重复运行。需要先检查报告，再决定下一步。

## 恢复一条既有待确认血氧（.6 新增）

用户最新 `.5` 报告中，195 个血氧目标包含 63 条已有匹配和 132 条 pending；132 条宽窗口均未找到匹配，抽取的 3 条窄窗口也未找到，查询没有报错。本轮新提交为 0。这说明旧待确认记录仍然阻止重新提交，不能把 pending 从 147 减少到 132 当作本次已经恢复 15 条。

`.6` 提供专门的一条恢复入口。**手机上的“恢复一条待确认血氧”操作与电脑模式共用同一恢复流程**，不需要电脑才能使用；手机入口位置以主应用说明为准。不要清除全部 pending，也不要为了试验而清空 App 数据。

电脑调用：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath "D:\CodingSoftware\adb-tools\adb.exe" -Mode recover-one-pending -AllowCloudWrite -Metric spo2 -RangeDays 3
```

这个模式只接受 `-Metric spo2`，仍需 `-AllowCloudWrite`。它与 `sync-one` 的区别是从选定范围内的**既有 pending** 中选择候选，最多处理一个 RAW 目标，不写日报，也不全局解除锁。正常单目标同步仍不会强行重传 pending。

恢复前会检查来源与本地记录是否一致、云端是否已存在目标或冲突，以及该目标是否已经使用恢复机会。查询失败、来源变化、删除状态、来源不明确、恢复机会已使用等情形可能导致 **0 条提交**。缺少匹配查询结果本身不能证明服务器从未保存，因此恢复结果仍需读回核对；不能承诺服务器事务或幂等行为。

失败、超时或结果不确定时不自动重复。不要通过反复运行命令、清锁或改时间来绕过恢复结果，应先查看脱敏诊断。命令执行完成不等于恢复成功；以内部 `attempted_raw_rows`、`newly_verified_raw_rows`、失败分类和恢复诊断为准。恢复一条成功也不表示其余 131 条已恢复或小米 App 已显示。

旧版 pending 没有保存完整原请求及提交结果。它的内容指纹不含外层时区，不能证明旧请求的每个字段都与当前记录相同；新版恢复不会把这项历史信息缺失伪装成已验证。具体检查和限制见 `docs/healthlab/spo2-recovery-evidence.json` 与主应用说明。

`.7` 的构建、测试数量和签名核对结果以版本匹配的 `validation.json` 为准；不能沿用 `.6` 的 488 项测试作为本轮通过证据。真机、Windows PowerShell 和个人账号验证是否执行，也以该记录为准。

## 直接 ADB 入口（供本地开发代理调用）

配套 APK 已经正确安装时，可直接调用同一个 runner。`inspect` 为默认模式；下面显式写出参数以便审查：

```powershell
& "D:\CodingSoftware\adb-tools\adb.exe" shell am instrument -w -e mode inspect -e metric spo2 -e rangeDays 3 -e allowWrites false com.bettermifitness.sync.healthlab.test/com.bettermifitness.sync.lab.HealthLabInstrumentation
```

结果位于 `MI_HEALTH_LIVE_REPORT_BEGIN` 与 `MI_HEALTH_LIVE_REPORT_END` 之间。推荐使用 PowerShell 驱动，因为它还会检查设备、主应用版本、测试包是否存在，并在结束后停止被隔离的测试进程。不要对主应用使用 `pm clear`、卸载重装或 `run-as` 来获取账号数据。

只读取云端并核对已匹配的本地 pending：

```powershell
& "D:\CodingSoftware\adb-tools\adb.exe" shell am instrument -w -e mode reconcile-pending -e metric spo2 -e rangeDays 3 -e allowWrites false com.bettermifitness.sync.healthlab.test/com.bettermifitness.sync.lab.HealthLabInstrumentation
```

明确授权一条既有血氧 pending 恢复时，直接 ADB 参数为：

```powershell
& "D:\CodingSoftware\adb-tools\adb.exe" shell am instrument -w -e mode recover-one-pending -e metric spo2 -e rangeDays 3 -e allowWrites true com.bettermifitness.sync.healthlab.test/com.bettermifitness.sync.lab.HealthLabInstrumentation
```

直接 ADB 不包含脚本的结束清理；命令完成后需要停止测试进程并重新打开主应用，才能恢复普通同步。优先使用上述 PowerShell 驱动。
