package com.wifiadb.ts.core

import com.wifiadb.ts.data.AdbConfig
import com.wifiadb.ts.data.AllowSource
import com.wifiadb.ts.data.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 某一时刻的 adb tcp 与防火墙规则快照，界面直接渲染它。
 */
data class AdbStatus(
    val hasRoot: Boolean = false,
    val rootError: String? = null,
    val enabled: Boolean = false,
    val port: Int = AdbConfig.DEFAULT_PORT,
    /** `service.adb.tcp.port` 属性当前值，空串表示未开启 */
    val propPort: String = "",
    /** adbd 服务状态，通常为 running */
    val adbdState: String = "",
    /** 设备上所有网络地址，形如 `wlan0/192.168.0.100/24` */
    val interfaces: List<NetInterface> = emptyList(),
    /** 当前生效的 Tailscale 网卡名，探测不到为 null */
    val tailscaleInterface: String? = null,
    /** v4/v6 两张表里与该端口相关的规则原文 */
    val v4Rules: List<String> = emptyList(),
    val v6Rules: List<String> = emptyList(),
    val lastApplyLog: List<String> = emptyList(),
) {
    /** adb tcp 是否真的在监听：属性值非空且等于配置端口 */
    val isListening: Boolean
        get() = propPort.isNotEmpty() && propPort.toIntOrNull() == port

    /** 规则是否已按白名单正确落地（两张表各 白名单数+1 条 DROP） */
    val rulesMatchConfig: Boolean
        get() = v4Rules.isNotEmpty() || v6Rules.isNotEmpty()

    /** 值得展示给用户的网卡（滤掉 lo / 内部虚拟网卡 / 所有 IPv6） */
    val usableInterfaces: List<NetInterface>
        get() = interfaces.filter { it.isUsable }

    /**
     * 「拿这个地址去 adb connect」的候选，只保留真正有意义的：
     * 局域网（192.168 / 10. / 172.16-31）与 Tailscale（100.）。
     *
     * 为什么单独一个列表：手机上常驻着别的 VPN（实测这台有个 tun0 拿 172.31.201.192/18），
     * 那是别的应用的隧道，把它的地址当成本机可连地址展示出去纯属误导——
     * 那个地址只有装同一个 VPN 的设备才连得到。
     */
    val connectAddresses: List<NetInterface>
        get() = usableInterfaces.filter { iface ->
            val ip = iface.address.substringBefore('/')
            ip.startsWith(AdbConfig.TAILSCALE_PREFIX) ||
                ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                (ip.startsWith("172.") && ip.substringAfter("172.").substringBefore('.').toIntOrNull()
                    ?.let { it in 16..31 } == true)
        }

    /** 最该展示的那个地址：优先 Tailscale（在外面用），其次局域网 */
    val primaryAddress: NetInterface?
        get() = usableInterfaces.firstOrNull { it.address.startsWith(AdbConfig.TAILSCALE_PREFIX) }
            ?: usableInterfaces.firstOrNull()
}

/** 设备上的一个网络接口 */
data class NetInterface(
    val name: String,
    val address: String,
) {
    fun display(): String = "$name  $address"

    /**
     * 是否值得展示给用户。
     *
     * 一台 Android 上 `ip -o addr` 会吐出十几条，绝大多数毫无意义：
     * lo / dummy0 / ifb0-2 / rmnet_data0 / r_rmnet_data0 / 各网卡的 fe80:: 等。
     * 全列出来只会让人找不到自己要用的那个地址，所以这里过滤掉：
     * - 回环与内部虚拟网卡（lo、dummy、ifb、tunl、gre、sit、ip6tnl、ovnet、veth）
     * - 移动数据网卡（rmnet/r_rmnet）：5555 只在 WiFi 与 Tailscale 上用
     * - **所有 IPv6 地址**：fe80:: 是链路本地；240e: 这类运营商公网 v6 又长又随网络变化，
     *   列出来只会把卡片撑高、给人错误印象（手机一换网这个地址就没了）。
     *   真要连 v6 直接敲 `adb connect [地址]:端口` 即可，不需要界面展示。
     *   Tailscale 的 100.x 是 IPv4，不受影响。
     */
    val isUsable: Boolean
        get() {
            val n = name
            if (n == "lo" || n.startsWith("dummy") || n.startsWith("ifb") ||
                n.startsWith("tunl") || n.startsWith("gre") || n.startsWith("sit") ||
                n.startsWith("ip6") || n.startsWith("ovnet") || n.startsWith("veth")
            ) return false
            if (n.startsWith("rmnet") || n.startsWith("r_rmnet")) return false
            // 只要 IPv4：带冒号的一律排除（IPv6 全在这种形态）
            if (address.contains(':')) return false
            return true
        }

    companion object {
        /** `2: wlan0    inet 192.168.0.100/24 brd ...` → NetInterface(wlan0, 192.168.0.100/24) */
        fun parse(line: String): NetInterface? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return null
            val name = parts[1]
            val addrIndex = parts.indexOfFirst { it.startsWith("inet") }
            if (addrIndex < 0 || addrIndex + 1 >= parts.size) return null
            return NetInterface(name, parts[addrIndex + 1])
        }
    }
}

