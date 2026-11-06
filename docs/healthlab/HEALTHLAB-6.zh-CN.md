# Mi Health Sync Lab · Healthlab 6 使用与验证说明

版本 **1.0.6-healthlab.6**，Android versionCode **1000012**，更新日期 **2026-11-03**。

项目沿用 Better Mi Fitness Sync v1.0.6，固定基础提交 `c1dd886328c82f15faf17ec24dc485cb61a70cec`。包名 `com.bettermifitness.sync.healthlab`，应用名 **Mi Health Sync Lab**。

## 1. 本轮依据：healthlab.5 的 132 条血氧 pending

用户最新 v2 诊断已经证实：**195 个血氧目标中，63 条上传前已在云端匹配，另外 132 条被既有 pending 状态拦住；本轮尝试上传 0，提交后新核对 0。** 这次没有新的健康数据上传，不能将其解释成小米再次返回业务码 100。读取接口本身可能使用 HTTP POST，健康数据提交数与网络 POST 总数不是同一概念。

| `.5` 观察 | 可以确定 | 仍不能确定 |
|---|---|---|
| `195 = 63 + 132`；`attempted_raw_rows=0`、`newly_verified_raw_rows=0` | 当前范围内 63 条已有匹配，132 条仍待确认 | 无法从脱敏计数确定每条历史上传的实际结果 |
| pending 宽窗口 132 条均 MISSING；2 组读取成功、无失败或上限 | 本次宽窗口没有返回目标的有效同来源记录；不是宽窗口网络读取失败 | 查询成功不等于返回结果覆盖了后台所有保存形式 |
| 3 个窄窗口均 MISSING，差异数 0 | 最早、中间、最新的三个抽样目标在另一种结束边界下仍未找到 | 三个抽样不能替代全部历史的独立读取；不能由此证明云端从未保存 |
| 两次 `INCOMPLETE_LOCAL_DAY` | 当前选择范围不足以支持完整自然日日报 | 这个警告不是已经证实的 pending 根因 |

`.4` 的血氧诊断是 `219 = 72 + 147`。本轮变成 `195 = 63 + 132`，输入与既有记录数也同时改变；**147→132 不能解释为已恢复 15 条**。两份报告没有记录标识和时间，无法确定两次目标集合之间的关系。

旧验证器的 **MISSING 也可能包含“相同时刻存在其他 SID 的记录”或“目标已标记删除”**，不只代表该时刻空无一物。旧 pending 账本保存目标、内容和来源指纹，但没有为每次普通上传保留可恢复的历史业务结果；不能据此区分旧 POST 被明确拒绝、响应丢失、接受后未读回等情形。`.6` 增加独立读取和限定恢复记录，避免把不确定的历史结果直接当成可重复上传。

### healthlab.6 实际新增

- **手机端单条恢复入口。** 同步页新增“恢复一条待确认血氧”。它复用手机已有登录与 HC 权限，在当前范围内选最早的一个 pending 血氧目标，经来源、内容和云端检查后最多额外尝试这一条。手机操作无需电脑、Root、Shizuku 或第二个 App。
- **电脑入口使用同一恢复器。** `recover-one-pending -AllowCloudWrite -Metric spo2` 与手机按钮执行同一套恢复规则。原 `inspect` 仍只读，`sync-one` 仍遵守普通 runner 的 pending 拦截，两者不会隐式开启恢复。
- **独立原生读取。** 新控制读取采用国内小米运动健康 3.59.2 的 full-pull 参数形状：省略 `start_time` 和非原生 `limit`，保留结束时间、倒序和游标。普通 pending probe 最多查询一个 key、5 页；恢复器最多 32 页，未找到时只有明确结束才允许继续写入检查。未遍历完且没找到的目标记为 unqueried，不能当作不存在；已经看到的明确匹配仍可仅完成核对。
- **持久化的一次恢复额度。** 保持相同目标身份、内容和来源指纹，通过宽窗口、完整原生读取及提交前的窄窗口检查后，先以比较并交换方式持久化一次恢复额度，再提交。进程中断、明确拒绝或未核对不会自动恢复额度；同一来源计划内，未解决目标再次操作时也不会跳到下一条。每次最多 1 个 RAW 目标、0 个日报。
- **增加可解释的诊断。** probe 新增其他来源与删除标记提示、原生读取计数；恢复报告记录选择、额度状态、原生读取完整性及本次结果。保留旧 pending，不做全局清锁。
- **已核对历史防重发。** 普通 RAW 同步遇到本地曾 verified 的同一目标身份，而本轮云查询为 MISSING 时，计入 `blocked_verified_history_rows` 并报告 UNVERIFIED，不重新提交，也不清除历史。这避免单条恢复已通过原生/窄窗口完成核对后，普通宽窗口再次漏返回时把它当成全新数据。历史核对不冒充本轮已验证，也不改计为 pending。

