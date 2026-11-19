package com.bettermifitness.sync.localvalidation

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.bettermifitness.sync.data.MiSessionManager
import com.mifitness.miclient.api.MiApiException
import com.bettermifitness.sync.data.preferences.CredentialsStore
import com.bettermifitness.sync.sync.ExclusiveSyncActionResult
import com.bettermifitness.sync.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/** No credential export occurs until the user reviews and confirms this exact computer on the phone. */
class LocalValidationPairingActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val text = TextView(this).apply {
            textSize = 18f; gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt(); setPadding(padding, padding, padding, padding)
            text = "电脑本地验证配对"
        }
        setContentView(text)
        if (savedInstanceState != null) { text.text = "请从电脑提供的链接重新开始配对。"; return }
        val request = try { parseValidationPairRequest(intent?.dataString.orEmpty(), System.currentTimeMillis() / 1000) }
        catch (_: Exception) { text.text = "配对链接无效或已过期，请重新扫描电脑上的二维码。"; return }
        AlertDialog.Builder(this).setTitle("连接你的验证电脑")
            .setMessage("接收方：${request.recipientLabel}\n校验码：${request.fingerprint.take(12).uppercase()}\n\n" +
                "请确认这与你电脑页面显示的一致。连接后，电脑可用本机当前的小米健康短期会话，" +
                "执行你授权的健康数据读取和上传验证。会话在手机端加密后发送，" +
                "只有验证电脑能够解密，不发送密码或长期登录令牌。")
            .setNegativeButton("取消") { _, _ -> finish() }
            .setPositiveButton("加密发送短期会话") { _, _ ->
                text.text = "正在配对…"
                scope.launch {
                    try {
                        val koin = GlobalContext.get()
                        val outcome = koin.get<SyncCoordinator>().runExclusiveUserAction {
                            val session = koin.get<MiSessionManager>()
                            check(session.ensureActive()) { "Sign in required" }
                            try { check(session.api.getLatest("heart_rate", 1).code == 0) }
                            catch (_: MiApiException.AuthExpired) {
                                check(session.refreshSessionDetailed().isSuccess) { "Session renewal required" }
                                check(session.api.getLatest("heart_rate", 1).code == 0)
                            }
                            val credentials = koin.get<CredentialsStore>().loadCredentials()
                                ?: error("Sign in required")
                            withContext(Dispatchers.IO) {
                                request.send(credentials, System.currentTimeMillis() / 1000)
                            }
                        }
                        text.text = if (outcome is ExclusiveSyncActionResult.Completed) {
                            "已连接电脑。可以关闭此页，后续验证由电脑完成，无需 ADB。"
                        } else "应用正在同步，请完成后重新打开配对链接。"
                    } catch (_: Exception) {
                        text.text = "配对未确认。请检查网络、配对链接是否过期，以及本机是否仍已登录小米。"
                    }
                }
            }.show()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
