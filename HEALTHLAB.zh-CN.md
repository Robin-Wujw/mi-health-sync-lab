# Mi Health Sync Lab · 当前版本 .20

当前版本为 **1.0.6-healthlab.20 / 1000026**，以 `androidApp/build.gradle.kts` 为准。Zepp → 小米方向以 Zepp 为准，云端不一致的记录会被更新；登录刷新不再丢失新 token。说明与验证见 [Healthlab 20 说明](docs/healthlab/HEALTHLAB-20.zh-CN.md)。每个版本的说明都在 `docs/healthlab/HEALTHLAB-<版本>.zh-CN.md`。

以下保留为 .7 制作时的历史说明，其“最新用户反馈”和验证状态不代表当前版本。

# Mi Health Sync Lab · Healthlab 7 历史使用与验证说明

版本 **1.0.6-healthlab.7**，versionCode **1000013**，更新日期 **2026-11-03**。

主包名 `com.bettermifitness.sync.healthlab`，沿用 Better Mi Fitness Sync v1.0.6 的固定基础提交 `c1dd886328c82f15faf17ec24dc485cb61a70cec`。本版继续完善 **官方 Zepp → Health Connect → 小米运动健康** 中的待确认血氧处理。

## 1. 这次解决什么

`.6` 已能在手机上恢复一条 pending 血氧，但通过独立读取找到多条匹配记录时，仍需要逐条处理。`.7` 新增 **“核对全部待确认血氧”**：一次检查当前范围内的 pending，将已经明确匹配的记录批量完成本地核对。云端没有匹配、查询不完整、来源变化或存在冲突的目标保留状态。

**这个新按钮不会上传任何健康记录。** 它会更新本地核对状态，因此与完全不改账本的“只读检查”不同；它也不等于批量重传。

当前最新真实反馈仍是 `.5`：195 个血氧目标中 63 条已匹配、132 条 pending；本次上传 0、新核对 0，宽窗口没有返回这 132 条的匹配，3 条窄窗口抽查也未返回。本轮没有收到 `.6` 或 `.7` 的真实手机诊断，所以不能说用户的 132 条已经恢复，也没有证实旧上传失败的具体原因。

## 2. 手机使用：先核对全部，再判断是否需要单条恢复

1. 覆盖安装 `mi-health-sync-lab-1.0.6-healthlab.7.apk`。保留现有登录、权限和待确认记录，不卸载或清除应用数据。
2. 设置中开启“血氧 → 小米”，选择覆盖问题记录的 **1、7 或 14 天**。专用操作接受 1～27 天，30 天会提示调整。
3. 打开同步页，先点 **“核对全部待确认血氧”**。
4. 在 **“本次待确认血氧处理结果”** 中展开并复制诊断。结果与普通同步统计分开保存。
5. 如果还有未解决目标，再根据诊断决定是否点已有的 **“恢复一条待确认血氧”**。单条恢复可能真实提交一条；批量核对不会自动转入该操作。

两按钮与普通同步互斥，也与资料刷新、后台会话维护共享协调锁。各自有独立加载状态，不会把专用操作显示为普通同步正在进行。

主应用覆盖安装可以直接使用上述操作，不需要测试 APK、电脑、Root 或 Shizuku。原签名配置保持一致；最终实际签名及版本核查以本版 `docs/healthlab/validation.json` 为准。

## 3. 两个按钮的区别

| 操作 | 小米云端读取 | 上传健康记录 | 更新本地状态 | 目标 |
|---|---|---|---|---|
| 核对全部待确认血氧 | 是 | **0 条** | 仅核对明确匹配且本地快照未变的目标 | 当前范围内已有 pending |
| 恢复一条待确认血氧 | 是 | 满足条件后最多 **1 条** | 保存恢复额度与结果 | 当前范围内最早一条 pending |
| 普通同步 | 是 | 按选中指标和正常规则 | 正常导入记录 | 当前来源计划；仍不重发 pending |

“核对全部”中的“全部”指当前读取范围内、仍有来源记录可对应的 pending，不是整个账号的全部历史。本轮剩余数是候选数减去本轮完成核对数，不是重新扫描得出的全局 pending 总数。

### 批量核对如何避免错误清锁