/**
 * adb tcp 开关与防火墙规则的核心逻辑。
 *
 * 与 Magisk 模块版的关系：**完全等价的 root 操作**，只是驱动方从开机脚本换成了 app。
 *
 * 三件事：
 * 1. [setEnabled] 开关 adb tcp：`setprop service.adb.tcp.port <port>` + 重启 adbd
 * 2. [applyRules] 按白名单重建 iptables 规则
 * 3. [readStatus] 读当前状态给界面显示
 */
class AdbManager(private val config: AdbConfig) {

    /**
     * 开关 adb tcp 端口。
     *
     * 关闭时把属性清空并重启 adbd，端口就不再监听；
     * 开启时先设属性再重启 adbd。两条路都要重启 adbd 才生效。
     */
    suspend fun setEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val port = config.port
            if (enabled) {
                RootShell.execute("setprop service.adb.tcp.port $port").check()
                RootShell.execute("stop adbd").check()
                RootShell.execute("start adbd").check()
            } else {
                RootShell.execute("setprop service.adb.tcp.port \"\"").check()
                RootShell.execute("stop adbd").check()
                RootShell.execute("start adbd").check()
            }
        }
    }

    /**
     * 按白名单重建规则。
     *
     * 做法（在 OnePlus PJX110 / Android 16 上实测可用，比逐条 `-C` 匹配更稳）：
     * 1. `-S INPUT | grep -F -- "--dport <port>"` 列出所有相关规则
     * 2. 去掉 `-A INPUT ` 前缀得到**完整规格**，逐条 `-D` 删除
     *    —— 这样不需要猜 `-C` 该写哪些字段，天然免疫「规格少写了 -i 就删不掉」
     * 3. 按顺序 `-I INPUT <位置>` 插入各 ACCEPT
     * 4. 最后把 DROP 插在最后一个 ACCEPT 之后
     *
     * 先删 ACCEPT 再插、最后才动 DROP：删 ACCEPT 期间白名单连接会落到 DROP 上被拒
     * （短暂拒绝自己的连接）；反过来先删 DROP 会出现「任意网卡都能进」的窗口，安全语义更糟。
     *
     * @param tailscaleInterface 当前探测到的 Tailscale 网卡名，null 表示 Tailscale 没连上
     * @return 每条命令的输出，供日志展示
     */
    suspend fun applyRules(
        tailscaleInterface: String?,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val port = config.port
            val logs = mutableListOf<String>()

            for (table in TABLES) {
                // 1) 删干净：列出本端口全部规则，按完整规格逐条删除
                val listResult = RootShell.execute("$table -S INPUT | grep -F -- \"--dport $port\"")
                val lines = listResult.stdout.lines().filter { it.startsWith("-A INPUT ") }
                for (line in lines) {
                    val spec = line.removePrefix("-A INPUT ")
                    val del = RootShell.execute("$table -D INPUT $spec")
                    if (!del.isSuccess && del.stderr.isNotBlank()) {
                        logs += "[$table] 删除失败: $spec → ${del.stderr.trim()}"
                    }
                }

                // 2) 依次插入 ACCEPT。
                // 条目筛选统一走 RulePlanner.planAccepts：它是纯函数、可单测，
                // 且巡检服务判断「规则是否符合预期」用的也是同一份逻辑——
                // 否则会出现「巡检认为已符合、实际插的又不一样」的漂移。
                val effective = RulePlanner.planAccepts(
                    allowSources = config.allowSources,
                    tailscaleInterface = tailscaleInterface,
                    table = table,
                )
                var position = 1
                for (source in effective) {
                    // TAILSCALE 与 INTERFACE 都按 -i 匹配，只是网卡名来源不同
                    val iface = when (source.kind) {
                        AllowSource.Kind.TAILSCALE -> tailscaleInterface
                        AllowSource.Kind.INTERFACE -> source.value
                        AllowSource.Kind.SOURCE_IP -> null
                    }
                    val spec = iface?.let { "-i $it" } ?: "-s ${source.value}"

                    val insert = RootShell.execute(
                        "$table -I INPUT $position $spec -p tcp --dport $port -j ACCEPT"
                    )
                    if (insert.isSuccess) {
                        logs += "[$table] 放行 $spec"
                        position++
                    } else {
                        logs += "[$table] 放行失败 $spec → ${insert.stderr.trim()}"
                    }
                }

                // 3) DROP 兜底放在最后
                val drop = RootShell.execute(
                    "$table -I INPUT $position -p tcp --dport $port -j DROP"
                )
                if (drop.isSuccess) {
                    logs += "[$table] 其余来源丢弃"
                } else {
                    logs += "[$table] DROP 插入失败 → ${drop.stderr.trim()}"
                }
            }
            logs
        }
    }

    /** 关闭白名单：删掉该端口的所有规则，恢复系统默认 */
    suspend fun clearRules(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val port = config.port
            for (table in TABLES) {
                val listResult = RootShell.execute("$table -S INPUT | grep -F -- \"--dport $port\"")
                for (line in listResult.stdout.lines()) {
                    if (!line.startsWith("-A INPUT ")) continue
                    val spec = line.removePrefix("-A INPUT ")
                    RootShell.execute("$table -D INPUT $spec")
                }
            }
        }
    }

    /** 读取当前状态。每次巡检与界面刷新都调它。 */
    suspend fun readStatus(): AdbStatus = withContext(Dispatchers.IO) {
        val rootProbe = RootShell.execute("id -u")
        val hasRoot = rootProbe.isSuccess && rootProbe.stdout.trim() == "0"
        if (!hasRoot) {
            return@withContext AdbStatus(
                hasRoot = false,
                rootError = rootProbe.stderr.trim().ifEmpty { "未获得 root 权限" },
            )
        }

        val propPort = RootShell.execute("getprop service.adb.tcp.port").stdout.trim()
        val adbdState = RootShell.execute("getprop init.svc.adbd").stdout.trim()

        val interfaces = RootShell.execute("ip -o addr")
            .stdout.lines()
            .mapNotNull { NetInterface.parse(it) }

        // 动态探测 Tailscale 网卡：找地址在 100. 段的接口。
        // 不能写死网卡名——实测同一台设备上出现过 tun0 与 tun1 两种情况，
        // 且 Tailscale 断开时该接口直接消失。
        val tailscaleInterface = interfaces
            .firstOrNull { it.address.startsWith(AdbConfig.TAILSCALE_PREFIX) }
            ?.name

        val port = config.port
        val v4Rules = RootShell.execute("iptables -S INPUT | grep -F -- \"--dport $port\"")
            .stdout.lines().filter { it.isNotBlank() }
        val v6Rules = RootShell.execute("ip6tables -S INPUT | grep -F -- \"--dport $port\"")
            .stdout.lines().filter { it.isNotBlank() }

        AdbStatus(
            hasRoot = true,
            enabled = config.enabled,
            port = port,
            propPort = propPort,
            adbdState = adbdState,
            interfaces = interfaces,
            tailscaleInterface = tailscaleInterface,
            v4Rules = v4Rules,
            v6Rules = v6Rules,
        )
    }

    /** 检测旧 Magisk 模块是否装着（目录存在即算） */
    suspend fun detectLegacyModule(): Boolean = withContext(Dispatchers.IO) {
        if (cachedLegacyModule != null) return@withContext cachedLegacyModule!!
        val result = RootShell.execute("test -d $LEGACY_MODULE_DIR && echo yes || echo no")
        val detected = result.stdout.trim() == "yes"
        cachedLegacyModule = detected
        detected
    }

    /**
     * 禁用旧模块：创建 `disable` 文件。
     *
     * Magisk 的约定是「模块目录下存在名为 disable 的文件 = 该模块不加载」，
     * 重启后生效。**不需要卸载**，随时删掉该文件即可恢复。
     */
    suspend fun disableLegacyModule(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            RootShell.execute("touch $LEGACY_MODULE_DIR/disable").check()
        }
    }

    /** 恢复旧模块：删掉 disable 文件 */
    suspend fun enableLegacyModule(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            RootShell.execute("rm -f $LEGACY_MODULE_DIR/disable").check()
        }
    }

    private fun RootShell.Result.check() {
        if (!isSuccess) {
            throw IllegalStateException(
                stderr.trim().ifEmpty { "命令失败，退出码 $exitCode" }
            )
        }
    }

    private companion object {
        val TABLES = listOf("iptables", "ip6tables")
        const val LEGACY_MODULE_DIR = "/data/adb/modules/magisk-wifiadb-ts"

        @Volatile
        var cachedLegacyModule: Boolean? = null
    }
}
