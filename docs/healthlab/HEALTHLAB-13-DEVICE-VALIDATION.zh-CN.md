# .13 重新连接后的实机验证补充

用户重新连接后，已保留数据覆盖安装交付目录中 .13 主包和匹配测试 APK。原 APK 与交付哈希不变，早先发布时的“手机未连接”是历史构建状态，现由本补充更新安装及设备验证状态。

安装版本：1.0.6-healthlab.13 / 1000019。一天范围只读验证：

- 普通 RAW 预检通过测试适配器执行，写前预留及云端上传均禁用。遇到未匹配有效目标时返回 `NEW_WRITE_BLOCKED`，没有实际上传；不能将此报告称为普通同步全部完成。
- 来源覆盖检查共 1318 个有效目标，其中云端精确匹配 1273、本地 verified 1273；45 个查询未匹配，不等于已证明云端不存在。
- 原来一个同记录、相同毫秒的矛盾时间点仍存在于来源，但新 mapper 输出 `excluded_source_points=1` 和 `HEART_RATE_SOURCE_POINT_EXCLUDED` / WARNING；`source_rejected_records=0`，`pending_rows=0`。冲突点被明确排除，未被填值、删除或计入匹配数量。
- 一天源检查为 PREPARE / INCOMPLETE，只表示它是检查模式，并非实际导入运行完成；没有伪报 `COMPLETE_WITH_EXCLUSIONS` 的实机全流程成功。
- Health Connect 支持历史读取，但历史权限仍未授权。没有代替用户授权。

测试结束已停止测试进程。主应用重新打开后才能恢复正常运行。45 个未匹配有效目标的实际上传和最终读回，以及用户界面的显示结果，尚未执行。此前更久范围 300 条待确认不在本次一天验证结论范围内。

脱敏报告位于工作区 `.build-tools/live-healthlab13/verify-heart-1d.txt`、`coverage-heart-1d.txt`、`inspect-heart-1d.txt`。全程实际健康上传为 0。