静态 APK 与作者有限账号实测支持这种独立读取方式，但**尚未证明它能解释用户的 132 条，也未证明少一个参数就能修好服务器结果**。此前作者发现的起点分桶现象，在当前普通读取中已通过前移一天缓解；原生无起点读取是新增对照，不将已知案例套成当前根因。证据见 `docs/healthlab/pending-native-read-evidence.json`。

本版继续保留 `.5` 的 RHR 按可靠源日期取实际最小值、宽/窄窗口 probe、测试进程隔离、同签名测试 APK、PowerShell 驱动和健康云默认传输重放限制，以及更早的 HRV/血氧/心率映射。**尚未在用户手机、小米账号或 Windows PowerShell 上执行 `.6` 闭环。** 实际构建、测试、APK 哈希和签名只以版本匹配的 `docs/healthlab/validation.json` 为准，不沿用旧版本通过记录。

### 历史依据：healthlab.3 的四份实机诊断

下表保留 `.3` 的历史诊断与当时可以得出的结论，不能视为 `.6` 的新实测。日志不含测量值、原始时间或账号，不能从旧计数反推出用户的具体来源记录。

| 指标 | `.3` 实机诊断 | 可以确定的含义 | 仍不能确定的部分 |
|---|---|---|---|
| 心率 | 9,540 个目标，9,322 已核对，218 受影响，本次提交 0，PRECHECK | 9,322 条在云端已存在且内容匹配；最后汇总还有 218 个目标被阻塞 | v1 没记录失败原因，不能区分待确认锁、内容冲突、来源不明确；不能据此认定小米拒绝全部数据 |
| 血氧 | 1,542 个目标，本次提交 233，已核对 411，受影响 171，READBACK，code=0/accepted=true/ok | 最后一次 POST 业务接受；按 `.3` 的计数流程，233 条提交中 62 条已读回匹配，另有 349 条此前已存在；171 条仍未完成内容核对 | v1 未区分目标缺失、字段不同、来源不明；`ok` 不等于 233 条都保存成功，更不等于手机显示 |
| HRV | 2,888 条来源记录，10 个目标摘要，10 已核对，本次提交 0，170 受影响，PREPARE | 10 份映射后的睡眠区间摘要已在云端匹配；RAW 处理后另有来源转换问题 | 170 条可能包含未匹配睡眠、冲突或非法数据，v1 没有问题代码，不能确定实际组成 |
| 静息心率 | 27 条来源记录，1 个目标，1 已核对，本次提交 0，24 受影响，PREPARE | 唯一生成的日记录已匹配；24 是来源问题涉及的数量，并非服务器错误码 | 可能是缺少时区、同一天不同值等，不能从旧日志确定原因 |

`input_records` 是 HC 记录数，`source_raw_rows` 是转换产生的目标数：一个心率记录可含多个采样点，许多 HRV 点合成一个睡眠摘要，同一天同值 RHR 可合并。它们不能直接相减来认定丢数据。上表中的云端核对均不证明小米运动健康图表已显示。

### 继续保留的 healthlab.4 历史修复

