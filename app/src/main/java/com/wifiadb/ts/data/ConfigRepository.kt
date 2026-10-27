package com.wifiadb.ts.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 白名单来源条目。
 *
 * 三种来源，对应 iptables 的两种匹配方式：
 * - [Kind.TAILSCALE] → `-i <探测到的 Tailscale 网卡>`，**不写死网卡名**。
 *   实测同一台设备上出现过 tun0 与 tun1 两种名字，写死就永远匹配不上；
 *   Tailscale 断开时该网卡直接消失，此时这条自动失效（不插规则）。
 * - [Kind.INTERFACE] → `-i <网卡名>`，用于明确指定某个网卡（高级用法）
 * - [Kind.SOURCE_IP] → `-s <IP>`，用于固定的局域网地址，例如家里电脑的 192.168.0.110
 */
data class AllowSource(
    val kind: Kind,
    val value: String,
) {
    enum class Kind { TAILSCALE, INTERFACE, SOURCE_IP }

    /** 界面显示用的一行文本，例如 `Tailscale（自动）` / `网卡 wlan0` / `IP 192.168.0.110` */
    fun display(): String = when (kind) {
        Kind.TAILSCALE -> "Tailscale（自动）"
        Kind.INTERFACE -> "网卡 $value"
        Kind.SOURCE_IP -> "IP $value"
    }

    companion object {
        /** 序列化成单行，例如 `t:`、`i:wlan0`、`s:192.168.0.110` */
        fun encode(source: AllowSource): String = when (source.kind) {
            Kind.TAILSCALE -> "t:"
            Kind.INTERFACE -> "i:${source.value}"
            Kind.SOURCE_IP -> "s:${source.value}"
        }

        /** 解析单行；格式不认识返回 null（调用方负责忽略坏行） */
        fun decode(line: String): AllowSource? {
            val trimmed = line.trim()
            if (trimmed.length < 2) return null
            val prefix = trimmed.substring(0, 2)
            val value = trimmed.substring(2)
            return when (prefix) {
                "t:" -> AllowSource(Kind.TAILSCALE, "")
                "i:" -> if (value.isEmpty()) null else AllowSource(Kind.INTERFACE, value)
                "s:" -> if (value.isEmpty()) null else AllowSource(Kind.SOURCE_IP, value)
                else -> null
            }
        }
    }
}

/**
 * 全部可配置项。
 *
 * 与模块版的 config 文件一一对应，但改成 SharedPreferences：
 * 模块改配置要手动编辑文件、还得等 maintain 下一轮读，app 这边改完立即生效。
 */
data class AdbConfig(
    val enabled: Boolean = false,
    val port: Int = 5555,
    val restrictEnabled: Boolean = true,
    /**
     * 白名单；空 = 任意来源都可连（危险）。
     *
     * 默认同时放两样：
     * - Tailscale（自动）：在外面走虚拟局域网，网卡名靠探测，不写死
     * - 192.168.0.110：家里这台电脑的局域网 IP
     *
     * **这个默认值必须留住 IP 那条**：只有 Tailscale 一条、而 Tailscale 恰好没连时，
     * 生效列表为空 → 只剩一条 DROP → 连局域网也进不来，等于把自己锁在外面。
     */
    val allowSources: List<AllowSource> = listOf(
        AllowSource(AllowSource.Kind.TAILSCALE, ""),
        AllowSource(AllowSource.Kind.SOURCE_IP, "192.168.0.110"),
    ),
    val checkIntervalSeconds: Int = 1,
    /** 旧 Magisk 模块是否装在这台设备上 */
    val legacyModuleDetected: Boolean = false,
    val legacyModuleDisabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 5555
        const val DEFAULT_INTERVAL = 1
        const val PORT_MIN = 1024
        const val PORT_MAX = 65535

        /** Tailscale 虚拟网卡的地址段，用来动态探测当前生效的网卡名 */
        const val TAILSCALE_PREFIX = "100."
    }
}

/**
 * 配置的读写与变更通知。
 *
 * 用 SharedPreferences 而非 DataStore：数据量极小（几个键），且需要同步读写——
 * 巡检循环与界面可能在不同时机同时访问，SharedPreferences 的 commit 是原子的。
 */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wifiadb_config", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<AdbConfig> = _config.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _config.value = readConfig()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun update(transform: (AdbConfig) -> AdbConfig) {
        val next = transform(_config.value)
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putInt(KEY_PORT, next.port)
            .putBoolean(KEY_RESTRICT, next.restrictEnabled)
            .putStringSet(KEY_ALLOW_SOURCES, next.allowSources.map(AllowSource::encode).toSet())
            .putInt(KEY_INTERVAL, next.checkIntervalSeconds)
            .putBoolean(KEY_LEGACY_DISABLED, next.legacyModuleDisabled)
            .commit()
    }

    private fun readConfig(): AdbConfig {
        val defaults = AdbConfig()
        val sources = prefs.getStringSet(KEY_ALLOW_SOURCES, null)
            ?.mapNotNull(AllowSource::decode)
            ?: defaults.allowSources

        return AdbConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            port = prefs.getInt(KEY_PORT, defaults.port).coerceIn(
                AdbConfig.PORT_MIN,
                AdbConfig.PORT_MAX
            ),
            restrictEnabled = prefs.getBoolean(KEY_RESTRICT, defaults.restrictEnabled),
            allowSources = sources.ifEmpty { defaults.allowSources },
            checkIntervalSeconds = prefs.getInt(KEY_INTERVAL, defaults.checkIntervalSeconds)
                .coerceIn(1, 10),
            legacyModuleDetected = defaults.legacyModuleDetected,
            legacyModuleDisabled = prefs.getBoolean(KEY_LEGACY_DISABLED, defaults.legacyModuleDisabled),
        )
    }

    companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PORT = "port"
        const val KEY_RESTRICT = "restrict_enabled"
        const val KEY_ALLOW_SOURCES = "allow_sources"
        const val KEY_INTERVAL = "check_interval"
        const val KEY_LEGACY_DISABLED = "legacy_module_disabled"
    }
}
