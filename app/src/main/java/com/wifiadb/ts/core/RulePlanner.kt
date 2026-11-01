package com.wifiadb.ts.core

import com.wifiadb.ts.data.AllowSource

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
     * 单条 ACCEPT 规则的完整规格。[AdbManager.applyRules] 插入与 [expectedSpecs]
     * 比对共用这一处构造——巡检判断「符不符合」用的和实际插进去的必须是同一份规格，
     * 否则会出现「巡检认为已符合、实际插的又不一样」的漂移。
     */
    fun acceptSpec(source: AllowSource, tailscaleInterface: String?, port: Int): String {
        val iface = when (source.kind) {
            AllowSource.Kind.TAILSCALE -> tailscaleInterface
            AllowSource.Kind.INTERFACE -> source.value
            AllowSource.Kind.SOURCE_IP -> null
        }
        val match = iface?.let { "-i $it" } ?: "-s ${source.value}"
        return "$match -p tcp --dport $port -j ACCEPT"
    }

    /** DROP 兜底规格，恒为该表最后一条。 */
    fun dropSpec(port: Int): String = "-p tcp --dport $port -j DROP"

    /**
     * 一张表里「应该存在的全部规则规格」：ACCEPT 按配置顺序 + 末尾 DROP。
     *
     * 巡检拿它和设备上的实际规则逐条比对。比对的是规则语义而非条数——
     * 网卡改名（实测同机出现过 tun0 与 tun1）这类「条数没变、内容变了」的情况，
     * 数条数的判据会误判为「已符合」而不重建，旧 `-i` 规则就成了死规则。
     */
    fun expectedSpecs(
        allowSources: List<AllowSource>,
        tailscaleInterface: String?,
        table: String,
        port: Int,
    ): List<String> =
        planAccepts(allowSources, tailscaleInterface, table)
            .map { acceptSpec(it, tailscaleInterface, port) } + dropSpec(port)

    /**
     * 判断现有规则是否已符合预期，用于避免无谓的重建。
     *
     * 旧判据只数「含 --dport 的条数 == 期望数」，网卡改名时 3 条 == 3 条误判通过。
     * 现在把每条规则解析成「匹配方式>动作」的签名再做多重集合比对：
     * `-m tcp` 回显噪音、`-s x.x.x.x/32` 的 /32 后缀都归一化掉，
     * 签名解析不出（结构怪异的规则）一律判不匹配——认不准就重建，宁滥勿缺。
     *
     * @param actualRules `iptables -S INPUT | grep --dport` 的原文（`-A INPUT ...` 行）
     * @param expectedSpecs [expectedSpecs] 的输出
     */
    fun rulesMatch(
        actualRules: List<String>,
        expectedSpecs: List<String>,
    ): Boolean {
        fun sigs(specs: List<String>): List<String>? {
            val out = ArrayList<String>(specs.size)
            for (spec in specs) {
                val sig = signature(spec) ?: return null
                out += sig
            }
            return out
        }

        // 只认 -A INPUT 行：grep 已按 --dport 过滤，本端口的规则必然以此开头
        val actual = actualRules
            .filter { it.startsWith("-A INPUT ") }
            .map { it.removePrefix("-A INPUT ") }
        val actualSigs = sigs(actual) ?: return false
        val expectedSigs = sigs(expectedSpecs) ?: return false

        // 多重集合比对（sorted 而非 toSet）：重复堆积的规则也算差异，不被去重掩盖
        return actualSigs.sorted() == expectedSigs.sorted()
    }

    /**
     * 规则签名：`-i tun1 -p tcp --dport 5555 -j ACCEPT` → `i:tun1>ACCEPT`。
     *
     * 只保留匹配方式（-i / -s / 都没有 = any）与 -j 动作；-p、-m、--dport
     * 在本端口规则里全部相同，不参与区分。`-s` 的 /32 是 iptables 回显自动补的
     * （用户输入裸 IP、插进去再 -S 出来就带 /32），剥掉以对齐期望规格。
     * 没有 -j 或结构残缺返回 null（调用方判不匹配）。
     */
    private fun signature(spec: String): String? {
        val tokens = spec.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null
        var match = "any"
        var i = 0
        while (i < tokens.size - 1) {
            when (tokens[i]) {
                "-i" -> match = "i:${tokens[i + 1]}"
                "-s" -> match = "s:${tokens[i + 1].removeSuffix("/32")}"
            }
            i++
        }
        val j = tokens.indexOf("-j")
        if (j < 0 || j + 1 >= tokens.size) return null
        return "$match>${tokens[j + 1]}"
    }
}