- 先核对源记录身份、内容指纹、来源集合、已知时区及原 pending 快照。同目标来源冲突或不可确认的修订不随意选一个版本。
- 最多读取 32 个宽窗口日组；需要独立对照时，再读取最多 32 页的原生历史。普通窗口全已明确匹配时不强行遍历整个账号历史。
- 合并全部返回观测后统一判断。任一次查询发现同目标删除标记、其他 SID 或内容冲突，都会阻挡该目标，后来的匹配不能覆盖先前冲突。
- 历史未完整结束时，已经明确返回的匹配仍可以核对；未返回目标计入 `unqueried`。只有明确完成遍历后未返回，才计入 `missing`。`missing` 仍不证明服务器从未保存。
- 所有云端读取结束后，使用一次本地 DataStore 事务核对。每条记录都比较原 pending token 和恢复预算；读取期间本地状态变化时跳过该条。
- 同一事务保存 observed fingerprint、更新既有恢复结果并清除对应 pending。保留此前业务码和恢复额度，不把已读到的记录冒充本 App 新上传。
- 不上传、不预留新的恢复额度、不清除不匹配记录，不全局重置账本。

若计划包含无法可靠归属到目标的 REJECTED 来源问题，当前实现会保守阻止该计划的核对；报告明确显示来源问题，不自动忽略它们。

## 4. 新诊断怎样看

新报告继续使用 `import diagnostic v2`，增加 `pending_reconcile.*`。

| 字段 | 含义 |
|---|---|
| `pending_reconcile.cloud_read_only=true` | 本次没有向云端上传健康记录；读取接口本身可能用 HTTP POST |
| `pending_reconcile.updates_local_journal=true` | 匹配记录允许完成本地核对，与只看报告的 inspect 区别明确 |
| `candidate_rows` | 当前来源计划里的去重 pending 候选数 |
| `reconciled_rows` | 实际完成本地核对的条数，不仅是“读到了匹配” |
| `unresolved_rows` | 本轮候选减去本轮已核对数；不是全局 pending 数量 |
| `source_changed_rows` / `source_collision_rows` | 来源与原快照不一致或存在同目标矛盾 |
| `cloud_conflict_rows` / `deleted_rows` / `other_source_rows` | 明确观察到内容、删除或来源冲突 |
| `missing_rows` | 完整历史遍历后仍未返回匹配目标，不授权重传 |
| `unqueried_rows` | 没有足够完整的读取结果，不冒充缺失 |
| `journal_changed_rows` | 云端已有匹配，但本地 CAS 没通过 |
| `persistence_failed_rows` | 云端已有匹配，但本地保存失败，未计为成功 |
| `history_pages_read` / `history_complete` | 原生查询页数及是否明确结束；可能未调用历史查询 |

这些候选结果分类互斥，相加等于候选总数。宽窗口失败次数等属于请求统计，不再加到候选分类里。

批量核对的 `attempted_raw_rows`、`attempted_daily_rows`、`newly_verified_raw_rows` 始终为 0。已经完成本地核对的记录计入 `verified_raw_rows` 和 `already_present_raw_rows`。`raw_batch_limit=0` 表示该入口没有上传批次。

| `pending_reconcile.status` | 含义 |
|---|---|
| `COMPLETE` | 当前候选全部完成本地核对，不表示整账号或小米图表全部完成 |
| `PARTIAL` | 一部分候选核对完成，其他记录保留；看分类计数 |
| `INCOMPLETE` | 本轮没有候选完成核对，查看来源、冲突、缺失或读取分类 |
| `NO_PENDING` | 当前来源计划中没有 pending，不等于所有历史已成功 |
| `LOCAL_SAVE_FAILED` | 本地批量保存失败，不把云端匹配直接报作已完成 |
| `NOT_REQUESTED` / `UNSUPPORTED` | 未明确请求或传入不支持的计划，没有处理 |

例如，假设某次当前候选 132 条，云端明确匹配且本地成功核对 100 条，则会显示 `reconciled_rows=100`、`unresolved_rows=32`，同时上传数仍为 0。这只是说明计数规则的示例，不是用户实测。

报告不输出凭据、账号、设备 ID、来源记录 ID、健康数值、健康记录时间戳或请求/响应正文。