- **血氧等原始数据按独立日期继续处理。** 已接受上传、读接口正常但部分内容仍未核对的日期保留待确认锁，随后处理其它独立日期。明确拒绝、上传传输故障或读接口故障仍停止本指标的写入；没有自动重发不确定 POST。
- **读回只读重查延长为 1 秒、3 秒两次间隔。** 用于容纳可能的延迟可见；尚未证明延迟是用户 171 条未核对的根因。较早日期后来才出现的记录会在下一次普通同步预检查时核对。
- **HRV 未匹配睡眠的样本改为明确排除并警告。** 正常白天样本不应导致已匹配的夜间摘要整体报错；同样也可能存在缺少睡眠会话的夜间样本，不能由此推断这些点都是白天。非法值、矛盾修订、多个睡眠候选仍拒绝转换。
- **部分进度不再丢失。** 指标有已核对记录但尚未完成时显示“部分已核对”；总结果也保留这些数量，不计为全量成功。只有排除项、没有可映射行时仍显示无可用数据，但可以展开并复制诊断。
- **静息心率部分来源失败也能检查已验证日期的日报。** 仅在所有已选、可映射 RHR 目标都核对成功时进行；如果只知道部分数量，不猜测哪些日期成功。
- **诊断升级 v2。** 明确问题分类、来源问题代码、字段差异、既有记录和新核对数量，避免把统计数当错误码或将前一批成功响应附到后一阶段失败上。
- **同一 HC 记录的同版本内容矛盾时不再按分页顺序随便选一条。** 精确重复仍去重，真实矛盾保留并报告；独立有效记录继续处理。

`.4` 的回归曾使用与旧日志相同的合成计数形状。例如 HRV 2,888→10+170、RHR 27→1+24 只证明这些形状可以由来源问题产生，**不是还原了用户的真实样本，也不是对那 170/24 条的归因**。`.5` 的 RHR 策略已更新，不能要求新版本继续制造旧版的日期拒绝数量。

## 2. 安装

主 APK 文件名为 `mi-health-sync-lab-1.0.6-healthlab.6.apk`。包名与签名配置保持一致，设计为覆盖安装并保留现有登录、设置与 pending；实际产物签名及兼容检查见本版 `validation.json`。也可在电脑执行：

```powershell
.\adb.exe install -r .\mi-health-sync-lab-1.0.6-healthlab.6.apk
```

手机恢复只需要主 APK。安装、登录和同步无需 Root、Shizuku 或隐藏 Activity。如果上游原版 BMFS 也在使用，应暂停它的自动同步，避免重复写入相同来源。初次安装时 HRV、血氧、单条测试与旧版睡眠补字段兼容选项默认关闭；覆盖安装沿用已有设置。

### 可选：电脑测试 APK

本地代理要通过电脑直接读取报告时，再安装同版测试 APK：

```powershell
& 'D:\CodingSoftware\adb-tools\adb.exe' install -r .\androidApp\build\outputs\apk\release\androidApp-release.apk
& 'D:\CodingSoftware\adb-tools\adb.exe' install -t -r .\androidApp\build\outputs\apk\androidTest\release\androidApp-release-androidTest.apk
```

测试包为 `com.bettermifitness.sync.healthlab.test`，应与主包同签名、同轮构建。它使用主应用的账号与 HC 权限。签名不匹配需修正产物，不能通过卸载或清空主应用绕过检查。

## 3. 这次更新后怎样操作与判断

### 直接在手机恢复一条

1. 覆盖安装 `.6`，保留原有登录、权限和待确认记录。
2. 在设置中确认已开启“血氧 → 小米”，同步范围选能覆盖问题记录的 1、7 或 14 天。恢复入口接受 1～27 天；界面选 30 天时会提示调整，不会静默裁短。
3. 打开同步页，点击 **“恢复一条待确认血氧”**。它先检查当前范围内最早的一个 pending，可能读取多页；检查不通过时不会提交。
4. 查看独立的“本次单条恢复结果”，展开并复制诊断。旧普通同步的统计单独保留，不能把这次单条结果解释为 132 条全部完成。

这个入口本身就是显式请求有限恢复；普通同步、后台同步与只读 probe 不会自动调用它。当前范围里没有可匹配的 pending，或来源已经变化时，它会说明未执行上传。

### 电脑先只读检查，再按需恢复同一目标

