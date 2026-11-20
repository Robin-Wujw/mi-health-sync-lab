# Mi Health Sync Lab 1.0.6-healthlab.10

本版是针对“血氧部分缺失”的手机端排查版，同时修正已确认的上传受理误判。**尚未证明能恢复当前 131 条待确认血氧。** 用户已确认小米运动健康也缺一部分血氧，并选择继续手机端检查；电脑未连接手机。

## 安装后只需一次核对

主包版本 `1.0.6-healthlab.10 / 1000016`，包名和签名沿用 .9。覆盖安装，保留登录和应用数据。

保持原来的日期范围，点一次 **“核对全部待确认血氧”**，复制完整报告即可。新增的 `spo2_comparison.*` 会自动附在报告中，不需要点“恢复一条”。

此操作健康上传为零。原有核对仅在读到严格匹配时原子更新对应本地待确认状态；新增对比模块复用本次读取，不增加云请求、不改账本、不重置额度。

## 本次新增检查

将本次来源计划分为：云端已有严格匹配、在完整查询中未返回的待确认目标，以及其他情况。其他情况包括查询不完整、来源碰撞或内容/来源/删除冲突，不会混作缺失。

| 字段 | 说明 |
|---|---|
| `source_targets` | 当前来源计划中的唯一血氧目标数 |
| `exact_matched_targets` | 本次读到严格匹配的来源目标；不是本次新增上传数 |
| `missing_targets` | 只有本次完整核对中缺失的目标才能计入 |
| `matched_minute_aligned_targets` / `missing_minute_aligned_targets` | 两组中原始时间恰好位于整分钟的数量 |
| `matched_shared_source_minute_targets` / `missing_shared_source_minute_targets` | 两组中与另一个来源目标处于同一分钟的目标数 |
| `missing_inner_time_candidate_targets` | 缺失目标附近发现内部测量时间相同、外层记录时间不同的候选 |
| `missing_same_minute_candidate_targets` | 没有上一类候选时，发现同来源、同一分钟而秒数不同的候选 |
| `missing_same_value_candidate_targets` / `missing_different_value_candidate_targets` | 候选的测量值相同或不同；只报告目标数 |
| `missing_unusable_value_candidate_targets` / `missing_multiple_candidate_targets` | 候选值不可用或存在多个候选的目标数 |
| `missing_other_source_candidate_targets` / `missing_deleted_candidate_targets` | 附近存在其他/未知来源或删除标记的目标数 |
| `exact_outer_inner_time_different_targets` | 同一来源、精确外层时间的返回行中，发现内部时间不同的目标数 |

这些字段统一以 `spo2_comparison.` 开头。候选值分类可重叠，不能相加当作缺失总数。相同血氧值很常见，同一分钟或同值都不能证明是同一条记录。全部候选只作线索，不计为已恢复；查询原有边界外的记录可能未被观察到，因此零线索也不能证明不存在。

## 为什么查分钟与内外时间

固定公开反编译提交 `04e79cf5debe3f9a43e57f6d981e472ea6b59637` 中，`DailyRecordParser` 在设备日记录解码时执行 `time / 60 * 60`，随后读取该条血氧字段。这支持检查源记录时间对齐差异。

但同一公开源码的 `DailyRecordDaoUtils` 从云端入库时保留外层时间，`Spo2RecordEntity` 的主键仍是 key/sid/time；没有证据证明云端会对我们上传的记录进行同样的分钟取整或合并。因此本版不改写 HC 测量时间、数值或来源。

RAW 读回与小米 App 日报/曲线是不同验证层：公开 `DailySpo2Report` 同时含汇总值和分时列表，`CloudSpo2Report` 只有汇总及最新点。小米 App 部分可见不能单独证明某条 RAW 已经保存或丢失。已有完整 RAW 查询缺失仍按实际证据保留。

## 受理误判修复

.9 及更早版本可能仅凭业务码 0 就给出 `accepted=true`，即使响应缺少明确的 Boolean 受理结果。本版只在显式 Boolean true 且业务码 0 时判为受理。显式 false 或非零业务码仍为拒绝；缺失、null、字符串、数字或不符合格式的对象属于未知。

未知返回仍会独立读回，保留业务码、固定形状 `upload_result_shape` 与 `INVALID_RESPONSE`，不会自动重传或继续提交剩余批次。旧账本原样保留，界面明确“旧版标记为已受理”不能证明当时收到显式 true。

这个修正确认的是客户端误判，不能追溯断言用户那次响应一定缺少 Boolean，也不能解释所有血氧缺失。

## 验证与边界

实际构建、测试数量、APK 签名、哈希、源码补丁还原结果见 `validation-healthlab10.json` 和 `patch-validation.json`。新增测试包含匹配/缺失分组、时间对齐、邻点候选、冲突隔离、隐私、零额外请求、额度保留及严格受理解析。

本次没有访问用户账号或执行真实健康上传，没有连接手机或运行手机 instrumentation，iOS 未构建。实际缺失原因仍待这次只读对照结果；测试通过不等于 131 条已恢复。
