# Zepp → 小米：运动、PAI 与活力指标的调研和测试设计

日期：2026-11-10。基于当前 healthlab.21 工作区；这是研究增量，尚未发布新的正式版本。

最新进展：用户要求继续上传后，已完成 1 条真实跑步机摘要的上传、云端读回、页面展示和两次零新增复测，见 [首条上传结果](WORKOUT-UPLOAD-DEVICE-FINDINGS.zh-CN.md)。下文“尚未实现/执行”保留为最初的分阶段设计；普通批量运动同步和 PAI/活力仍未实现。

后续更新：同日手机连接后已完成 7 天/27 天只读检查，分别读到 9/29 条真实 Zepp 运动。详见 [真机结果](WORKOUT-PAI-DEVICE-FINDINGS.zh-CN.md)；以下“没有连接设备”的描述保留为最初离线设计时的状态，不是最新真机状态。运动上传和评分重算仍未验证。

## 本轮结论与实际交付

先确认 Zepp 实际输出，再验证小米的运动协议，最后才做单条导入和页面验收。PAI 和活力指标单独验证，不把两个分数直接互换。

本轮已实现 `inspect-workouts` 只读检查模式、Android 读取器及本地测试。检查覆盖 11 类 HC 记录，不调用小米云端，不操作上传账本，不新增运动导入开关。新增 Manifest 读取声明仅用于手动授予权限后的检查，正常同步不自动请求这些新增权限。

2026-11-10 检查 ADB 时没有连接的设备。因此本轮没有真实 Zepp 运动样本、没有云端运动上传、没有小米 App 页面或评分重算的实测结论。构建通过也不改变这个结论。本地验证结果见 `workout-pai-validation.json`。

## 证据与尚缺内容

| 项目 | 已有证据 | 仍需验证 |
|---|---|---|
| Zepp 输出运动 | 仓库保存的国区 Zepp 10.10.1 Manifest 声明 WRITE_EXERCISE、WRITE_EXERCISE_ROUTE 等 | 当前手机、版本、账号和设备实际输出的类型、时间、单位及更新行为 |
| 小米运动读取 | `MiDirectApi.getSportRecordsByTime` 已实现独立运动接口和分页 | 真实外部来源记录的读取、字段完整性和页面显示 |
| 小米运动写入 | 旧版反编译资料有 `data/up_sport_records`、`UploadSportReportParam` 和 `SportReportModel` | 当前地区接口是否仍接受；`key/sid/category/time/value` 的准确语义；最小必填字段及稳定身份规则 |
| PAI | 旧资料有 `DailyPaiReport` 的 pai、totalPai 及低/中/高强度分项 | 是否适用于当前客户端；Zepp 是否存在可读的分数来源；可写协议及算法是否同义 |
| 小米活力指标 | 小米官方 Band 9 页面说明最近 7 天中高强度活动累计为分数 | 导入外部心率或运动是否参与计算，何时更新，账号/设备适用范围 |
| VO₂max | HC 有标准 Vo2MaxRecord，保存 measurementMethod | Zepp 当前是否导出，小米是否接受外部来源和对应测量方式 |
| 训练负荷/效果/恢复时间 | 不在本轮运动摘要的标准映射内 | 各自的算法、数据来源、写入和展示路径，不能由距离/心率自行编造 |

静态权限证明“声明有能力”，不能证明已有运动数据。旧版反编译结构是协议线索，不是小米当前官方 API 契约。`value` 是字符串容器，不代表已经知道里面的运动摘要结构。现有小米 → HC 的运动映射也不能直接反转：类型、暂停与字段在导出时可能已丢失。

参考来源（本轮在线读取，或已在本地归档）：