## 5. 电脑入口（可选）

配套工具包包含主 APK、同版测试 APK 和 PowerShell 驱动。手机需已登录并授予 HC 读取权限，USB 调试连接正常且手机解锁。脚本沿用现有手机会话，不需要上传账号令牌。

四种模式：

| 模式 | 健康上传 | 本地账本变化 | 写入开关 |
|---|---|---|---|
| `inspect` | 0 | 无 | 不带 |
| `reconcile-pending` | 0 | 仅明确匹配的核对 | 不带 |
| `sync-one` | 最多一个普通 RAW 目标 | 正常导入记录 | 必须 `-AllowCloudWrite` |
| `recover-one-pending` | 最多一个既有 SpO₂ pending 目标 | 恢复额度和结果 | 必须 `-AllowCloudWrite` |

`reconcile-pending` 和 `recover-one-pending` 仅支持 `spo2`。完整安装与命令见 `docs/live-test.zh-CN.md`；在解压工具包目录使用其同目录说明。默认 `inspect` 不变。

工具执行结束只说明 runner 已完成，不能替代内部诊断或小米 UI 检查。本轮云端工作环境的 `adb devices -l` 没有用户手机；不能从这里直接执行手机上的线上同步。能访问用户电脑 USB ADB 的本地开发代理可使用工具包，交接见 `docs/healthlab/LIVE-TEST-AGENT.zh-CN.md`。

## 6. 单条恢复与其他指标的既有边界

单条恢复沿用 `.6`：保持原目标身份、值和来源，通过检查后先持久保存一次额外尝试额度，再至多提交一条；取消、拒绝或响应丢失不会恢复额度，同一来源计划内不会跳过已用额度的最早未解决目标继续发送。批量核对发现它已实际存在时，可更新其已核对状态，额度仍保留。

本轮新增功能针对 pending SpO₂。睡眠、心率、静息心率、HRV 和体重沿用现有映射及同步规则；不能将血氧批量核对测试当作这些指标新增线上验证。RHR 日记录与小米图表、HRV 实验摘要与设备原生算法仍需分别判断。完整历史设计、字段和 `.5/.6` 依据保留在 `docs/healthlab/HEALTHLAB-6.zh-CN.md`。

## 7. 验证与源码恢复

本轮最终本地验证：**519 项测试通过，失败、错误、跳过均为 0**，其中本版新增 31 项。主 APK、同版测试 APK 构建成功，实际二进制版本为 `.7 / 1000013`，两者同签名且与 `.6` 一致；ZIP、非 debuggable 主包、测试目标 Manifest、许可文件及 baseline profile 核验通过。没有执行用户真机、个人账号或 Windows PowerShell 测试。

本轮新增回归包括：195 来源/132 pending 的合成形状、零健康上传、跨窗口冲突优先、partial 历史的已匹配/未知分类、源变化、CAS 并发变化、本地保存失败、预算与上传归属保护、诊断脱敏，以及真实临时 PreferencesDataStore 文件关闭 scope 后重新打开。

真实文件测试不是实际杀进程、磁盘损坏或断电试验。所有合成测试都不证明用户账号已保存或小米 App 已显示。最终测试数、实际二进制版本、签名、APK 哈希及真实执行范围只以本版 `docs/healthlab/validation.json` 为准。

累计改动包从固定上游提交恢复 `.1` 至 `.7` 的全部源码、测试、说明与许可，具体命令在改动包 README 中。构建入口：

```bash
./gradlew :composeApp:testAndroidHostTest :miclient:androidTest :androidApp:assembleRelease :androidApp:assembleReleaseAndroidTest
```

Windows 使用 `gradlew.bat`。新增资源后应重新生成 Android 资源输出，避免新代码与旧资源 ID 错位。主包继续为非 debuggable release，并沿用项目公开测试签名以兼容已交付实验版；长期正式维护应自行保管发布签名。

关键源码为 `MiPendingSpO2Reconciler.kt`、`HealthImportLedger.kt`、`MiPendingSpO2Recovery.kt`、`MiHealthImportRunner.kt`；新增本地证据在 `docs/healthlab/spo2-reconciliation-evidence.json`。APK 内保留两项 MIT 许可。
