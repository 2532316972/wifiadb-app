package com.wifiadb.ts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wifiadb.ts.R
import com.wifiadb.ts.core.AdbManager
import com.wifiadb.ts.core.RulePlanner
import com.wifiadb.ts.data.AdbConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻巡检服务。
 *
 * 职责：按配置周期反复「确保 adb tcp 开着 + 规则符合白名单」。
 * 与 Magisk 模块的 maintain 循环是同一个东西，区别只在于驱动方。
 *
 * 为什么需要它而不只是在界面上点一下：
 * - iptables 规则**不会**因为 adbd 重启而清空，但会被系统在某些时机清掉
 * - Tailscale 网卡会动态出现/消失（实测断开时 tun1 直接没了），规则要跟着变
 * - adbd 可能被系统重启，属性随之丢失
 * 这些都要求有一个持续巡检的循环。
 */
class AdbService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var manager: AdbManager

    /** 供界面观察的运行状态 */
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        manager = AdbManager(currentConfig())
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在启动"))
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_APPLY_NOW -> {
                scope.launch { runOnce(force = true) }
            }
        }
        // START_STICKY：被系统杀掉后重启，重启时会重新走 onCreate → 巡检继续
        // 但 START_STICKY 重启时 intent 为 null（不带 action），所以上面的
        // ACTION_STOP / ACTION_APPLY_NOW 分支不会被执行，循环正常继续。
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop() {
        scope.launch {
            // 延迟到第一个周期末再跑第一轮：让 onCreate 里的 startForeground
            // 先完成（部分系统要求 startForeground 后 5 秒内必须有前台活动，
            // 晚一轮更稳），也让刚启动时的 root 授权框有时间弹出来。
            delay(500L)
            while (isActive) {
                runOnce(force = false)
                delay(currentConfig().checkIntervalSeconds * 1000L)
            }
        }
    }

    /**
     * 跑一轮巡检。
     *
     * 顺序刻意如此：
     * 1. 先确保 adb tcp 开着（属性 + adbd 状态），否则规则建好了也没人监听
     * 2. 再建规则
     * 3. 最后读一次状态给界面
     *
     * @param force 为 true 时跳过「规则已符合预期」的快路径（用户手动点了应用时用）
     */
    private suspend fun runOnce(force: Boolean) {
        val config = currentConfig()
        manager = AdbManager(config)

        if (!config.enabled) {
            // 关闭态：把规则清掉、属性清掉，不留半开状态。
            // 注意这里只清理自己名下的规则，不动别人（模块）的。
            manager.clearRules()
            updateStateFromConfig(config)
            return
        }

        _state.value = _state.value.copy(running = true, lastError = null)

        // 1) 确保 adb tcp 开着
        val status = manager.readStatus()
        if (!status.hasRoot) {
            _state.value = _state.value.copy(
                running = false,
                hasRoot = false,
                lastError = status.rootError ?: "未获得 root 权限",
            )
            return
        }
        if (!status.isListening) {
            manager.setEnabled(true).onFailure { e ->
                _state.value = _state.value.copy(lastError = e.message)
            }
        }

        // 2) 建规则（带快路径判断）
        val tailscaleIf = status.tailscaleInterface
        val plannedV4 = RulePlanner.planAccepts(config.allowSources, tailscaleIf, "iptables")
        val plannedV6 = RulePlanner.planAccepts(config.allowSources, tailscaleIf, "ip6tables")
        val needApply = force || !RulePlanner.rulesMatch(
            v4Rules = status.v4Rules,
            v6Rules = status.v6Rules,
            expectedV4Accepts = plannedV4.size,
            expectedV6Accepts = plannedV6.size,
            port = config.port,
        )

        if (needApply) {
            manager.applyRules(tailscaleIf)
                .onSuccess { logs ->
                    _state.value = _state.value.copy(
                        lastApplyLogs = logs,
                        lastApplyTime = System.currentTimeMillis(),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(lastError = e.message)
                }
        }

        // 3) 刷新状态
        updateStateFromConfig(config)
    }

    private suspend fun updateStateFromConfig(config: AdbConfig) {
        // 关态也要读一次状态：属性可能仍是旧值（关闭过程中 adbd 还没重启完），
        // 界面需要如实显示「正在关闭」，而不是显示成开着。
        val status = manager.readStatus()
        _state.value = _state.value.copy(
            running = config.enabled && status.isListening,
            hasRoot = status.hasRoot,
            lastError = status.rootError.takeIf { !status.hasRoot },
        )
    }

    private fun currentConfig(): AdbConfig =
        (application as com.wifiadb.ts.AdbApp).configRepository.config.value

    private fun stopSelfSafely() {
        scope.launch {
            // 先停服务，再写关闭态。
            // 顺序反了会有一段时间「配置已关闭但服务仍在跑」，
            // 巡检循环那一轮会把 adb tcp 重新打开——这正是「点开就自己关了」的一种成因。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            val config = currentConfig().copy(enabled = false)
            AdbManager(config).clearRules()
            (application as com.wifiadb.ts.AdbApp).configRepository.update { it.copy(enabled = false) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW, // 低重要性：不发声、不弹
            ).apply { description = getString(R.string.channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, com.wifiadb.ts.ui.MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "adb_service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.wifiadb.ts.action.STOP"
        const val ACTION_APPLY_NOW = "com.wifiadb.ts.action.APPLY_NOW"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AdbService::class.java))
        }

        fun applyNow(context: Context) {
            // 用 startForegroundService 而非 startService：服务可能已被关掉，
            // 后台态 startService 会抛 IllegalStateException（Android 8+）。
            context.startForegroundService(
                Intent(context, AdbService::class.java).setAction(ACTION_APPLY_NOW)
            )
        }

        fun stop(context: Context) {
            // 同样走 startForegroundService：服务可能已停，startService 会抛异常
            context.startForegroundService(
                Intent(context, AdbService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** 服务的运行状态，界面只读 */
data class ServiceState(
    val running: Boolean = false,
    val hasRoot: Boolean = false,
    val lastError: String? = null,
    val lastApplyLogs: List<String> = emptyList(),
    val lastApplyTime: Long = 0L,
)
