# 睡眠健康体征接入核对

核对日期：2026-11-12。基线：Health Lab .34（1000040）。本次为支持范围与协议调查，未修改运行时代码、构建新版或操作实际账号。

用户反馈：小米睡眠页面已有 HRV，睡眠平均心率、血氧、呼吸率为空，睡眠效率提示未检测到准确入睡时长。用户确认同一天 Zepp 的前三项都有，效率没有。

## 结论

| 项目 | 当前缺口 | 接入条件 |
| --- | --- | --- |
| 睡眠心率 | 现有睡眠导入未生成 `avg_hr`、`min_hr`、`max_hr` 或对应的睡眠 FDS 明细 | 按同来源、同场睡眠关联真实采样，补摘要和小米要求的明细；不能只写平均数就宣称页面已支持 |
| 睡眠血氧 | 现有睡眠导入未生成 `avg_spo2`、`min_spo2`、`max_spo2` 或对应明细 | 同上；日常血氧同步成功不等于睡眠卡片会自动补全 |
| 睡眠呼吸率 | 现有导入未写 `avg_breath`，Zepp 客户端也未读取 `RespiratoryRate/real_data` | 增加已有 Zepp 会话下的源读取和睡眠关联；实际账号接口可用性、采样覆盖与云端接收仍待验证 |
| 睡眠效率 | 来源没有提供该值，HC 睡眠起止不能直接当作准确卧床和入睡潜伏期 | 本次不生成数值；将来只有获取明确的卧床、入睡等来源事实后才能评估，不能为了让卡片显示而填默认值 |

以上为 .34 调查时的结论；后续 .35 已将前三项接入正常前后台睡眠同步，并通过原始小米解析器的合成验证。当前实施与验收边界见 `HEALTHLAB-35.zh-CN.md`；实际小米页面仍未验收。没有增加修复工具、测试按钮或独立重试入口。

## 当前实现证据

- `composeApp/src/commonMain/kotlin/com/bettermifitness/sync/data/api/MiHealthImportMapper.kt` 的 `sleep` 只生成起止、分期、时长和清醒信息，没有这三类健康体征字段。
- `MiHealthLiveInspection.kt` 的 `prepareMiHealthImportPlan(SLEEP)` 只读取睡眠；HRV 分支另读 RMSSD 并调用 `MiHrvImportMapper.sleepSummary`，因此不能把 HRV 已显示推定为其他体征已接通。
- 现有 `ZeppCloudClient` 读取评分、压力、运动，尚无睡眠呼吸流读取。
- 现有 `HealthMetricReader` 有心率和血氧读取，没有呼吸率读取。HC 能否取得 Zepp 页面中的对应数据，不能仅凭 Zepp 页面有值确定。

## 小米静态协议证据

检查本地公开 Mi Fitness 3.59.2 APK，SHA256：`112e603b8342cb134402a6dcabdf3ec16fd5171ec65a1d5f7cb7efd83cb0995d`。以下结论限于该产物，不代表用户手机当前安装版本已验证。

- `SleepSegmentReport` 序列化 `avg_hr/min_hr/max_hr`、`avg_spo2/min_spo2/max_spo2`、`avg_breath`、`sleep_efficiency`。`hrRecords/spo2Records` 是 transient 属性，不能通过伪造同名 JSON 数组补齐。
- `SleepHeartRateDayHolderV2.updateReport` 要求平均心率为正且 `hrRecords` 非空；`SleepSpo2DayHolderV2` 对血氧有同类条件。
- `SleepBiz.getSleepSegAssistInfo` 检查有效版本、SID 和分期，按睡眠文件身份取 FDS 对象，通过 `parseDailyData(bytes, 8, 0)` 读取 `AllDaySleepParseData`，再填充心率、血氧及明细属性。普通全天心率/血氧的 RAW 行不替代这条文件链路。
- `SleepBreathDayHolderV2.updateReport` 读取 `avgBreathRate`；呼吸率详细曲线另有原始睡眠数据要求，摘要支持与详细曲线支持应分别验收。
- `SleepEfficiencyHolder.updateSleepSegmentReport` 检查正数 `sleepEfficiency`；否则进入设备能力和设置提示路径。模型另有 `sleep_duration`（入睡时长）和 `bed_duration` 等字段，不得把睡眠总时长换名充当这些字段。

本次提取到本地 `.build-tools/sleep-research/` 的 Java 文件供后续实施核对；不分发 APK 或反编译源码。

## Zepp 来源参考与实施验收

只读参考 `D:/MyCodes/Vitalis/src/vitalis/adapters/zepp/streams.py`、`client.py` 和 `parser.py`：

- 呼吸率使用 v2 `RespiratoryRate`、`real_data` 事件，`value.measurements` 为 Base64 采样；必须保留起始时间和采样位置，过滤无效值时不能把剩余点向前挤。
- 心率和血氧已有独立采样接口；只取能够唯一关联到所选来源睡眠的测量，不将全天均值或静息心率冒充睡眠均值。
- 同时覆盖跨午夜、午睡、范围切换、清醒阶段、晚到数据、无权限、来源修订和重叠歧义。
- 睡眠文件上传需要稳定且不覆盖他人文件的身份、持久预留、独立字节读回及摘要引用核对；新实现不能绕过既有 pending 写入保护。
- 需要用合成数据验证原始小米解析器，并分别验收云端持久化与手机卡片/明细显示。正常同步重复执行应无重复写入。

没有读取 Vitalis 凭据、数据库或用户健康记录；没有健康云写入和手机操作。外部浏览工具本次两次均返回服务不可用；结论依据上述本地代码和公开 APK 静态分析。
