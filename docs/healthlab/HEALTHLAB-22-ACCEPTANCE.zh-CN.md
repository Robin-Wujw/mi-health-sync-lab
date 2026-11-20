# healthlab.22 验收清单

2026-11-11，版本 1.0.6-healthlab.22 / 1000028。证据目录为同级 workout-sync-device-2026-11-11；交付副本位于 device-evidence。

本次约定的同步和交付验收完成。用户于 2026-11-11 明确要求两条旧缓存暂不处理，因此将其列为用户接受暂缓的已知限制，不再作为阻挡项。缓存本身仍未修复；来源缺失的评分与未实现的扩展曲线仍按能力边界说明。

| 要求 | 状态 | 对应证据及范围 |
|---|---|---|
| 正式批量运动摘要 | 通过 | timeline-final-cloud：29 条、无 default 来源；timeline-batch-upload：无待确认或冲突 |
| 最新心率/GPS 文件 | 通过 | 单条 2 个 + 批量其余 37 个；全部 39 个独立下载/解密/字节比对 |
| 心率实际解析 | 通过 | native-timeline-cloud-all：29 场、2,281 个真实点，值和时间戳逐点一致，旧布局 0 |
| 心率页面与时间轴 | 已抽样通过 | ui-observations：完整时长、缺失秒留空；两条先前已缓存记录仍有例外 |
| GPS 页面 | 已抽样通过 | ui-observations：真实地图、轨迹、动态轨迹入口；未逐页验收十条路线 |
| 两个新进程防重复 | 通过 | timeline-repeat1 / timeline-repeat2：摘要新增/修订、文件体和引用写入均为 0 |
| VO₂max | 通过 | vo2max-final-repeat：8 条、写入 0；页面出现数据及周曲线 |
| 普通同步及防回流 | 通过 | timeline-normal-flow：29 / 8 已核对，反向运动与 VO₂max 为 NO_DATA，失败 0 |
| 真实后台执行 | 通过 | background-acceptance-v10-final：SUCCEEDED、completed_with_skips、busy_owner=none，后台结果最近更新 |
| 后台覆盖范围 | 明确 | 生产 worker 使用正常 1 天范围；27 天批量是另一组证据，不能混称 |
| 可靠距离 | 通过且有限 | distance-final-source / timeline-final-cloud：3 条精确记录 + 1 条完整圈后备，共 4 场；其余不补造 |
| PAI / 活力 / 训练评分 | 明确不支持 | 没有已验证的 Zepp HC 有类型来源；没有写入替代分数，也未验证小米自动重算 |
| 安装与交付 | 通过 | 同签名可覆盖 APK；897 项测试；lint / Detekt、16 KB 对齐、补丁及 SHA256 见 validation.json |
| 两条原生客户端旧缓存 | 用户要求暂不处理 | 云端均已修正；小米 files/fitness 仍可能返回先前缓存。未清除本地数据或退出账号 |

用户不再提供日常 ADB 连接。手机已恢复普通应用进程，自动同步、Health Connect 后台读取及系统自启动已开启；日常同步由手机完成。此说明不承诺绕过系统调度，也不把未授权的应用数据清除作为完成条件来执行。

最终复核：29 场摘要、39 个文件、8 条 VO₂max、2 次新进程零写入、普通流程无回流及独立后台执行证据均通过；897 项本地测试通过。所有通过结论仅覆盖对应证据范围，抽样页面核对不扩展成逐页核对。
