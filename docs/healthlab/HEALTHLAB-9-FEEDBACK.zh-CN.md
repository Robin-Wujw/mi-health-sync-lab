# .9 用户真机反馈与受理判定复核（2026-11-03）

> 后续：用户补充小米 App 内也“缺一部分”，暂不方便 USB，选择继续手机端检查。现已将下述受理修正及只读血氧对比纳入 .10，参见 `HEALTHLAB-10.zh-CN.md`。本文中“尚未发布”描述的是本次最初源码修正时的历史状态。

本文记录收到 .9 实机报告后的进展。已交付 .9 APK 和 `validation-healthlab9.json` 保持原样；下面的严格受理解析修正属于交付后的源码修改，尚未作为新 APK 发布。

## 用户这次实际执行了什么

用户说明先点一次“恢复一条”，然后点一次“核对全部”。

| 项目 | 单条恢复 | 核对全部 |
|---|---:|---:|
| HC 来源记录 | 201 | 201 |
| 待确认候选 | 131 | 131 |
| 本次上传 | 1 | 0 |
| 本次核对成功 | 0 | 0 |
| 原生历史页数 | 上传前 6、上传后 6 | 7 |
| 查询结束 | 完整、END_OF_HISTORY | 完整、END_OF_HISTORY |
| 目标结果 | MISSING | 131 MISSING |

单条返回 `upload_business_code=0`、`upload_accepted=true`、`upload_message=ok`，该目标 `previously_consumed=false`、`consumed_this_run=true`。随后只读核对读出了同一最早候选的历史 `READBACK_UNVERIFIED / accepted=true / code=0 / MISSING`。

没有依据说用户重复提交；第二步健康上传为零。之前的 203/133 与现在的 201/131 来源集合不同，不能把数量下降解释成恢复成功，也不能凭脱敏报告断言两次选中同一源记录。原生查询均完整到 EOF，因此当前问题不能继续仅归因于分页上限。

## 确认的代码缺陷：受理判断过宽

`parseMiHealthImportUploadResponse` 原先复用体重兼容判断 `isMiWeightUploadResponseSuccessful`。后者只否决显式布尔 false：`code=0` 时，缺少 `result`、`result=null`、空对象和其他不含明确布尔结果的返回也会被判为 `accepted=true`。

所以 .9 的 `upload_accepted=true` 是**客户端当时的判定**，不能证明服务端原始响应包含 `result.result=true`。原始响应形状未保存在旧恢复账本里，现有日志无法追溯区分，不能反过来断言用户此次一定遭遇了缺失布尔值的返回。

本次源码修正：

- HC 导入要求 `code=0` 且 `result.result` 为真实 JSON Boolean true，或兼容显式扁平 Boolean true，才判为已受理。
- 显式 false / 非零业务码仍为拒绝；缺失、null、对象、数组、数字、字符串等不确定形状不会伪装成接受或拒绝。
- 不确定响应保留业务码和固定形状枚举，归为 `INVALID_RESPONSE`；保留 pending 和额度，继续读回，停止追加后续批次。读取到明确匹配仍可确认，绝不因格式异常重传。
- 旧恢复历史的文案改为“当时标记为已受理”，说明旧版可能按 code=0 推断，不改写旧账本。
- 本次仅修正 HC 导入判定；体重兼容路径单独保留，没有未经验证地扩大范围。

这修复了一个明确的错误判定，**不是已证明的 131 条缺失根因，也不会把这些记录恢复出来**。新增 6 项回归，Android 主机侧 533 项和 miclient 60 项、合计 593 项通过；见 `validation-strict-upload-ack.json`。未构建新 APK。

## 进一步协议核对

只读复核发现：

- native 每页保留有效行，只有真实 JSON Boolean `has_more=false` 才报告完整。缺失分页、缺游标、重复游标、页数上限分别保持不完整；没有根据旧时间提前停读。
- 当前唯一的 native 时间过滤是 `0 <= outer time < until`，单条结束界限为目标秒加一；原样返回的该目标不会因此被过滤。
- 错误行会使解析失败，不会静默丢弃；同一目标的其他来源、删除和冲突有独立阻挡。此次这些计数都没有命中。
- 公开反编译血氧模型使用 long 秒级 `time` 和 int `spo2`；当前 mapper 使用同一形状，没有发现把秒误作毫秒、按分钟截断或写入另一个血氧 key 的证据。
- 公开导入器同样使用 `sid=default`、`phone_id`、JSON 字符串 `value`、`zone_offset`、`deleted=false`。其固定 `zone_name=Asia/Shanghai` 与本应用空时区名称有差异，但 HC 偏移本身不能确定 IANA 区域，且没有证据此差异导致本次缺失，故未猜测改动时间或来源。

## 公开证据与可复核来源

此次未取得旧 Linux 工作区的原始 3.59.2 smali，不能冒称重新检查了那份 APK。以下使用固定公开反编译提交，与当前 APK 的潜在版本差异保留：

- [FitnessDataRequest.java](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/persist/server/FitnessDataRequest.java)：构造 UploadFitnessDataParam，上传后取 DataBooleanResult.getResult()。
- [DataBooleanResult.java](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/persist/server/data/DataBooleanResult.java)：result 字段为 boolean。
- [Spo2Item.java](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/export/data/item/Spo2Item.java)：秒级时间与整数血氧字段。
- [Mori-17 原始导入器](https://github.com/Mori-17/huami-health-bridge/blob/main/xiaomi_health_import.py)：本次读取 SHA256 `45e64f07f65eacf0f7e1c4ef40b13a07f1415d12e1f473bda69cf00ffa64fdb9`。
- [Jenwein 血氧只读研究](https://github.com/Jenwein/mifit/blob/main/notes/11-spo2-weight-profile.md)：本次读取 SHA256 `96f462feaf5573d7178f3289e91d8e5ba8be9b90acf5122bd3e4b432f4050cbd`；别人的账号观察不等于用户这次写入验证。

下载材料位于本机 `.build-tools/healthlab9-research`，未执行其中下载代码。

## 当前边界

没有设备连接、没有读取用户账户或原始响应、没有真实云端上传。尚无法判断此次是“返回没有明确受理结果”“服务端未保存/未按同一目标返回”还是其他后台行为；也没有依据让用户批量重传、重置额度或修改真实测量时间。已询问小米 App 中实际血氧展示情况以辅助区分保存和查询差异，不要求重复点击恢复。
