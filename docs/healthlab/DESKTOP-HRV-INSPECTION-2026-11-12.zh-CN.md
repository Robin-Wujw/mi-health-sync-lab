# 脱离 ADB 的本机账号核对

用户明确要求停止依赖 ADB，并授权在本机登录其账号。该要求之后未再调用 ADB。未安装模拟器，也未改动 .36 生产 APK。刚准备但尚未安装的手机专用 HRV 调查入口已撤下，调查工具放在工作区 .build-tools/hrv-offline-research 与已有 desktop local-kotlin 目录，未进入 App。

## Zepp：已经完成本机只读核对

复用已有本机授权，从回环地址凭据桥在进程内取得会话。没有向聊天、日志或报告输出账号、token、健康数值、睡眠时间或轨迹。没有健康云写入。

最近14天数据源检查结果：

- HRVRMSSD/real_data：14条事件，读取未触及分页上限。
- readiness/watch_score：58条事件，均存在单值 hrvBaseline；没有在所核对的字段中找到个人基准上下限或明确的 HRV 分析时间字段。
- readiness/watch_score_data：57条事件，当前结构检查没有识别出可直接对应的小米 HRV 分析/区间字段；不宣称已解码其算法内容。
- band_data 睡眠摘要：16条成功解码，未识别到上述 HRV 元数据。

这些是来源事件/修订条数，不是58个不同有效睡眠日。Zepp 的单值基准不能直接冒充小米的上下限范围。结论只覆盖本次已读接口和已识别结构。

脱敏结果：D:/MyCodes/test/.build-tools/hrv-offline-research/zepp-hrv-cloud-schema.json。

## 小米：官方登录及健康云读取已验证

用户完成独立浏览器配置中的小米官方登录后，已通过生产 MiAuth.refreshWithPassToken 正式换取健康服务会话，并成功用生产 MiDirectHealthImportCloud 读取最近14个本地自然日的睡眠。更新的会话已保存回本机加密凭据桥。先前 MissingPassToken 结果仅代表登录前状态，已被本次实际读取结果取代。一次续期成功不代表跨多日续期已经验收。

本次只读报告：

- active_sleep_rows：31（含同夜分段和多个来源，不是31个不同睡眠日）。
- native_application_source_rows：10。
- hrv_summary_rows：24；按小米夜间窗口归日去重后 nights_with_hrv_summary：14。
- native_analysis_marker_rows：0；valid_native_hrv_nights：0。
- baseline_range_rows：0；missing_timezone_rows：0。

因此，用户提供的HRV汇总已经覆盖14个夜晚，但当前云端条目均缺小米原生分析标记及个人基准区间，无法满足当前APK的有效日及区间展示条件。继续等待七天或重传相同均值不能填补这个兼容缺口。没有为消除提示伪造分析时间或基准上下限。

本次 adb_used=false、read_only=true、health_writes=0、session_refreshed=true；没有输出账号、凭据、健康原值、睡眠时间或轨迹。脱敏结果：D:/MyCodes/test/.build-tools/hrv-offline-research/mi-hrv-cloud-state.json。

## 安装包研究

小米运动健康3.61.0的安装 APK 已在本地，之前读取自手机；停止 ADB 后继续只读分析该本地文件，不再访问手机。

Zepp 官方 forwarding/watchAppLink 公开入口跳转腾讯应用宝，页面列出10.10.5包。小米应用商店当前下载页维护中；应用宝实际APK分发节点对请求返回HTTP567，安装包未下载成功，未安装。此限制不影响已经验证成功的 Zepp 本机云端读取。

## 继续工作边界

优先用本机授权云端读回、离线 APK 和合成测试研究协议。手机最终显示与后台调度可以通过应用内脱敏诊断及用户页面反馈验收，不再将 USB/ADB 作为必要条件。当前尚未写入原生分析时间或个人基准，也没有以七日均值生成虚假的小米上下限。
