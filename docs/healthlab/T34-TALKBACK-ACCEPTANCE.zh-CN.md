# T34 TalkBack 真机验收

当前设备已安装 `com.google.android.marvin.talkback`，但 TalkBack 尚未启用。不要由脚本自动切换无障碍服务；启用会影响用户当前的无障碍配置。

## 用户操作

1. 打开系统“设置 → 更多设置 → 无障碍 → TalkBack”。
2. 启用 TalkBack，并确认系统提示。
3. 打开 Mi Health Sync Lab 首页，进入同步设置页，再返回首页。
4. 在 200% 字号和窄屏条件下，用 TalkBack 顺序浏览：Health Connect 状态、同步选项、上次同步、后台同步、立即同步。
5. 确认每个指标状态与操作按钮只有一个清晰读屏语义，没有重复朗读、不可聚焦控件或被底部 CTA 遮挡的可操作项。
6. 完成后关闭 TalkBack，恢复用户原来的无障碍服务配置。

## 只读留证

启用期间可执行以下命令保存 UI 树；命令不授予权限、不上传健康数据：

```powershell
& $adb -s bd1aaec6 shell uiautomator dump /sdcard/healthlab-ui-talkback.xml
& $adb -s bd1aaec6 pull /sdcard/healthlab-ui-talkback.xml .\docs\healthlab\live-ui-talkback-bd1aaec6.xml
```

报告中不得包含账号、令牌、来源记录 ID 或健康数值。未完成用户实际朗读前，T34 不得标记为 VoiceOver/TalkBack 全部通过。
