package com.wifiadb.ts.core

import com.wifiadb.ts.data.AdbConfig
import com.wifiadb.ts.data.AllowSource
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 纯逻辑：算出「这一轮应该插哪些 ACCEPT」。
 *
 * 单独抽出来是为了能写 JVM 单测——iptables 本身只能在 root 设备上验证，
 * 但「哪些条目该生效」是可以纯函数测的。
 */
object RulePlanner {

    /**
     * @param allowSources 用户配置的白名单
     * @param tailscaleInterface 当前探测到的 Tailscale 网卡名，null = Tailscale 未连接
     * @param table `iptables` 或 `ip6tables`
     * @return 该表应插入的 ACCEPT 条目（顺序即配置顺序），已过滤掉不适用的
     */
    fun planAccepts(
        allowSources: List<AllowSource>,
        tailscaleInterface: String?,
        table: String,
    ): List<AllowSource> {
        val isV6 = table == "ip6tables"
        return allowSources.filter { source ->
            when (source.kind) {
                // Tailscale：用探测到的网卡名，不比对用户填的名字（这类型根本没名字）。
                // 探测为 null（Tailscale 没连）时这条失效——插一条指向
                // 不存在网卡的规则是无害但无效的残留，不如不插。
                AllowSource.Kind.TAILSCALE -> tailscaleInterface != null

                // 指定网卡名：只有「用户配的网卡名 == 此刻探测到的网卡名」才生效
                AllowSource.Kind.INTERFACE -> source.value == tailscaleInterface

                // -s 只对 v4 表合法：把 IPv4 地址喂给 ip6tables 会直接报
                // "host/network '192.168.0.110' not found" 并失败。
                AllowSource.Kind.SOURCE_IP -> !isV6
            }
        }
    }

    /**
     * 判断现有规则是否已符合预期，用于避免无谓的重建。
     *
     * 判据 = 两张表里该端口的规则数都等于「预期 ACCEPT 数 + 1 个 DROP」。
     * 这个判据是粗粒度的（不比对每条规则的具体内容），但足以覆盖三种实际会发生的情况：
     * 规则堆积、被 netd 清掉、Tailscale 断开后多出失效的 -i 规则。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rulesMatch(
        v4Rules: List<String>,
        v6Rules: List<String>,
        expectedV4Accepts: Int,
        expectedV6Accepts: Int,
        port: Int,
    ): Boolean {
        fun countPort(rules: List<String>) =
            rules.count { it.contains("--dport $port") }

        val v4Count = countPort(v4Rules)
        val v6Count = countPort(v6Rules)
        val v4Expected = expectedV4Accepts + 1 // +1 = DROP
        val v6Expected = expectedV6Accepts + 1

        // 一条规则都没有 = 完全没加固，不符合
        if (v4Count == 0 && v6Count == 0) return false

        // 任何一张表条数不对就要重建。
        // 注意 v6 表可能因为「白名单里只有 IP 条目」而预期 0 条 ACCEPT + 1 条 DROP = 1 条，
        // 此时 v6 那条 DROP 仍然要存在（否则 v6 进来的连接无人拦截）。
        return v4Count == v4Expected && v6Count == v6Expected
    }
}
