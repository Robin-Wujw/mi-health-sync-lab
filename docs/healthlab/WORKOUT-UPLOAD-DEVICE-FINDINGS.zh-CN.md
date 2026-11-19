# Zepp → 小米：首条运动摘要上传实测

日期：2026-11-10（Asia/Singapore）。用户明确要求继续真实上传测试。本次完成一条真实 Zepp 跑步机记录的上传、云端读回、小米列表和详情检查，以及两次进程重启后的防重复复测。

## 实测结果

| 验收项 | 结果 |
|---|---|
| 最近 27 天的云端运动基线 | 0 条，分页正常结束 |
| 真实健康写入 | 1 次，仅 1 条运动摘要 |
| 上传业务结果 | code=0，accepted=true |
| 上传后读回 | 恰好 1 条，映射字段全部相符，UPLOADED_READBACK_VERIFIED |
| 第一次重启测试进程复测 | ALREADY_PRESENT_VERIFIED，新增上传 0 |
| 第二次重启测试进程复测 | ALREADY_PRESENT_VERIFIED，新增上传 0 |
| 独立读取 27 天云端列表 | 恰好 1 条 |
| 小米运动列表 | 出现 1 条“室内跑步” |
| 小米详情 | 能打开，显示 6.01 公里、00:31:00、398 kcal，并显示平均配速 |
| 最终构建覆盖安装后只读核对 | ALREADY_PRESENT_VERIFIED，新增上传 0 |

详情由用户手动打开后通过界面树核对；手机拒绝 ADB 模拟输入，未使用权限绕过。未点击分享、删除或训练感受选项。交付中只保存必要的页面验证字段，不包含账号昵称、截图或完整界面树。

详情显示结果与云端字段核对是两份独立证据：云端逐字段与映射后的源数据比较；UI 观察记录展示内容，没有另外编写源数据到 UI 的自动数值比较器。平均配速由小米页面显示，本次未上传逐秒配速或速度文件。

## 这条实际上传了什么

源数据来自 Health Connect 中 `com.huami.watch.hmwatchmanager`。仅选择类型明确的跑步机运动，要求起止时间为整秒、时区偏移存在且一致、没有 PAUSE/REST segment、没有其他 Zepp 会话时间重叠。距离和活动热量只取与该运动起止时间完全一致、唯一的同来源记录，上传前再次读取源数据核对没有变化。

按固定提交的原生 `FitnessPersistKey`、`SportBasicReport`、`UploadSportReportParam` 和 `SportReportModel` 结构构造：

- 接口：`/app/v1/data/up_sport_records`，外层 `phone_id` 使用现有手机会话标识，`sport_records` 仅包含一条。
- 身份：sid=default，key=indoor_running，category=running；time 使用源开始时刻，保留源 zone_offset。
- 内容：start_time、end_time、duration、time、timezone、sport_type=3、proto_type=3、version=0，以及源距离和活动热量。
- 距离规范到米、热量规范到 kcal，按原生整数摘要字段四舍五入；不上传额外总热量、不补造缺失值。
- version=0 表示没有对应 FDS 文件；未写入 GPS、心率曲线、训练评分、VO₂max 或日汇总。

未覆盖任何现有云端运动。写前两次读取时间窗口确认无重叠，写后验证身份、类型、时区及所有映射字段。当前服务实际返回的外层结构、字段名及计数保存在脱敏报告中。

## 防重复与使用边界

本轮新增的是测试入口，不是普通同步开关。测试 APK 的 `upload-one-workout` 必须显式携带 `-AllowCloudWrite`；预览入口不写健康数据或账本。

每个账号只有一个持久化实验槽位。写入前先 commit 保存源身份、修订和目标内容，随后最多发送一次网络请求。后续运行始终核对同一条记录，不能自动前进到另一条源运动；源被修订、记录缺失或上传结果不确定时会停止，不自动补传。没有清锁或全量上传参数。

实验账本单独保存在 App 私有 SharedPreferences 内，不清除原心率/睡眠等导入账本，也不导出账号凭据。本轮安装保留原包名、签名、登录和设置；没有发布新正式版本。

未验证的范围仍包括其他运动类型、源修订更新、批量同步、GPS、运动心率曲线、PAI 与活力重算。测试证明这条跑步机摘要路线可用，不能据此宣布全部 Zepp 运动已支持。

## 本地验证与原始证据

最终 837 项测试结果全部通过（composeApp 777、miclient 60），其中运动上传工作流新增 23 项。覆盖单条封装、缺失字段、权限外输入、写前冲突、来源碰撞、持久化失败、超时/取消、读回失败、明确拒绝、重启后不前进、待确认不重传、历史缺失、字段不一致和畸形云端区间。

主 APK、同签名测试 APK、Android release lint 和 Detekt 均通过；lint 具体警告数和 APK 哈希见结构化验证记录。实际首次上传使用的 APK 与最终构建哈希分别记录，避免混用：首次上传后又修正了诊断标签、预览字段存在性显示，并加强了畸形云端区间检查；最终构建已覆盖安装并做只读核对，没有新增上传。

- [结构化验证记录](workout-upload-device-2026-11-10/validation.json)
- [实际上传报告](workout-upload-device-2026-11-10/workout-upload-one.txt)
- [第一次防重复复测](workout-upload-device-2026-11-10/workout-upload-repeat1.txt)
- [第二次防重复复测](workout-upload-device-2026-11-10/workout-upload-repeat2.txt)
- [上传后的云端列表](workout-upload-device-2026-11-10/workout-cloud-after.txt)
- [详情页验证](workout-upload-device-2026-11-10/ui-verification.json)
- [最终构建只读复核](workout-upload-device-2026-11-10/workout-final-readonly-verification.txt)

旧报告中 `selected_metric=spo2` 是标签遗漏，实际模式以 runner_mode 与 workout_upload.* 为准。首次预览 has_distance/has_active_calories=false 是读取尚未创建的实验槽位造成的诊断错误；实际上传与后续复测均确认两个字段存在，最终构建已修正这两处显示。

在本仓库目录执行已安装测试包的只读复核：

```powershell
.\scripts\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode preview-workout-upload -RangeDays 27
```

原生结构线索：[FitnessPersistKey](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/fitness/export/data/FitnessPersistKey.java)、[SportBasicReport](https://github.com/KurenaiRyu/XiaomiHealth/blob/04e79cf5debe3f9a43e57f6d981e472ea6b59637/decompiler/com/xiaomi/fit/data/common/data/sport/SportBasicReport.java)。这些是旧版反编译线索；本轮对应封装已得到当前账号真实服务的接受和读回证据。
