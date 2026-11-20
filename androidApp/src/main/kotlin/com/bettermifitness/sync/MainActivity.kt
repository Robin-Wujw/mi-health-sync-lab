package com.bettermifitness.sync

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.bettermifitness.sync.health.HealthConnectPermissionBridge
import com.bettermifitness.sync.sync.LiveTestSessionGate
import kotlinx.coroutines.channels.Channel

class MainActivity : ComponentActivity() {

    private val routeResults = Channel<androidx.health.connect.client.records.ExerciseRoute?>(capacity = Channel.BUFFERED)
    private val routeLauncher = registerForActivityResult(
        androidx.health.connect.client.contracts.ExerciseRouteRequestContract()
    ) { route -> routeResults.trySend(route) }

    private val permissionResults = Channel<Set<String>>(capacity = Channel.BUFFERED)

    private val requestPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        permissionResults.trySend(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HealthConnectPermissionBridge.requestRoute = { recordId ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { routeLauncher.launch(recordId) }
            routeResults.receive()
        }
        HealthConnectPermissionBridge.requestPermissions = { permissions ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { requestPermissionLauncher.launch(permissions) }
            permissionResults.receive()
        }
        if (LiveTestSessionGate.isActive) {
            // Keep HC access in a visible Activity without constructing the
            // normal navigation graph. HomeViewModel's independent profile
            // refresh could otherwise replace/close the live test API client.
            setContentView(TextView(this).apply {
                text = "正在检查健康数据同步\n请保持手机解锁，等待电脑上的结果。\n\n检查结束后，重新打开应用即可恢复正常同步。\n\nHealth sync check in progress. Keep this screen open."
                textSize = 18f
                gravity = Gravity.CENTER
                val inset = (24 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
            })
            return
        }
        enableEdgeToEdge()

        HealthConnectPermissionBridge.openHealthConnect = {
            openHealthConnectOrStore()
        }

        setContent {
            App()
        }
    }

    override fun onStart() {
        super.onStart()
        HealthConnectPermissionBridge.activityStarted()
    }

    override fun onStop() {
        HealthConnectPermissionBridge.activityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        HealthConnectPermissionBridge.requestRoute = null
        HealthConnectPermissionBridge.requestPermissions = null
        HealthConnectPermissionBridge.openHealthConnect = null
        super.onDestroy()
    }

    private fun openHealthConnectOrStore() {
        val status = HealthConnectClient.getSdkStatus(this)
        try {
            when (status) {
                HealthConnectClient.SDK_AVAILABLE,
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                -> {
                    // Settings screen for Health Connect (or provider update flow).
                    startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                }
                else -> {
                    // Install Health Connect from Play Store.
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.apps.healthdata"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata",
                                ),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata",
                        ),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
                // No browser / Play Store — ignore.
            }
        }
    }
}