配套 APK 已安装后，可使用本地 PowerShell 驱动：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode inspect -Metric spo2 -RangeDays 3
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode recover-one-pending -AllowCloudWrite -Metric spo2 -RangeDays 3
```

`inspect` 为默认模式，默认血氧、最近 3 天，支持 1～27 天。它只读 HC、构造映射，并检查计划内已有 pending；不是全部新目标的云端预检查。没有 pending 时，不调用 probe 的云端数据读取。`verified_raw_rows=0` 和计划阶段的 `outcome=INCOMPLETE` 不能单独说明命令失败或云端没有数据；命令完成状态看 `runner_outcome`，问题看 `issue.*`、`pending_probe.*`。

`recover-one-pending` 必须显式带 `-AllowCloudWrite`，且只支持 `spo2`。它与手机按钮复用同一恢复器。原 `sync-one -AllowCloudWrite` 仍可用于普通单目标测试，但不会解除或绕过 pending，不能代替本轮恢复入口。

手机保持解锁并让 App 在前台；测试进程 gate 隔离正常同步，结束后脚本停止该进程，再打开主应用恢复普通使用。报告默认保存到脚本目录的 `mi-health-live-日期时间.txt`，可用 `-OutputPath` 指定。脚本未在本轮 Windows 或用户真机上执行，当前云端聊天也不能直接控制用户电脑的 USB 手机。完整用法见 `docs/live-test.zh-CN.md`，代理交接见 `docs/healthlab/LIVE-TEST-AGENT.zh-CN.md`。

### 单条恢复的实际边界

恢复器固定选取当前计划内**最早的 pending 血氧目标**，不选择最新数据，也不循环直到遇到一条可以写的记录。

- 当前 HC 计划的目标身份、规范化内容和来源指纹必须与待确认账本一致；源记录被删除、修订或产生冲突时不拿新数据替换旧目标。旧账本没有保存时区等旧请求字段时会额外标记，不声称已经还原旧请求的每个字段。
- 先读正常宽窗口，再使用不含 `start_time/limit` 的原生形状读取，最后紧邻提交前再读窄窗口。原生读取上限 32 页：若已返回可靠匹配，可以只完成核对；若发现冲突，则直接停止。只有未找到目标、服务器又明确结束游标遍历时，才能继续提交前检查。未找到且达到上限、异常游标、缺少完整性信号或读取失败时不写入。
- 任何检查发现同目标内容矛盾、来源不明、其他 SID 或删除标记时，保留记录并停止。若已读到同来源且内容匹配的记录，则只完成核对，不重发。
- 满足全部条件后，以比较并交换方式原子占用并持久化该目标的一次恢复额度，再尝试提交原目标，绝不改变时间、来源身份或数值来规避冲突。
- 本次最多尝试一个 RAW 目标，随后只读核对；不写日报，不全局清锁。提交被拒、连接中断或仍未匹配时，额度保持已用，同一来源计划内重复点击不会再次提交这条，也不会自动跳过它继续另一条。相对时间范围滚动或来源集合改变后，最早候选可能变化，不能宣称跨任意范围都锁定同一条。

这是一项有明确界限的额外尝试。多个读取都未返回，并不能在逻辑上证明旧上传从未保存；恢复通过保留同目标身份、冲突检查和持久次数限制控制重复风险，而不是声称已经证明旧记录不存在。

| `pending_recovery.status` | 本次结果如何理解 |
|---|---|
| `ALREADY_PRESENT` | 读取已匹配，完成该条核对；本次无须上传 |
| `VERIFIED` | 本次尝试提交的一条已读回匹配；仍需检查小米运动健康显示 |
| `NO_PENDING` | 当前计划中没有可选 pending，不代表所有历史都已成功 |
| `SOURCE_CHANGED` / `SOURCE_COLLISION` | 不能确认当前来源对应原目标，不提交 |
| `READ_FAILED` / `HISTORY_INCOMPLETE` | 读取失败或原生历史未遍历完，不把未知当作缺失 |
| `OTHER_SOURCE_PRESENT` / `TOMBSTONE_PRESENT` / `CONFLICT` | 发现来源、删除或内容冲突，不覆盖或重新创建 |
| `RETRY_ALREADY_CONSUMED` / `RESERVATION_CHANGED` | 恢复额度已经使用或账本发生并发变化，本次不提交 |
| `UPLOAD_REJECTED` | 本次恢复尝试被小米拒绝，保留 pending 和已用额度 |
| `UPLOAD_UNVERIFIED` | 本次恢复结果未核对，可能是提交或读回不确定；不自动再试 |

### 诊断 v2 的计数规则

| 字段/分类 | 含义 |
|---|---|
| `failure_reason` | 本次结束原因，例如 PENDING、CONFLICT、SOURCE_REJECTED、UNVERIFIED，不是服务器业务码 |
| `already_present_raw_rows` | 上传前云端已存在并匹配的目标数 |
| `newly_verified_raw_rows` | 本次尝试提交后读回匹配的目标数；不能仅凭匹配宣称一定由本客户端创建 |
| `verified_raw_rows` | 上述两项之和，不能再把三项相加 |
| `attempted_raw_rows` | 本次开始尝试提交的目标数；不能等同于服务端确认保存的数量 |
| `not_attempted_raw_rows` | 计划目标扣除尝试提交和此前已有，包含被保护性跳过及尚未处理目标 |
| `blocked_pending_rows` | 旧提交尚未核对，本轮未自动重发的目标数 |
| `blocked_conflict_rows` / `blocked_ambiguous_rows` | 内容冲突或来源/多版本歧义，被保护性跳过 |
| `blocked_verified_history_rows` | 本地曾核对的同一 RAW 目标本轮查询为 MISSING，保护性不重发；不是本轮已验证或 pending |
| `source_collision_rows` | 多个当前来源候选映射到同一目标却内容不同 |
| `readback_missing/mismatched/ambiguous/unqueried_rows` | 本次尝试提交后的最终核对分类；不能用这些零值替代旧 pending probe |
| `precheck_mismatch_field.*` / `readback_mismatch_field.*` | 固定协议字段差异名及计数，不含值；一个目标可有多个字段不同 |
| `source_rejected_records` / `issue.*` | 来源转换拒绝和问题分类；分类可重叠，不与目标行数直接相加 |
| `preserved_daily_rows` / `rhr_daily_*_days` | 保留已有日报与 RHR 日报只读检查；不证明官方 App 已显示 |
| `pending_probe.verified/missing/mismatched/ambiguous/unqueried` | 宽窗口对既有 pending 的只读分类，不是本轮新提交后的读回 |
| `pending_probe.narrow_*` | 最多 3 个抽样窄窗口，不能直接当作全体 pending 比例 |
| `pending_probe.missing_with_other_source_rows / missing_with_deleted_rows` | broad-MISSING 里发现其他明确 SID 或相同目标删除标记的数量；可重叠 |
| `pending_probe.native_*` | 一组、最多 5 页原生读取的选择、观察和完整性计数；未遍历完且未找到时为 unqueried |
| `pending_recovery.*` | 独立单条恢复的状态、候选数、持久额度及原生读取完整性；不改变旧普通同步报告 |

普通同步每次仍先读云端：已可见并匹配的 pending 可按原有规则核对，尚未匹配的保留。**设置里的“允许重试未确认的上传”是原有体重专用操作，不会解除血氧、心率、睡眠或 HRV 的 health-import pending。** 本版血氧应使用上述单条恢复入口，不能将那个旧设置当作全指标重试。

只读 probe 不改 journal，不核销额度，也不解除任何 pending。它最多读取 32 个宽窗口、3 个窄窗口和一个 key 的 5 页原生历史。`native_complete_queries=0` 且 `native_unqueried>0` 常表示此独立对照没有完成，不能作为重传依据。读取结果的差异也可能随时间变化，不预先指定为分页、延迟或删除的根因。

复制诊断不含账号、令牌、Cookie、完整请求/响应、测量值、源记录 ID、设备 ID 或精确时间。电脑驱动保存相同范围的安全报告，不自动上传远程服务。

## 4. 静息心率：修日期，并检查图表需要的日报

小米原生将 `resting_heart_rate` 作为按日记录。以 HC 源记录时区计算日键：

```text
UTC 编码的源日期 = floorDiv(原始秒时间 + 源时区偏移秒数, 86400) × 86400
外层 time = 该日键
外层 zone_offset = 0
内层 date_time = 该日键
内层 bpm = 当前有效来源观测中，同一源日期的实际最小静息心率
```

`.5` 已核实原生 `HeartRateBiz.splitDailyReport` 的调用链：有效 `RestHrItem` 进入同一集合，经 `ListExtKt.minOfOrDefault` 选择最低值，随后用于 `DailyHrReport` 的 `restHr`。结合原生 RAW 的按日契约，桥接器采用以下映射：

- 同一 HC 记录先解析最新修订，最新修订本身有矛盾仍拒绝，不退回旧修订掩盖问题。
- 同一可靠源日期、不同时刻的有效 RHR 可合并，取实际观测的最小值；同值重复合并并保留各来源记录的本地归属。
- 不同值合并时输出 `RESTING_HEART_RATE_DAILY_MINIMUM_APPLIED` 警告，说明日目标并不保留每条瞬时观测。
- **相同源毫秒时间出现不同值**仍输出 `RESTING_HEART_RATE_DAY_COLLISION` 并拒绝该日，不能改时间躲避冲突。
- 缺少源时区仍拒绝；云端同日目标已有不同值仍由 runner 保留，不被新最小值覆盖。

这个最小值来自当前选择范围内的有效观测，不保证覆盖完整一天。原生日报规则与 RAW 日期契约支持该兼容映射，但静态源码证据不能证明用户账号会接受写入，也不能证明手机立即显示。用户 `.4` 的 RHR 3 条来源日志隐藏了时间戳，只有实际读取才能判断它们是否满足“不同时刻”条件。原始 HC 瞬时记录保持原样。

普通同步的 RHR 入站流程在原始目标全部核对成功后（即使其它来源记录被拒绝转换），会额外只读查询 `heart_rate / daily_report / avg_rhr`，显示同来源的：

- 对应日报数值相符的日期数；
- 缺日报或缺静息心率字段的日期数；
- 数值或来源不明确、相互矛盾的日期数。

这次额外只读查询失败只增加警告，不把已经核对的原始记录改报为失败。即使日报相符，也仍需小米运动健康下载和刷新才能确认图表。

电脑 `inspect` 与 `sync-one` 当前不执行这条额外 RHR 日报显示检查；不能把单目标诊断当作日报或官方 App 展示已经通过。

本版不盲写只有 `avg_rhr` 的稀疏日报：当前原生构造 HR 日报存在 `maxHr > 0` 门槛，报告空值检查也依赖心率明细。只知道一个 JSON 字段存在，不足以证明不完整日报会被接受和显示。本版也不覆盖已有日报。

旧版上传的瞬时形状记录不自动删除；新版本修正日记录后，旧行可能仍在云端。诊断会帮助确认显示链，而非以“成功”掩盖日报缺失。

## 5. 血氧导入

新增独立入站开关，读取 Zepp 来源的 `OxygenSaturationRecord`，要求对应读取权限。保留原始时间、来源和原始百分比；小米的实际原始字段为：

```json
{"time": 真实秒时间戳, "spo2": 整数百分比}
```

写入 `spo2`；HC 点记录本身不能证明是手动测量，因此不会自动标成 `single_spo2`。非整数百分比按最近整数取整，并明确显示精度警告；HC 中原值不改动。非法值不会静默截断。

在源时区可靠且查询覆盖完整自然日时，可以补缺失的 min/max/avg/latest 血氧日报，不编造缺氧事件次数或设备算法结果。单条测试模式不写日报；若需验证完整日汇总，可在关闭测试模式后选最近 7 天。

## 6. HRV 的实际适配方式

Health Connect 的这个类型明确是 **RMSSD、毫秒**。目前尚未证明小米睡眠 HRV 原生算法与它相同，因此本版将开关明确标为实验映射，需同时读取 HRV 和睡眠。

流程为：

1. 读取 Zepp 的真实 RMSSD 样本及同来源睡眠会话。
2. 只关联到唯一匹配的睡眠区间；排除未匹配睡眠及明确清醒/离床时段，并分别显示警告。分期缺失或仅能确认区间关联时提示假设。
3. 使用样本的算术均值、最小值、最大值，按小米整数毫秒字段生成 `avg_hrv`、`min_hrv`、`max_hrv`。
4. 保留完整睡眠底稿。原目标不存在时，会连同睡眠行写入；已有目标则适用下面的补字段规则。

这里的算术均值不是从全夜 RR 间期重新计算的 RMSSD，也不能声称与小米原生全夜算法等价。原始 HC 数据及其精度保留。不会生成分析时间戳、基线、分位数、评分或详细 HRV 曲线。

当前官方 App 的睡眠页有读取正数 `avg_hrv` 的展示路径，但会受来源设备是否被认定为 Phone 等条件影响；实际显示仍需手机验证。基线与详细曲线走另外的数据和有效性条件，不能用三个摘要字段假装完整支持。

### 为已经同步的睡眠补 HRV

- 本版上传且读回核对的睡眠：只有当前云端内容仍与账本一致、睡眠底稿一致、三个 HRV 字段均未存在，才允许补齐。
- 已有不同 HRV：保留原值并报告冲突。
- 仅被本版“看到且相同”的外部记录：不因读到过就当成本版上传，不自动补字段。
- healthlab.2 账本没有区分“本 App 上传”与“原本已有”。若要为确定由旧版导入的睡眠补 HRV，需显式开启旧版睡眠兼容选项；仍须旧账本指纹、当前云端内容和睡眠底稿全部相符。

补字段保留云端所有已有附加字段，只增加缺失的三个摘要。POST 前再读一次确认没有变化，写后再次核对。服务端没有已确认的字段级条件更新/CAS 接口，因此仍不能保证最后一次 GET 与 POST 之间绝无其它客户端修改；默认不开放旧版兼容，是为了把不确定来源单独交给用户选择。

本 App 同步协调器禁止同进程并发同步；睡眠与 HRV 入站顺序执行。测试模式每个选中指标最多一条：对 HRV 而言，一条是一个睡眠摘要，可能包含多个真实 RMSSD 样本。

## 7. 仍保留的同步与限制

保留 healthlab.1/.2/.3 的小米出站、睡眠、心率、修订/去重、权限及体重质量修复；体重仍是最新测量模式，未加入 S400 Pro 米家设备/家庭成员专用历史取数。新增入站来源仍固定为官方 Zepp 包名 `com.huami.watch.hmwatchmanager`。

**日汇总仍采用保守条件。** 心率 `planned_daily_rows=0` 是本地转换阶段没生成日报，不是小米拒收日报。来源有 REJECTED 问题时当前会抑制整个选定范围的日汇总；时区不完整、日期覆盖不完整也会阻止对应日期生成。原始目标尚有未确认项时，本指标也不会继续写日报。因此 `.4` 的执行/诊断修复与 `.5` 的 RHR 日值/待确认探测仍不保证心率/RHR 图表立即出现。v2 会说明日汇总被抑制的原因；后续若要按干净日期独立生成，需要给被拒绝的来源记录建立可靠日期归属，不能简单忽略它们。

本版没有给官方 Zepp 增加 HC 心率/睡眠/HRV 读取权限，也没有添加它的私有上传接口；新增的是 Zepp 经 HC 向小米的路径。HC 没有收到的记录不能凭空补齐。

入站当前可使用界面的最近 1/7/14 天；选择 30 天会提示超出保守的最近 28 天限制，不静默裁短。初次在前台验证，再考虑后台权限与自动同步。iOS 未进行本轮构建，新增入站明确不支持 iOS。

## 8. 自动化测试与复现

本轮最终本地验证：**488 项 Android JVM/host 测试通过，失败、错误、跳过均为 0**；主 APK 与测试 APK 构建成功，实际二进制版本为 `.6 / 1000012`，两者同签名且与已交付 `.5` 证书一致。APK ZIP、非 debuggable 主包、测试目标 Manifest、许可文件与 baseline profile 核对通过。这里的重新打开账本回归使用内存 DataStore，不是实际杀进程、磁盘故障或断电实验；真机、个人账号、Windows PowerShell 和小米 UI 显示尚未验证。

`.6` 的针对性回归覆盖原生无起点请求形状、分页完整性与限额、132 条 pending 计数形状、其他来源/删除提示、单条恢复的来源一致性、持久额度、并发变化、明确拒绝及不确定结果、手机与电脑入口的模式限制。继续保留 `.5` 的 RHR 日最小值、宽/窄窗口 probe、测试 gate 与 HTTP 重放保护测试。实际执行结果以版本匹配的 `validation.json` 为准。

继续保留 `.4` 的日志计数关系、跨日期部分进度、等待后读回、未匹配睡眠样本、同修订冲突、部分结果统计及诊断脱敏回归。更早测试覆盖官方日期/字段契约、50 条批量边界、服务业务拒绝及 false、错误码与条数分离、单条测试不会变成整批写入、上传超时后的模拟读回、pending 不重发、HRV 来源归属/旧账本迁移/补字段前并发变化、RHR 日报只读检查、HRV 与血氧权限。

这些测试使用合成数据、Fake/模拟响应，传输测试使用本地回环服务器和实际 Ktor/OkHttp 客户端；它们能检查实现遵循已核协议和失败处理，不能证明用户账号的服务端一定接受。新增实机测试 APK 和 Windows PowerShell 驱动在本轮尚未运行于用户设备或 Windows，不能仅凭构建成功报告其线上闭环通过。最终实际测试数量、APK 哈希、签名及构建状态见 `docs/healthlab/validation.json`，必须确认其版本为 `.6`。

构建环境：JDK 17、仓库固定依赖、Android SDK platform 37.0。

```bash
./gradlew :composeApp:testAndroidHostTest :miclient:androidTest :androidApp:assembleRelease :androidApp:assembleReleaseAndroidTest --no-build-cache
```

安装包继续使用项目公开测试签名并保持非 debuggable。改动包包含从固定上游提交恢复源码所需的完整累计补丁、测试、说明与证据 JSON，不含个人账号数据、构建缓存或整份反编译源码。长期正式维护应自行保管发布签名。

## 9. 核验依据

- 上游源码：https://github.com/ilyasaftr/better-mi-fitness-sync/tree/v1.0.6
- Android HRV 类型：https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/HeartRateVariabilityRmssdRecord
- Android 血氧类型：https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/OxygenSaturationRecord
- Android RHR 类型：https://developer.android.com/reference/kotlin/androidx/health/connect/client/records/RestingHeartRateRecord
- Android 命令行 instrumentation：https://developer.android.com/studio/test/command-line
- Android 测试构建类型：https://developer.android.com/studio/test/advanced-test-setup
- `docs/healthlab/import-contract-evidence.json`：国内 Mi Fitness 3.59.2 的心率、50 条批量、RHR 日期/图表链和血氧字段核验。
- `docs/healthlab/hrv-contract-evidence.json`：HRV 序列化字段、显示/基线条件、源算法及实验映射边界。
- `docs/healthlab/rhr-day-policy-evidence.json`：`.5` 原生 RHR 最小值调用链、同一时刻冲突保护与兼容映射边界。
- `docs/healthlab/no-replay-evidence.json`：健康云客户端自动重放限制的依赖字节码依据与本地回环测试。
- `docs/healthlab/pending-native-read-evidence.json`：`.6` 原生无起点读取链、作者有限实测与分页/诊断的边界。
- 原作者读取研究：https://github.com/Jenwein/mifit/blob/main/notes/11-spo2-weight-profile.md 与 https://github.com/Jenwein/mifit/blob/main/notes/08-heart-rate-keys-and-completeness.md。
- 先前参考的 Huawei→Xiaomi 导入项目（并非 Huami Zepp 客户端）：https://github.com/Mori-17/huami-health-bridge/tree/3823d7ad05e3d03cd97942b0ed55dafae5035ba0

两项 MIT 许可继续保留在 APK assets 与改动包中。这是个人实验改版，不是小米、华米或上游作者的官方发布。
