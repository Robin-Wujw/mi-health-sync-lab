# Health Lab 33：检查上游并修正续期 token 的 Cookie 编码

版本 `1.0.6-healthlab.33` / `1000039`。覆盖安装保留 .32 设置、同步账本和运动备份。

## 上游核对

2026-11-12 通过 GitHub API 读取源项目的全部开放/关闭 issue 和 PR 列表、登录相关 PR 合并状态、issue #2 全部 17 条评论，以及当前主干。没有向 GitHub 发布消息或提交修改。

- [issue #2：mi cloud disconnecting](https://github.com/ilyasaftr/better-mi-fitness-sync/issues/2) 仍开放，最初就描述每 2～3 天断连、需要重新验证。
- [11 月 8 日的用户反馈](https://github.com/ilyasaftr/better-mi-fitness-sync/issues/2#issuecomment-6727826008) 明确表示，使用 1.0.6 和 PR #55 保活后仍出现 70016；浏览器登录也不是已经证明能解决这个问题的方法。
- [PR #55](https://github.com/ilyasaftr/better-mi-fitness-sync/pull/55) 于 10 月 12 日合并，加入独立后台续期及并发共享刷新，发布说明列入 1.0.6。该修复已在本地实验分支历史中，不是遗漏未合入的补丁。
- [PR #26](https://github.com/ilyasaftr/better-mi-fitness-sync/pull/26) 是更早的持久登录修复，已合并；[PR #23](https://github.com/ilyasaftr/better-mi-fitness-sync/pull/23) 已关闭但未合并，不能把关闭等同于合并。

读取到的主干为 `45587fc09cf76f7a3cebdbab757f3b119649f7f9`；其 `MiAuth.kt` 最后一次变更仍为 10 月 12 日的 `ddd3061`。没有找到比 PR #55 更新、已经合并的登录续期修复。因此，.32 的更积极周期不能据此称为已解决反复失效根因。

## 复现的代码缺陷

当前源项目及 .32 的三个 passToken Cookie 写入点都没有指定原样编码方式，Ktor 默认使用 URI 编码。采用纯合成凭据、真实 MiAuth 及 Ktor Cookie 插件的测试发现：本地保存的 `V1:synthetic+token/with=padding` 在续期请求中被发送为 `V1%3Asynthetic%2Btoken%2Fwith%3Dpadding`。

该结果证明请求改变了保存的凭据值。模拟服务端要求 token 字节一致时会拒绝这次请求；只测试字母数字 token 的旧用例无法覆盖这种情况。**本轮没有读取用户实际 token，也没有向真实小米服务发送这些合成值；因此尚未证明用户本次失效就是由这个缺陷导致。**

修正集中在 `PassportHttpSession.seedPassTokenCookie`，使用 `CookieEncoding.RAW` 保存 opaque token；MiAuth 的首次续期、登录 Cookie 重设和轮换后 Cookie 更新均调用同一方法。已有的响应头即时保存和后续失败/取消保护保留。

新增两项回归：普通 token 与含 Cookie 合法标点的 token 均原样发送；连续两次轮换的新 token 正确保存，并在下一次续期原样使用。修正前第一项失败，修正后通过；测试只使用合成数据。

## 使用与验收

覆盖安装 .33。若目前已提示需要重新登录，在同步页完成一次重新登录，取得新凭据。新版本保留 .32 的前台检查、6 小时主动续期、3 小时后台检查及退避；本次更改修正的是发送凭据的方式。被服务端真正撤销的旧凭据仍不能保证恢复。

Compose 948 项、miclient 73 项、Python 接收端 6 项，共 **1,027 项测试通过**。Release、instrumentation APK、Lint、Detekt、版本、签名、非 debuggable 主包、ZIP 完整性及 16KB 对齐的最终结果见交付校验文件。

本轮没有手机操作、读取实际账号凭据、真实登录续期或健康云写入。.33 的实际重新登录和跨多日保持效果仍待用户验证，不因模拟服务端测试或上游同类反馈而标为真机通过。
