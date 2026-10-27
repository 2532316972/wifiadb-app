package com.wifiadb.ts.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 执行 root shell 命令。
 *
 * 一次 [execute] 起一个 su 进程，跑完即退出。这是刻意的：
 * - 模块版靠 Magisk 开机拉起的常驻脚本，app 版没有这个便利，改用「前台服务 + 每次调用都 su」。
 * - 每条命令都独立 su 的代价约几十毫秒，1 秒一轮的巡检完全可以接受。
 * - 优点是失败隔离：某条命令挂住不会拖死整个巡检循环。
 */
object RootShell {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * su 的候选路径，按优先级排列。
     *
     * **不能直接 `ProcessBuilder("su", ...)`**：Android 应用进程的 PATH 由 zygote 设定，
     * 与 adb shell 的 PATH 不同，实测在 ColorOS/Android 16 上会抛
     * `Cannot run program "su": error=2, No such file or directory`——尽管 `/system/bin/su`
     * 确实存在（`su 10387 -c /system/bin/su -c id` 实测成功）。
     *
     * **也不能用 `File(path).exists()` 预筛**：应用域的 SELinux 策略让 Java 层 stat
     * `/system/bin/su` 这种 root 拥有的符号链接返回不存在，`exists()` 恒为 false。
     * 所以这里直接**逐个试 exec**，谁能跑通就用谁。
     *
     * 顺序说明：Magisk 会把 su 链到 `/system/bin/su` 与 `/debug_ramdisk/su`；
     * KernelSU/APatch 常见 `/data/adb/ksu/bin/su` 等。全都试一遍最稳。
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/debug_ramdisk/su",
        "/system/xbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/sbin/su",
        "su", // 最后兜底：靠 PATH，某些 ROM（如类原生）这样能成
    )

    /** 缓存已验证可用的 su 路径，避免每轮巡检都试 7 个候选。 */
    @Volatile
    private var suPath: String? = null

    /**
     * 执行单条命令并等待结果。
     * @param command 完整命令，可含管道、引号、重定向
     */
    suspend fun execute(command: String): Result = withContext(Dispatchers.IO) {
        // 已有可用路径就直接用；失败时清空重试一轮全部候选
        suPath?.let { cached ->
            val r = runSu(cached, command)
            if (r.exitCode != SU_NOT_FOUND) return@withContext r
            suPath = null
        }

        var lastError = "未找到可用的 su（已尝试：${SU_CANDIDATES.joinToString()}）"
        for (candidate in SU_CANDIDATES) {
            val r = runSu(candidate, command)
            if (r.exitCode == SU_NOT_FOUND) {
                lastError = r.stderr.ifEmpty { lastError }
                continue
            }
            suPath = candidate
            return@withContext r
        }
        Result(-1, "", lastError)
    }

    /** 用指定路径起一个 su 进程。沿用 [SU_NOT_FOUND] 表示「这个路径根本 exec 不了」，可换下一个。 */
    private suspend fun runSu(su: String, command: String): Result = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(false)
                .start()

            // 用单独的协程读 stdout/stderr，避免缓冲区写满导致子进程阻塞
            val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }

            val exitCode = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { process.waitFor() }
            if (exitCode == null) {
                process.destroyForcibly()
                return@withContext Result(-1, "", "命令超时(${COMMAND_TIMEOUT_MS}ms): $command")
            }

            Result(exitCode, stdout.await(), stderr.await())
        } catch (e: java.io.IOException) {
            // 路径不存在 / 没有执行权限 —— 换下一个候选
            Result(SU_NOT_FOUND, "", "${e.message ?: e.javaClass.simpleName} ($su)")
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 快速探测是否已获得 root 权限。
     * 首次调用会弹 Magisk 授权框。
     */
    suspend fun isRootAvailable(): Boolean = execute("id -u").let {
        it.isSuccess && it.stdout.trim() == "0"
    }

    /** 内部哨兵：这个 su 路径 exec 不了，应尝试下一个候选。不对外暴露。 */
    private const val SU_NOT_FOUND = -2

    private const val COMMAND_TIMEOUT_MS = 15_000L
}
