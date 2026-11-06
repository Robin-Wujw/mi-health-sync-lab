# Mi Health Sync Lab 1.0.6-healthlab.11

版本 `1.0.6-healthlab.11 / 1000017`。本版修复血氧按秒查找、云端按分钟保存所导致的“缺失 / 待确认”误判。包名和签名沿用旧版，可覆盖安装保留登录、权限及导入账本。

## 实机证据

2026-11-03，已通过授权的 ADB 手机检查最近三天的官方 Zepp 来源血氧。源计划共 645 条，其中 197 条原始时间已经是整分钟，旧版可以精确匹配；其余 448 条原始时间带秒，云端内外时间均位于相应分钟起点。

对这 448 条逐条确认了：同一分钟只有一个源目标和一个云端候选，来源、已知时区、血氧值均一致，只有时间取整差异；没有多来源、墓碑或内容冲突。

新版执行“核对全部待确认血氧”的实际结果：

```text
input_records=645
attempted_raw_rows=0
pending_reconcile.status=COMPLETE
pending_reconcile.candidate_rows=448
pending_reconcile.reconciled_rows=448
pending_reconcile.minute_reconciled_rows=448
pending_reconcile.unresolved_rows=0
```

测试进程结束后重新启动再核对，返回 `NO_PENDING`，候选为 0。两次健康数据上传均为 0。结果只覆盖本次三天来源范围，不能据此宣称整个账号所有历史都完整。此前 201 / 131 的报告使用了不同范围，645 / 448 不是新增失败数量。

最终 APK 又通过禁用新写入的普通预检实机检查：`verified_raw_rows=645`、`already_present_raw_rows=645`、`attempted_raw_rows=0`、`blocked_pending_rows=0`、`outcome=COMPLETE`；没有触发新写入拦截。说明在重启后普通导入流程也能识别全部这批现有记录。

## 修复规则

- 源记录身份及上传时间仍保留原秒数；先前待确认锁不会因改时间而被绕过。
- 仅对 RAW / default / spo2，且内外时间、有效来源、血氧整数值和已知时区符合规则时，接受严格的分钟取整匹配。其他指标维持现有规则。
- 同一分钟多个源目标、多个云端候选、其他来源、删除标记、时区未知或不同、数值不同、拒绝的源输入均不能通过此匹配。
- 原身份和实际云端分钟身份在一个本地事务中登记实际观察指纹。原身份防重传，分钟身份用于抑制重复回写 Health Connect；核对不被标记为本次拥有的上传。
- 同一云端分钟持久绑定其源身份，跨窗口也不能被另一条源秒记录借用。已有待确认、冲突历史和单条恢复额度继续保留。
- 批量核对、普通导入预检、提交后读回和单条恢复均采用相同匹配条件；一次运行的早期冲突不会被后面的分钟匹配掩盖。

## 使用

本次连接的手机已经覆盖安装并完成上述三天核对，无需为这 448 条点击“恢复一条”。测试结束已停止测试进程，重新打开主应用即可恢复正常使用。

其他日期范围可在主应用选择相应范围后使用“核对全部待确认血氧”。它只读云端并更新严格匹配的本地状态，不上传。若有真正缺失、来源变化或冲突，仍会保留待确认并报告原因。

云端 RAW 已匹配和小米运动健康页面显示完整是两个验证层。本次没有修改已有日报，也没有验证页面所有曲线或日汇总；用户先前观察到的页面缺一部分，不能仅凭本地 pending 清零宣称已全部修复。

## 验证及测试工具

Android 共 658 项测试通过（composeApp 598、miclient 60），较 .10 新增 45 项。主包、同签名测试 APK、签名、16 KB 对齐和源码补丁还原结果见 `validation-healthlab11.json`、`patch-validation.json`。本轮未在真实账号发起任何健康上传；新数据提交后分钟读回路径由模拟云端的自动化测试验证。iOS 未构建。

独立测试 APK 的默认模式仍为 `inspect`。本版修复 Android 17 上测试 Activity 启动被后台限制卡住的问题：仅在测试隔离门生效后通过 instrumentation 的 shell 启动主 Activity，不提升健康权限。

新增 `verify-existing-spo2` 用普通导入预检核对当前 RAW：允许登记已有观察，禁用新的待上传预留和云端 POST。若需要新增上传，会在预留前阻止并报告 `NEW_WRITE_BLOCKED`，不会尝试写入真实健康数据。

```powershell
.\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode reconcile-pending -Metric spo2 -RangeDays 3 -OutputPath '.\reconcile.txt'
.\Invoke-MiHealthLiveTest.ps1 -AdbPath 'D:\CodingSoftware\adb-tools\adb.exe' -Mode verify-existing-spo2 -Metric spo2 -RangeDays 3 -OutputPath '.\existing.txt'
```

诊断只输出计数和固定状态，不导出凭据、账号、设备标识、来源记录 ID、时间戳、健康数值或请求 / 响应正文。测试报告可能包含来源记录总数等敏感统计，交付包仅保留必要的脱敏验证摘要。
