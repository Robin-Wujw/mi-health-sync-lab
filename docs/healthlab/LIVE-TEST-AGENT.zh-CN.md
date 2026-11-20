# Mi Health Sync Lab：真实线上同步与实机测试交接（healthlab.40）

> 当前工作区与已连接真机使用 `1.0.6-healthlab.40 (1000046)`。本文中 `.7 / 1000013` 的描述是历史交接记录，不得作为当前候选包的版本、签名或验收证据；当前证据以 `docs/healthlab/SPEC-ACCEPTANCE-MATRIX.zh-CN.md`、`SYNC-REVISION-STATUS.zh-CN.md` 和同目录 `live-*` 报告为准。

## 历史 .7 交接记录

先使用手机“核对全部待确认血氧”或 PC `-Mode reconcile-pending -Metric spo2`（不带 AllowCloudWrite）。它只读云端，在一次本地事务中批量完成明确匹配的 pending 核对，未匹配不上传、不清锁。`inspect` 仍不改本地账本；`recover-one-pending` 才是有条件的一条真实补传。不要把 `reconcile-pending` 的 `already_present_raw_rows` 当作本轮上传数。新报告看 `pending_reconcile.status/reconciled_rows/unresolved_rows` 及分类；最终验证只认 `.7 / 1000013` 的 validation.json。

当前没有新的用户 `.6/.7` 真机诊断，不能声称 132 条已经恢复。以下旧流程与协议边界继续保留；当前操作说明以根目录 HEALTHLAB.zh-CN.md 与 docs/live-test.zh-CN.md 为准。


更新：2026-11-03。本轮首先处理用户回传的 **132 条血氧 pending**，让本地代理完成观察、一次有限恢复、读回和复测；已有心率、睡眠、静息心率和实验 HRV 能力继续保留。

## 1. 历史 .7 实现与验证边界

- 历史版本：`1.0.6-healthlab.7`，versionCode `1000013`；不代表当前候选包。
- 主包：`com.bettermifitness.sync.healthlab`；Activity：`com.bettermifitness.sync.MainActivity`。
- 测试包：`com.bettermifitness.sync.healthlab.test`；runner：`com.bettermifitness.sync.lab.HealthLabInstrumentation`。
- 来源为华米官方 Zepp 包 `com.huami.watch.hmwatchmanager`。路径为 Zepp → 手机 Health Connect → 小米云端，不能混称 Zepp Life。
- `.6` 新增手机同步页“恢复一条待确认血氧”，以及电脑 `recover-one-pending` 模式；两者复用 `MiPendingSpO2Recovery`，没有另一套更宽松的上传器。
- `.6` probe 增加一个 key、最多 5 页的原生无起点控制读取；恢复器最多读 32 页，完整性不明时不会写入。
- `.6` 普通 RAW 同步保留已核对目标的历史：曾 verified 的目标身份本轮查询为 MISSING 时，以 `blocked_verified_history_rows` 报告 UNVERIFIED，不重发、不清历史，也不计为本轮已验证或 pending。
- `.5` 已实现同签名测试 APK、PowerShell 驱动、RHR 日最小值策略、宽/窄窗口 probe 和默认数据传输重放保护。本轮继续保留，并不将历史成果写成 `.6` 新实测。
- 当前聊天环境是云端 Linux，没有用户 Windows/USB 手机或账号远程会话。本轮未在用户真机或 Windows PowerShell 运行新入口。离线测试、APK 构建、命令 COMPLETED 与服务 code=0 均不能替代端到端成功。
- 实际构建、测试、APK 哈希和签名查看 `docs/healthlab/validation.json`，先核对版本为 `.7`，不要沿用旧值。
- 手机端使用不要求电脑、Root、Shizuku、`run-as` 或令牌导出。电脑入口仅用于已有授权下的本地自动操作；凭据留在手机 App 内。

本文件可供连接设备的代理执行。用户已经授权同步和测试，不必重新要求许可；账号登录、验证码、USB 授权与系统 HC 权限等无法代办的动作仍需用户本人完成。

## 2. 最新真实观察：没有新的健康数据上传

用户的 `.5` SpO2 诊断：

