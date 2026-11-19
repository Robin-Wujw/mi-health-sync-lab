# 规格验收矩阵（T01–T37）

用例号和层级来自交付规格：U 为策略/单元测试，H 为平台主机测试，C 为客户端交互，D 为 Android 真机，E 为受控服务端验证。表中“已有证据”只覆盖列出的行为，不代表该用例所有层级均已通过。旧 `.40` 构建的设备记录不能作为当前候选包的签收证据。

| 用例 | 层级 | 已有证据 | 尚需核对 |
| --- | --- | --- | --- |
| T01 | U+C | `SyncCoordinatorTest`：改变已保存范围和指标不影响正在执行的请求 | Android/iOS 页面在 30→1 天草稿变化时的展示及网络调用数 |
| T02 | U+H+C | `SyncCoordinatorTest`、`UserSyncServiceTest`：停止、等待清理和串行替换 | 候选包观察 Stopping→新任务整个交互 |
| T03 | U | `SyncCoordinatorTest`：快速替换、已提交请求和草稿隔离 | 用原型规定的 1→7→14 天顺序复核 |
| T04 | U | `SyncCoordinatorTest`：替换任务等待旧任务退出；共享 UI 按任务实例丢弃旧权限反馈和迟到的完成结果；新任务完成后仍保留最新身份 | 原生权限弹窗与客户端渲染时序另在设备验收复核 |
| T05 | U+E | `MiHealthImportRunnerTest`：未知写入保留、回读后避免重发 | 用受控账号证明取消→换 runId 不重复 POST |
| T06 | U | `SyncCoordinatorTest`：固定窗口、取消后清理；导入账本回归保护未决写入 | 补两者组合的 30→1 天回归 |
| T07 | H+D | `UserSyncServiceTest`：观察者脱离不取消持久请求 | Activity 旋转、重建、重进页面 |
| T08 | H+D | `AutoSyncScheduleTest`：关闭时仅保留会话维护 | 多次前后台切换并观察健康数据请求数 |
| T09 | H+D | 快照与中央到期策略已分离，前台不启健康任务 | 真机复核 1/14/16/61 分钟和 periodic 来源 |
| T10 | U+H | `SyncCoordinatorTest`、`AutoSyncScheduleTest`：中央到期与两类 WorkManager 请求 | 同时到达的调度器联动测试 |
| T11 | H | `AutoSyncScheduleTest`：恢复不创建 immediate、明确启用创建 immediate、迁移不增加周期作业 | iOS 处理请求提交与恢复需 macOS/设备复核 |
| T12 | U+H | `SyncCoordinatorTest`、`AutomaticPauseDiskTest`：手动冷却、暂停截止和恢复后到期 | 核对候选包历史字段展示 |
| T13 | U+H | `SyncCoordinatorTest`、`AutomaticPauseDiskTest`：独立停止、自动暂停、关自动不取消手动 | 设备停止通知及按钮交互见 T20/T22 |
| T14 | U | `AutomaticRetryTest`、`SyncCoordinatorTest`：退避与周期分离、保存并恢复尝试号、账号和所有者隔离 | iOS 系统调度及到期取消未在 Xcode 验证 |
| T15 | U+H | `SyncCoordinatorTest`、`AutomaticPauseDiskTest`：开关和代次保护、拒绝旧代次写入检查点 | 补调度提交与真实注销竞态验收 |
| T16 | H+D | `UserSyncJournalTest`、`UserSyncServiceTest`：固定请求重新读取，Intent 仅有 runId | 真实系统回收后确认窗口、指标、账号和 runId |
| T17 | H+D | `UserSyncJournalTest`、`UserSyncServiceTest`：旧任务重投递与未就绪候选拒绝 | 真机回收期间的已准备替换及未决写入 |
| T18 | D | `HealthLabInstrumentation` 的 `schedule-background-acceptance` 与 `Invoke-MiHealthLiveTest.ps1` 保留队列后只杀测试进程，避免把测试 gate 当生产进程；真机无写入生命周期基线中，应用进程在切后台和熄屏约 5 秒期间保持同一 PID，force-stop 后进程消失并标记 `stopped=true`，重新打开后 PID 和 Activity 恢复；2026-11-19 已用 ActivityManager 等价路径移除候选包最近任务 `45042`，移除后进程消失、`stopped=false`，重新启动生成新 PID `18897` 并恢复 `MainActivity` | 该次是系统任务移除等价验证，不是物理手势截图；仍需记录系统自然回收和重启，且脚本不把 force-stop/任务移除结果承诺为后台同步成功 |
| T19 | D | 主机 `AndroidHealthAccessTest`；最新同签名 `.40` 测试包已完成 available+granted（`live-inspect-restored-spo2-bd1aaec6-2026-11-19-v2.txt`）和 available+denied（`live-inspect-denied-spo2-bd1aaec6-2026-11-19-v2.txt`），runner 不自行授予权限并输出脱敏报告 | unavailable+granted/denied 两种组合仍需受控 Health Connect provider 环境；报告同时输出指标级 `selected_read_permissions_granted` |
| T20 | D | 生产停止/取消路径与 instrumentation 隔离门已覆盖；当前不把模拟测试计为设备通过 | 手动锁屏前后按方向核对写入、受阻及无数据，尤其是已发出但结果未知的写入 |
| T21 | H+D | `UserSyncServiceTest`：前台化在健康工作前完成 | 登录刷新等待时转后台；确认 FGS 启动限制和可见错误 |
| T22 | H+D | `UserSyncServiceTest`、`UserSyncJournalTest`：ID 隔离 | 通知实际内容、过期 Stop、返回只打开状态页 |
| T23 | U | `FitnessPageCacheTest`、`SyncCoordinatorTest`：普通、医疗及运动索引从原游标恢复，失效游标重读固定窗；运动缓存不含路线，归属变化时不复用；Zepp 固定逻辑子窗口续跑。`HealthRepositorySyncAllTest` 用模拟云端验证运动索引读全后才写入 | 实际游标失效语义、设备预算和进程回收；FDS 详情和写入回读不使用读页缓存 |
| T24 | U | `SyncCoordinatorTest`、`AutoSyncWindowFairnessTest`：不同指标独立推进、连续水位不过缺口 | 补手动短窗口与未知写入的组合回归 |
| T25 | U | `AutoSourceDayPlanTest`、`HealthTimePolicyTest`：来源日与时区；中央节流使用有界时间差 | 补大幅系统时钟变化和 DST 联合回归 |
| T26 | U+H | `SyncCoordinatorTest`、`UserSyncJournalTest`、`ZeppAutomaticSelectionTest`：范围、指标、账号代次、Zepp 来源指纹和来源时区冻结；模拟权限等待期间修改配置及换区，旧请求拒绝派发 | Android 原生权限弹窗及 iOS 候选包复核 |
| T27 | U+E | `HealthWriterChangeDetectionTest`：已存运动子记录防累计量重复 | 受控读写核对跨小时/跨日及多轮结果 |
| T28 | U+H | `HealthWriterChangeDetectionTest`：读取异常、独立路线/备注保护、同等完整度修订 | 按 D02 继续确认目标元数据对权威修订的支持 |
| T29 | H | `HealthWriterChangeDetectionTest`：半开路线边界、坏记录隔离及取消传播 | — |
| T30 | U | `MiSessionRotationTest`、`TokenStoreLogoutTest`：刷新与凭据代次保护 | — |
| T31 | U+E | `TokenStoreLogoutTest`：仅明确 429 可按持久化冷却和次数限制重试；`MiWeightUploadTest`：体重上传请求 | 受控账号确认 429、读写延迟和无重放 |
| T32 | U+E | 模拟服务验证完整来源日、严格唯一性、标签来源、写前报告出现时拒绝创建，以及冲突回读保留 pending；只读诊断区分服务端已重算与候选过期报告 | 按 `DAILY-REVISION-ACCEPTANCE.zh-CN.md` 验证真实服务端语义后再决定是否启用修订 |
| T33 | U | `ZeppCloudClientTest`：999/1000/1001、同秒饱和、续页信号、边界重复、失败和恢复；压力耗尽预算后仍保留两侧上下文，片边界不重复，缺失上下文不输出部分时间线 | 真实协议、压力事件跨度和设备预算另在 D11 追踪 |
| T34 | C+D | `SyncLayoutPolicyTest` 覆盖 320/360dp、200% 字号及短屏；Android 同步页按策略切换上下布局，按钮允许换行，操作可进入滚动内容，指标状态合并读屏语义并有稳定标签；iOS 指标行使用 `ViewThatFits`；真机现场检查 200% 字号、320dp+200% 窄屏和 TalkBack UI 树，确认 CTA 完整可见，底部状态进入滚动内容，Health Connect 标题按空间省略；TalkBack 开启后的 3 个交互容器分别暴露“同步选项”“设置”“立即同步”语义，用户确认实际朗读正常。原始 UI 图像/XML 含账户标识，已删除不作为交付物；人工步骤见 `T34-TALKBACK-ACCEPTANCE.zh-CN.md` | VoiceOver 仍需 iOS 设备验收 |
| T35 | CI | `scripts/verify-ci-gates.sh` 校验 host/miclient 测试、Detekt、版本、备份、iOS、签名、APK 证书和失败上传门禁；无签名变量时 Release instrumentation 构建在签名门禁停止，首次配置和配置缓存复用均验证 | GitHub CI 实际运行、主机测试失败注入、受保护签名、发行 APK 元数据及旧版升级 |
| T36 | H+D | `AndroidWorkoutRouteCacheTest`：旧格式、有效期、账号和撤权；`scripts/verify-backup-exclusions.sh` 静态校验 preferences、云备份和 D2D 排除 | Android 真机云备份和 D2D 迁移 |
| T37 | iOS | `AppleHealthWritePolicyTest`、`AppleHealthRevisionTest`：共享策略；`scripts/verify-ios-healthkit-gate.sh` 校验 entitlement、HealthKit bridge 和 `IOS_HEALTHKIT_VERIFIED_COMMIT` 发布门禁 | 按 `IOS-HEALTHKIT-ACCEPTANCE.zh-CN.md` 运行 Xcode 和 HealthKit 设备用例 |