- [Google：HC 数据类型和权限](https://developer.android.com/health-and-fitness/health-connect/data-types)。距离、活动热量、总热量、心率、速度、功率与运动会话是独立记录；当前标准类型表没有 PAI/小米活力的直接字段。步频使用 READ_STEPS，骑行踏频使用 READ_EXERCISE。
- [Google：运动轨迹读取与用户同意](https://developer.android.com/health-and-fitness/health-connect/features/exercise-routes)。读取会话不等于取得轨迹；ConsentRequired 与 NoData 必须区分。
- [小米 Band 9：活力积分说明](https://www.mi.com/global/product/xiaomi-smart-band-9/)。不能由该产品页推导所有小米设备/账号都支持外部数据重算。
- 仓库证据：`zepp-cn-10.10.1-health-permissions.json`，仅对应该 APK 的哈希与版本。
- [旧版反编译 FitnessApiService](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/persist/server/service/FitnessApiService.java)、[运动上传封装](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/persist/server/data/UploadSportReportParam.java)、[运动记录结构](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/persist/server/data/SportReportModel.java)、[PAI 结构](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/export/data/aggregation/DailyPaiReport.java)。固定提交供复查，未验证其与当前安装包一致。

## 第一阶段：确认 Zepp → HC 的实际输出

来源固定为 `com.huami.watch.hmwatchmanager`，不是 Zepp Life。先选最近 7 天，再在确实存在运动的日期做对照。目标样本优先为跑步、步行、骑行各一条；另收集一次暂停/恢复、跨午夜运动、室内运动和带 GPS 的运动。没有的样本记录为未测，不合成健康记录凑覆盖。

| 数据 | 检查方式 | 后续映射规则 |
|---|---|---|
| 会话 | 类型、起止、来源、时区、laps、segments 是否存在 | 未知运动类型先拒绝或明确映射为通用类型；HC running 不自动等于户外跑 |
| 时长 | 对照 Zepp 页面中的总时长与运动时长 | end-start 只能证明经过时长；没有明确暂停信息就不冒充有效运动时长 |
| 距离 | 独立 DistanceRecord，单位规范到米 | 不把全天累计距离或跨会话区间按时间比例摊入运动 |
| 热量 | 分开读 ActiveCaloriesBurnedRecord 与 TotalCaloriesBurnedRecord | 总热量不改名成活动热量；缺失时不补零，不把两者相加 |
| 心率/速度/步频/踏频/功率 | 同来源，按样本时间检查与会话的关系 | 采用 [start,end)；重叠会话不猜归属；同一时刻不重复累加 |
| 轨迹 | 分别统计 Data、ConsentRequired、NoData | 先做无轨迹摘要；后续只能通过支持的前台授权流程读取轨迹 |
| VO₂max | 独立读取，保留测量方式 | 不能仅凭同一天或时间接近就绑定某次运动 |

新增检查器只报告数量与能力状态，**不会输出距离、心率、坐标、精确时间、标题、备注、源记录 ID 或账号凭据**。因此还需要在手机端对照 Zepp 详情，才能完成字段值与单位验收；这个报告本身不证明某条记录可上传。

时间关联仅作为调查线索：

- `contained_one_records`：整条指标记录落在恰好一个完整窗口内的会话中，且不与其他会话重叠。
- `ambiguous_records`：指标区间与多次会话重叠，不选择归属。
- `crosses_boundary_records`：与一次会话有交集，但跨越边界，不能当成完整运动指标。
- `outside_records`：未与任何完整窗口内会话相交。
- `samples_in_one_session`：样本时刻恰好位于一次会话内，只是时间上的候选关系。

即使 `contained_one_records>0`，同来源的日常活动记录仍可能恰好落在运动内；必须对照源端确认。报告固定标记 `association=temporal_only_not_proven` 和 `import_ready=false`。

会话跨查询边界时计入 `crossing_window_records`，不当成完整运动。这里的“读取完整”只说明该类型的分页读取完成，不保证 Zepp 已全部同步、HC 历史授权完整或不同类型属于同一原子快照。报告同时标记 `snapshot=non_atomic` 与 `history_visibility=provider_and_existing_grants`。

## 第二阶段：运动导入器的实现与离线验收设计（尚未实现）

保留独立 `workouts` 入站类型、原始来源身份和修订信息；不要复用当前日级心率/睡眠目标身份。最小目标为类型、起止时间、准确标注含义的时长，距离/热量/心率仅在来源可靠时附加。轨迹和高阶评分不作为摘要首版的必填项。

| 用例组 | 必须验证的行为 |
|---|---|
| 单位和精度 | 米/千米、秒/毫秒、kcal/kJ 明确转换；能量缺失与零值区分；目标精度按真实协议确定 |
| 时间与暂停 | 时区缺失、跨日、时区切换、暂停不完整、零时长/负时长；不以手机当前时区补造源日期 |
| 来源与归属 | 拒绝本 App 回流、非 Zepp 来源、全天累计混入及重叠会话歧义；未知运动类型不默认为跑步 |
| 幂等 | 同源 ID/修订重复同步不新增；两个同秒开始的运动不合并；源起止修订不遗留重复运动 |
| 云端冲突 | 先读后写；已有相同内容跳过；其他设备来源或多条冲突不覆盖；写入前读到变化则停止 |
| 不确定结果 | 已发出请求但超时/断网/读回失败，保持 pending；进程重启后不盲目重发 |
| 明确拒绝 | 错误码与诊断可复查；不把 HTTP 成功、业务 code=0 或单独 true 当成端到端完成 |
| 文件引用 | 首版不携带不存在的 GPS/record FDS 引用；不填虚假的设备 SID、协议版本和文件存在标记 |

实现顺序：先确认当前版本真实运动的读取模型和脱敏字段规格，再写 mapper/transport/读回验证器/账本。只有协议和身份字段确定后才接入普通同步。当前检查器未实现这套写入器。

## 第三阶段：真实云端单条验收（尚未执行）

1. 固定一条已经核对的真实 Zepp 运动，记录源版本、类型、时区和可用字段。读取对应小米时间窗口并保存脱敏基线；查明是否已有相同运动。
2. 使用专门的有界入口，最多新增一条运动，零日汇总、零评分写入。沿用手机已有账号会话；不导出 token，不构造新的设备身份。现有 `sync-one` 不支持运动，不能用它代替未来入口。
3. 写入后以完整分页读取同一身份，比较类型、起止、时间单位、可映射字段和文件标记。缺字段或不一致就记为未核对，保留 pending。
4. 在小米 App 实际确认：运动列表出现且只有一条、详情可打开、类型和字段与 Zepp 一致。云端读回相符与 App 展示分别记录结果。
5. 再同步两次，重启后再同步一次，均应是零新上传。再测来源修订、跨日和与原小米设备记录冲突。

只有“云端读回一致 + 页面显示正确 + 重复同步不新增”全部通过，才称为摘要导入成功。不要预设存在可用的删除/回滚接口；本阶段不批量试写，不在真实账号中造假运动来探协议。

## PAI、活力与训练评分：独立实验

### A. 直接同步 PAI 分数

先寻找**真实可读来源**。HC 标准运动记录没有 PAI 分数字段；当前 Zepp 10.10.1 Manifest 也不足以证明其他来源可用。未查明来源就报告“不支持”，不从活动分钟数、心率区间或运动时长推算一个替代 PAI。

即使找到来源，也要核对每日增量、近 7 天总分、低/中/高区间分项、时区归日、滚动窗口及修订语义。小米旧 PAI 类名不能证明其当前“活力指标”与 Zepp 同算法。只有同义和可写性都得到证据后，才能另设实验导入类型；不作为运动摘要字段偷偷传入。

### B. 观察小米是否自行重算活力

这是影响评估，不是分数直接导入：

1. 先记录本地保存的活力页面基线、已有运动/心率、设备连接和自动同步状态、近 7 天窗口与时区。
2. 在已经支持心率导入的路径，观察一次“仅新增真实心率”后的变化；保留没有新增运动的对照，避免把手表随后同步产生的变化归因给本 App。
3. 摘要导入验收通过后，另选未导入过的真实运动，观察“仅运动摘要”及后续心率补齐的变化。不得反复上传同一条运动来测试分数。
4. 每步分别记录云端持久化、App 刷新后、正常下一次同步后的状态；跨日时记录 7 天窗口的自然滚动。暂时没变化标记“未观察到”，不直接断言永远不重算。
5. 有变化也要排除小米设备/后台新增数据和窗口变化；只能对测试过的账号、客户端与设备范围下结论，不能推广到所有用户。

### C. VO₂max、训练负荷、训练效果与恢复时间

VO₂max 可先用本轮只读检查确认是否有记录，之后单独研究目标写入、测量方式和页面验收。训练负荷、效果和恢复时间继续记录为未验证；不能承诺运动摘要导入后自动生成，也不以复制其他指标代替。

## 执行只读检查

需要用本轮源码重新构建主 APK 和测试 APK；此前交付的原版 .21 APK 不包含这个新模式和读取声明。本轮不修改版本号，不覆盖 `deliverables/healthlab21` 已交付文件。测试 runner 原先残留的 .20 版本校验已与工作区 .21 对齐。

构建（JDK 17、现有 Android SDK；本机已有固定版本 Gradle）：

```powershell
$env:JAVA_HOME = 'D:\MyCodes\test\.build-tools\jdk17\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=D:\MyCodes\test\.build-tools'
& 'D:\MyCodes\test\.build-tools\gradle-9.7.1\bin\gradle.bat' :composeApp:testAndroidHostTest :miclient:androidTest :androidApp:assembleRelease :androidApp:assembleReleaseAndroidTest :androidApp:lintRelease detekt --offline
```

连接并解锁手机后，覆盖安装上述两个构建产物；不要卸载或清空原 App。在系统 Health Connect → 应用权限 → Mi Health Sync Lab 中，按需要手动开启运动、距离、活动/总热量、速度、功率、爬升、VO₂max、心率和步数的读取权限。系统未提供或用户未允许的类型会报告 ACCESS_DENIED，不会当成零条。

```powershell
& 'D:\CodingSoftware\adb-tools\adb.exe' install -r .\androidApp\build\outputs\apk\release\androidApp-release.apk
& 'D:\CodingSoftware\adb-tools\adb.exe' install -t -r .\androidApp\build\outputs\apk\androidTest\release\androidApp-release-androidTest.apk
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode inspect-workouts -RangeDays 7 -OutputPath .\workout-inspection-7d.txt
```

该模式忽略 `-Metric`，范围支持 1～27 天，拒绝 `-AllowCloudWrite`。无需小米登录，不访问云端。沿用测试 gate 暂停本进程的普通同步；脚本结束后停止测试进程，重新打开普通 App 才恢复正常同步。

报告判断：`runner_outcome=COMPLETED` 只表示命令完成。逐类检查 `status`：COMPLETE + records=0 才是本次授权窗口内未返回记录；ACCESS_DENIED 表示缺少授权；READ_FAILED 表示失败或分页不可靠。失败会丢弃该类型已读的部分数据。最多 100 页、100000 条记录；重复分页 token 或重复 provider ID 都使该类型报告失败。取消信号正常向上传递。

## 验收状态

本地测试已经覆盖授权与空数据区分、完整分页、回流过滤、时间边界与歧义、心率样本裁剪、总热量隔离、跨窗口运动、分页重复、重复记录、权限撤回、读取失败、取消、非法范围、轨迹授权状态与时区偏移变化。实际数量和构建检查以同目录 `workout-pai-validation.json` 为准。

下一项需要真实设备的工作是执行第一阶段，确认 Zepp 当前实际导出的会话和配套指标；在这份证据到位以前，不能把后续运动上传与 PAI/活力实验标为通过。