| 项目 | 数量/状态 | 含义 |
|---|---|---|
| 当前目标 | 195 | 本次选择范围的目标集合 |
| 上传前已有匹配 | 63 | `already_present_raw_rows=63` |
| 既有 pending | 132 | `blocked_pending_rows=132`，仍被保护，不自动重发 |
| 本次尝试/新核对 | 0 / 0 | `attempted_raw_rows=0`、`newly_verified_raw_rows=0` |
| 宽窗口 | 2 组成功；132 MISSING | 无失败、无分组限额；没有找到所需非删除同来源记录 |
| 窄窗口 | 3 个抽样均 MISSING | 最早、中间、最新三个样本也未找到；宽窄差异 0 |

`.4` 血氧曾是 `219 = 72 + 147`，现在是 `195 = 63 + 132`。来源和已有计数也改变，缺少目标身份时**不能将 147→132 解释为恢复了 15 条**。即使界面都写最近几天，相对窗口也可能滚动；不要自行指定实际差异原因。

两次 `INCOMPLETE_LOCAL_DAY` 警告说明完整日日报条件不足，没有证明它造成 pending。宽/窄查询健康只证明请求成功并返回可解析响应，不能证明服务器已返回所有保存形式。读取原始数据本身使用 POST，所以“0 次健康数据上传”也不能写成“0 次网络 POST”。

普通验证器 MISSING 包括：没返回同来源目标、相同 key/time 下只有其他明确 SID，以及被忽略的 deleted 行。`.6` 增加细分提示，恢复器还会对所有目标时刻的来源/删除冲突更严格阻止写入。旧 journal 没有保存每次普通上传的历史业务结果，无法从 pending 反推出当时是明确拒绝还是接受后未核对。

### 保留的历史状态

`.4` 的 RHR 3 条来源被同日冲突规则拒绝，尚未上传。`.5` 已改成可靠源日期内不同时刻的实际最小值；源时刻隐藏，不能承诺这 3 条一定能合成。`.3` 的心率、血氧、HRV 和 RHR 计数，以及 `.4` 跨独立日期处理、部分结果、只读等待重查、HRV 排除警告、同修订冲突与 v2 诊断，详见 `HEALTHLAB.zh-CN.md`。它们不是本轮新的账号测试结果。

## 3. 获取、检查并构建源码

先读项目 AGENTS.md/agent.md（如有）、Git 状态和版本匹配的验证文件，保留用户已有修改。固定上游为 https://github.com/ilyasaftr/better-mi-fitness-sync ，基础提交 `c1dd886328c82f15faf17ec24dc485cb61a70cec`。

没有现成源码时，在新目录应用本轮累计补丁，文件名按交付包核对：

```powershell
git clone --no-checkout https://github.com/ilyasaftr/better-mi-fitness-sync.git better-mi-fitness-sync-healthlab7
Set-Location better-mi-fitness-sync-healthlab7
git checkout --detach c1dd886328c82f15faf17ec24dc485cb61a70cec
git apply --check ..\better-mi-fitness-sync-healthlab7.patch
git apply ..\better-mi-fitness-sync-healthlab7.patch
```

不要对用户工作区强制 reset/clean，也不要卸载或清空主 App。需要本地构建时，使用 JDK 17、Android SDK platform 37.0 和仓库固定依赖：

```powershell
.\gradlew.bat :composeApp:testAndroidHostTest :miclient:androidTest :androidApp:assembleRelease :androidApp:assembleReleaseAndroidTest --no-build-cache
```

产物为 `androidApp/build/outputs/apk/release/androidApp-release.apk` 与 `androidApp/build/outputs/apk/androidTest/release/androidApp-release-androidTest.apk`。核对主 APK 非 debuggable、两个包同签名、测试 manifest 的目标包和 runner 正确。本版计划使用原签名覆盖安装；实际证书结果以本版 `validation.json` 为准。未执行的检查明确记录未执行。

## 4. 手机与电脑的两个操作入口

### 无需电脑：同步页恢复按钮

覆盖安装 `.6`，在设置开启“血氧 → 小米”，选择覆盖旧问题的 1、7 或 14 天；然后在同步页点击“恢复一条待确认血氧”。界面会单独显示这次恢复诊断，原普通同步报告保留。恢复支持 1～27 天，界面选 30 天时须调整，不会静默裁短。它不会在后台自动触发。

