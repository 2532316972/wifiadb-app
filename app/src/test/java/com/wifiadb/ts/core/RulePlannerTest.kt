package com.wifiadb.ts.core

import com.wifiadb.ts.data.AllowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测纯逻辑。规则签名比对是巡检「要不要重建」的唯一判据，
 * 这里的每一条都对应一类真实发生过的现场。
 */
class RulePlannerTest {

    /** 与 ConfigRepository 默认值同构的白名单：Tailscale + 家里电脑的 IP */
    private val sources = listOf(
        AllowSource(AllowSource.Kind.TAILSCALE, ""),
        AllowSource(AllowSource.Kind.SOURCE_IP, "192.168.0.110"),
    )

    /** 设备上 `iptables -S INPUT | grep --dport` 的真实回显形态（-m tcp、-s 带 /32） */
    private val tun1Echo = listOf(
        "-A INPUT -i tun1 -p tcp -m tcp --dport 5555 -j ACCEPT",
        "-A INPUT -s 192.168.0.110/32 -p tcp -m tcp --dport 5555 -j ACCEPT",
        "-A INPUT -p tcp -m tcp --dport 5555 -j DROP",
    )

    @Test
    fun `回显带 m tcp 与 s 斜杠32 仍判为已符合`() {
        val expected = RulePlanner.expectedSpecs(sources, "tun1", "iptables", 5555)
        assertTrue(RulePlanner.rulesMatch(tun1Echo, expected))
    }

    @Test
    fun `网卡改名条数不变时判不匹配`() {
        // 本次 bug 的现场：tun1→tun0，条数 3==3，旧判据误判「已符合」不重建
        val expected = RulePlanner.expectedSpecs(sources, "tun0", "iptables", 5555)
        assertFalse(RulePlanner.rulesMatch(tun1Echo, expected))
    }

    @Test
    fun `缺 DROP 或规则堆积都判不匹配`() {
        val expected = RulePlanner.expectedSpecs(sources, "tun1", "iptables", 5555)
        assertFalse(RulePlanner.rulesMatch(tun1Echo.dropLast(1), expected))
        assertFalse(RulePlanner.rulesMatch(tun1Echo + tun1Echo.first(), expected))
    }

    @Test
    fun `结构解析不出的规则一律判不匹配`() {
        val expected = RulePlanner.expectedSpecs(sources, null, "iptables", 5555)
        val weird = listOf(
            "-A INPUT -s 192.168.0.110/32 -p tcp -m tcp --dport 5555 -j ACCEPT",
            "-A INPUT -p tcp -m tcp --dport 5555 -j DROP",
            "-A INPUT -m limit --limit 3/min -j ACCEPT", // 没有 --dport 也不会出现，但签名必须认不出即拒绝
        )
        assertFalse(RulePlanner.rulesMatch(weird, expected))
    }

    @Test
    fun `Tailscale 未连接时 v6 只剩 DROP`() {
        val expected = RulePlanner.expectedSpecs(sources, null, "ip6tables", 5555)
        assertEquals(listOf("-p tcp --dport 5555 -j DROP"), expected)
    }

    @Test
    fun `Tailscale 在线时 v6 是网卡条加 DROP`() {
        val expected = RulePlanner.expectedSpecs(sources, "tun1", "ip6tables", 5555)
        assertEquals(
            listOf(
                "-i tun1 -p tcp --dport 5555 -j ACCEPT",
                "-p tcp --dport 5555 -j DROP",
            ),
            expected,
        )
    }

    @Test
    fun `SOURCE_IP 条目不进 v6 表`() {
        val ipOnly = listOf(AllowSource(AllowSource.Kind.SOURCE_IP, "192.168.0.110"))
        val expected = RulePlanner.expectedSpecs(ipOnly, "tun1", "ip6tables", 5555)
        assertEquals(listOf("-p tcp --dport 5555 -j DROP"), expected)
    }
}