### 电脑：安装配套包

用户此前 ADB 路径为 `D:\CodingSoftware\adb-tools\adb.exe`。必须在连接手机的本地 Windows 上运行：

```powershell
& 'D:\CodingSoftware\adb-tools\adb.exe' devices -l
& 'D:\CodingSoftware\adb-tools\adb.exe' install -r .\androidApp\build\outputs\apk\release\androidApp-release.apk
& 'D:\CodingSoftware\adb-tools\adb.exe' install -t -r .\androidApp\build\outputs\apk\androidTest\release\androidApp-release-androidTest.apk
```

没有设备时检查连接；unauthorized 需要手机确认。多设备明确指定 `-Serial`。签名不匹配需修正产物，不卸载主 App 绕过。初次账号和 HC 授权由用户在手机处理，shell 身份不能替代 App 读取健康数据。

### 电脑先 inspect

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode inspect -Metric spo2 -RangeDays 3
```

默认参数同上，范围允许 1～27 天；应覆盖旧问题。范围边界预留一天避免恰好 28 天时起点因时钟推进越界，非法参数直接拒绝。其它可选指标为 `resting_heart_rate`、`heart_rate`、`hrv`、`sleep`。

inspect 只读 HC 和当前计划内 pending，不是所有新目标的云端预检查。无 pending 时不执行该 probe 的云端查询。`inspect_only=true`、`health_data_writes_attempted=0` 表示未写健康数据，认证刷新和签名 POST 读取仍可能发生。计划诊断处于 PREPARE；`outcome=INCOMPLETE` 或 `verified_raw_rows=0` 不等于命令失败，命令结果看 `runner_outcome`，映射与待确认情况看 `issue.*`、`pending_probe.*`。

### 电脑恢复一条既有 pending

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode recover-one-pending -AllowCloudWrite -Metric spo2 -RangeDays 3
```

必须显式使用 `-AllowCloudWrite`，且只支持血氧。与手机入口共用第 6 节的恢复流程。此模式不会循环重试，也没有恢复全量的参数。脚本不自动重复失败的 instrumentation 命令。

原 `sync-one -AllowCloudWrite` 仍调用普通 runner，最多 1 个 RAW、0 个日报，pending 不强制重发。它适合检查新的单目标路径，不能代替 `recover-one-pending`。也不要把设置中的“允许重试未确认的上传”用于血氧：**那个旧操作只针对体重，不清除 health-import 的心率、睡眠、HRV、血氧 pending。**

### 前台、报告与进程结束

手机保持解锁和 App 前台。测试 runner 在 Application 启动前设置 gate，暂停该进程的普通同步与首页资料刷新，避免与测试并发；保存的设置不变。脚本结束后停止测试进程，重新打开 App 恢复正常使用。

报告默认保存到脚本目录的 `mi-health-live-日期时间.txt`，可设置 `-OutputPath`。不抓原始 logcat、数据库、截图、请求体或凭据。Windows 脚本策略限制时，可通过 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File ...` 仅调整本次进程，不改变全局策略。更完整用法见 `docs/live-test.zh-CN.md`。

需要直接调用 runner 时，只读命令为：

```powershell
& 'D:\CodingSoftware\adb-tools\adb.exe' shell am instrument -w -e mode inspect -e metric spo2 -e rangeDays 3 -e allowWrites false com.bettermifitness.sync.healthlab.test/com.bettermifitness.sync.lab.HealthLabInstrumentation
```

安全报告位于 `MI_HEALTH_LIVE_REPORT_BEGIN` 和 `MI_HEALTH_LIVE_REPORT_END` 之间。优先使用已有脚本处理设备、版本、报告和进程，不另造广播或数据库导出入口。

## 5. 只读 probe：独立观察，不授予重传权限

| 字段（均有 `pending_probe.` 前缀） | 含义 |
|---|---|
| `pending_rows` | 当前计划与 journal 交集，不代表全账号历史 pending |
| `verified / missing / mismatched / ambiguous / unqueried` | 宽窗口匹配、未找到、字段不同、歧义或没有成功查询 |
| `broad_read_groups / broad_read_failure_groups / broad_read_limited_groups` | 宽窗口组数、失败和上限 |
| `narrow_selected / narrow_*` | 最多 3 个抽样窄窗口与结果 |
| `broad_narrow_disagreement` | 宽窗口 missing、窄窗口出现其它状态的数量 |
| `missing_with_other_source_rows` | broad-MISSING 中相同 key/time/tag 有非删除其他明确 SID 的目标数 |
| `missing_with_deleted_rows` | broad-MISSING 中精确预期 SID/key/time/tag 有 deleted 行的目标数；可与上一项重叠 |
| `native_selected / native_found / native_missing / native_mismatched / native_ambiguous / native_unqueried` | 无起点读取的候选与观察分类 |
| `native_pages_read / native_complete_queries` | 原生读取页数及明确完成的查询数 |

宽窗口最多 32 组，窄窗口最多 3 个；原生读取最多一个 key、5 页。无 pending 时不查询。原生读取保留 `key/end_time/reverse/next_key`，省略 `start_time` 和 `limit`。只有明确 Boolean `has_more=false` 且分页元数据合法才算 complete；缺失/异常字段、空或循环游标、页数上限都不能假设结束。

分页未完成时，已经观察到的匹配/不符/歧义保留计数；未返回的目标记为 **native_unqueried**，不是 native_missing。不同 key 未被查询的目标也保留 unqueried。Probe 不改 journal、不解除 pending、不消费恢复额度；即使看到匹配也只是报告。正常同步或单条恢复会自行重新读取后完成核对。

APK 3.59.2 的 `CloudSyncLineRouter.pullFullPullFitnessData` 确实构造 null startTime，默认 Gson 会省略它，模型没有 limit 字段。原作者 Jenwein 在有限账号中比较过无起点与前移一天的窗口；现有普通读取已前移一天，因此不能把已知起点分桶案例直接当作这 132 条的原因。证据和范围见 `docs/healthlab/pending-native-read-evidence.json`。本轮新增的是独立对照，没有证实某个请求参数造成未保存。

## 6. 单条恢复的持久约束与判定

共享恢复器 `MiPendingSpO2Recovery` 的顺序：

1. 确认是显式恢复、血氧 RAW、固定导入 SID 和正常目标；在本次 HC 计划与 journal 的交集中选最早 pending。遇到问题不跳过它找下一条。
2. 将当前目标身份、规范化内容指纹、来源记录指纹与 pending 快照比较。冲突修订、来源变化、拒绝项或无可靠日期/时区时停止。旧账本未保存时区等部分旧请求字段时报告 `legacy_row_metadata_unknown`，不声称旧请求已经逐字段还原。
3. 读取覆盖前一天的宽窗口；再以目标时间加一秒作为结束边界做原生无起点读取，最多 32 页。即使尚未遍历完，已观察到的明确匹配仍可只读完成核对，冲突也立即报告；只有未找到时才检查完整性并决定能否继续。不能因为已经读到更早时间就提前认为完整。
4. 原生完整遍历仍未找到时，提交前再做一个窄窗口读取。所有检查都拒绝同目标其它来源、来源不明、deleted 行、不同内容或多份歧义。若存在可靠匹配，只完成核对，不重发；partial missing 则为 HISTORY_INCOMPLETE，不进入写入。
5. 仍未观察到目标且没有冲突时，比较并交换 pending 快照，先持久化该目标的一次恢复额度。预留失败或状态变化则不提交。
6. 最多尝试这一条 RAW，之后只读核对。记录本轮明确拒绝、业务接受、传输未知和读回情况；原始 pending 保留到匹配，日报始终为 0。

持久额度在请求前消费。若进程恰在提交前退出，可能没有真的发出 POST，但额度仍已占用；这是防止不确定重放的保守行为。明确拒绝、网络异常、返回成功但没核对，都不会重新放开额度。在同一选定来源计划内，未解决目标再次操作仍只检查同一最早 pending，不自动跳下一条；原目标确认移出 pending 后，新动作可选另一个目标。当前范围按相对时间读取，范围滚动或来源集合改变也可能改变最早候选，因此不能宣称跨任意范围固定同一条，也不能主动改范围绕过未解决记录。

`ALREADY_PRESENT` 表示没有本轮新提交；`VERIFIED` 表示本轮尝试后已读回匹配。`HISTORY_INCOMPLETE`、`READ_FAILED`、`SOURCE_CHANGED`、`SOURCE_COLLISION`、`OTHER_SOURCE_PRESENT`、`TOMBSTONE_PRESENT`、`CONFLICT`、`RESERVATION_CHANGED` 或 `RETRY_ALREADY_CONSUMED` 均不会触发本轮写入。`UPLOAD_REJECTED` 与 `UPLOAD_UNVERIFIED` 保留 pending 和已用额度，不能据此自动再调用。

恢复通过同身份、冲突检查和次数限制控制额外尝试。即使三个读取都没找到，也不能逻辑上证明旧数据从未保存；不要将显式有限恢复包装为“服务器已确认没有记录”。

普通 RAW runner 还有一层历史防重发：恢复或以往同步已将该目标 verified 后，若后来宽窗口漏返回同一身份，不把它当成全新目标重新写入。此时 `blocked_verified_history_rows` 增加，结果为 UNVERIFIED，保留 verified 历史；不能把历史事实假装成本轮读回，也不能算作仍存在 pending。复测应检查这一分支，不能仅凭 pending 减少就宣称下次普通同步会重新上传。

## 7. RHR / HRV / 日报保留范围


`docs/healthlab/rhr-day-policy-evidence.json` 保存国内 Mi Fitness 3.59.2 的静态调用链：

1. `HeartRateBiz.splitDailyReport` 收集正值 RHR。
2. 原生 `ListExtKt.minOfOrDefault` 对 `RestHrItem.getRestHr()` 选最小值。
3. 结果传给 `DailyHrReport.restHr`。
4. RAW 的 `resting_heart_rate` 又有按源日期编码、外层时区归零的一日一记录约定。

据此，`.5` 兼容映射对同一可靠源日期的**不同时刻**有效观测取实际最小值，并保留来源归属，输出 `RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED` 警告。不平均、不从普通心率推导，不生成一个源数据中不存在的数值。

仍然拒绝：相同源毫秒时间的不同值、同一记录最新修订内容冲突、非法值或缺失源时区。已有云端目标内容不同仍保留并报冲突，不因新日最小值策略允许覆盖。

用户 3 条日志没有源时刻，不能承诺升级后必然变为 1 条：若它们是正常的不同时刻观测可以合并；若是同一时刻矛盾则继续拒绝。当前最小值也只覆盖已选输入，不宣称是完整一天。

这项原生源码依据是映射理由，不是用户账号的接受证明。仍不写只有 `avg_rhr` 的稀疏日报；原始记录、日报和官方 App 图表分别验证。


HRV 仍为真实 HC RMSSD 点在匹配睡眠区间内形成的实验摘要，未匹配的样本明确排除并警告，矛盾/非法来源仍拒绝；没有证明与小米原生算法等价，没有补齐基线、评分或详细曲线。原始记录匹配、日报完整性和官方 App 展示是三个不同验收项。

普通 RHR 同步可在所有可映射 RAW 核对后只读检查 `heart_rate / daily_report / avg_rhr`；电脑 inspect/sync-one 与血氧恢复不能代表该日报检查已通过。`planned_daily_rows=0` 表示本地没生成日报，不等于小米拒收。本轮恢复故意只处理 RAW，不以补日报来掩盖 pending。

## 8. 源码与证据定位

| 能力 | 仓库相对路径 |
|---|---|
| 真机 runner / Windows 驱动 | `androidApp/src/androidTest/kotlin/com/bettermifitness/sync/lab/HealthLabInstrumentation.kt`；`scripts/Invoke-MiHealthLiveTest.ps1` |
| UI 与普通同步互斥 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/ui/sync/SyncViewModel.kt`；`composeApp/src/commonMain/kotlin/com/bettermifitness/sync/sync/SyncCoordinator.kt` |
| 共享恢复器 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/repository/MiPendingSpO2Recovery.kt` |
| 共享计划与入口 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/repository/HealthRepository.kt`；同目录 `MiHealthLiveInspection.kt` |
| pending probe / 原生读取 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/api/MiPendingImportProbe.kt`；同目录 `MiHealthImportTransport.kt` |
| journal / 持久恢复额度 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/preferences/HealthImportLedger.kt` |
| 普通导入 / 诊断 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/repository/MiHealthImportRunner.kt`；同目录 `MiHealthImportDiagnosticText.kt` |
| 映射器 | `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/api/MiHealthImportMapper.kt`；同目录 `MiHrvImportMapper.kt` |
| 默认数据传输重放保护 | `miclient/src/androidMain/kotlin/com/mifitness/miclient/HttpClientFactory.android.kt` |

`.5` 已为默认健康云传输关闭连接恢复与重定向，并设置一次性请求体。真实 Ktor/OkHttp 本地回环测试不等于用户小米账号测试，也不能证明旧 pending 由自动重放导致；依赖升级或自定义客户端必须保留等价约束。

## 9. 本地代理执行顺序与验收

**先建立同一范围的只读基线。** 安装配套 `.6`，运行血氧 inspect，保存完整安全报告。核对源读取、pending 数量、其他来源/删除提示及原生 complete 情况。缺权限、登录、数据或分页不完整均如实报告，不把空结果当作成功。

**再完成一次显式恢复。** 使用现有授权执行 `recover-one-pending -AllowCloudWrite -Metric spo2`，由恢复器检查全部条件。报告实际尝试数、旧有/新核对、服务器结果、额度状态及最终读回。它最多尝试一条，不能用一条的成功宣称整批完成；不能在未解决时改变范围或排序来跳过首条继续试写。

**按结果复核。** 已匹配时检查官方小米运动健康相同日期；记录“已显示 / 未显示 / 未检查”。未完整读取、其他来源、删除、来源变化或明确拒绝时保存诊断，由具体原因决定下一步代码工作，不删 journal、改时间戳或猜字段循环提交。重复检查不能变成自动再次上传。

**分别验证其它指标。** RHR 按第 7 节看来源日期与日报；HRV 看睡眠关联和算法边界；普通 sync-one 与恢复模式不要混用。当前相对时间范围可能滚动或出现新记录，没有证明两次目标相同，就不能将“第二次零提交”称作同目标幂等性证明。

验收至少覆盖：HC 读取、映射/排除/拒绝、旧 pending、独立读取完整性、本轮尝试、实际读回、持久额度、日报与官方 App 展示。保存报告不包含账号、测量值、源 ID、精确时间、凭据或请求体。已授权且设备连接可用时，应连续完成必要修复、构建、覆盖安装与复测；不反复要求用户手工复制同一类日志。

## 10. 给本地代理的启动指令

> 请读取本文件、HEALTHLAB.zh-CN.md 和本轮 validation.json，接手 healthlab.6 的真实设备检查。确认本地 ADB、手机既有登录和 HC 权限，覆盖安装配套主 APK 与测试 APK。先通过 scripts/Invoke-MiHealthLiveTest.ps1 的 inspect 检查血氧 pending，再按已有授权执行 recover-one-pending -AllowCloudWrite -Metric spo2 的一次恢复和读回。保留原目标身份、内容/来源指纹与持久一次额度，遇到失败不跳到下一条、不清锁或改时间。RHR、心率、睡眠和 HRV 保留现有边界，按实际需要分别检查。需要代码修复时继续构建、覆盖安装与复测；只有必须本人完成的设备连接、账号验证或系统权限才请我操作。不要把离线测试、编译、命令 COMPLETED 或 code=0 报成真实端到端成功。

## 参考

- Android ADB：https://developer.android.com/tools/adb
- Android 命令行测试：https://developer.android.com/studio/test/command-line
- HC 架构：https://developer.android.com/health-and-fitness/health-connect/architecture
- 原作者 SpO2 读取研究：https://github.com/Jenwein/mifit/blob/main/notes/11-spo2-weight-profile.md
- 原作者分页对照：https://github.com/Jenwein/mifit/blob/main/notes/08-heart-rate-keys-and-completeness.md
- 项目证据：`docs/healthlab/pending-native-read-evidence.json`、`rhr-day-policy-evidence.json`、`hrv-contract-evidence.json`、`no-replay-evidence.json`，以及版本匹配的 `validation.json`。
