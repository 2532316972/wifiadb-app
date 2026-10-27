# WiFiADB

手机端一键开启 **ADB over TCP**（默认端口 5555），并用 iptables 把访问来源限定在你指定的白名单里——不用装 Magisk 模块，不用重启。

> 这是 [magisk-wifiadb](https://github.com/mrh929/magisk-wifiadb) 的 App 化替代：那套逻辑原本靠 Magisk 的开机脚本驱动，换成一个常驻 App + 每次调用 `su` 之后，开关变成即时的，也不再依赖 Magisk 本身（KernelSU / APatch 同样可用）。

## 为什么需要它

系统自带的「无线调试」在配对阶段要求电脑和手机在**同一局域网**内（mDNS 广播出不了路由器）。出门在外只有流量时根本搜不到设备。这个 App 直接 `setprop service.adb.tcp.port` 并重启 adbd，不挑网络、不用配对，配合 Tailscale 走流量即可连接。

| 场景 | 方式 | 说明 |
|---|---|---|
| 在家（同一 Wi‑Fi） | 系统「无线调试」 | mDNS + TLS 加密 + 随机端口，更安全 |
| 在外（只有流量） | 本 App + Tailscale | 开端口后 `adb connect <手机IP>:5555` |

## 功能

- **一键开关**：打开即置 `service.adb.tcp.port` 并重启 adbd，关闭即清空，状态实时显示
- **来源白名单**：只放行你指定的来源访问 ADB 端口，其余一律 DROP。三种类型：
  - Tailscale 网卡（按当前探测到的网卡名匹配，断开时自动失效）
  - 指定网卡名
  - 指定来源 IP（仅 IPv4 表，`-s` 对 `ip6tables` 非法）
- **双栈覆盖**：`iptables` 与 `ip6tables` 两张表都打（adbd 是 v6 双栈监听，只打一张等于没加固）
- **可连地址提示**：自动过滤掉 lo、内部虚拟网卡和别人的 VPN 隧道地址，只展示真能 `adb connect` 的那个（优先 Tailscale）
- **常驻巡检**：前台服务周期性校验「端口还开着 + 规则还符合白名单」，被系统清掉或 Tailscale 重连后自动补回

## 前提

- **已 root**（Magisk / KernelSU / APatch 任一），App 每次执行都单独 `su`
- 首次打开会弹 root 授权，请允许

## 用法

1. 装好打开，允许 root
2. 在白名单里加来源（推荐直接选 Tailscale）
3. 点开关打开，界面上会显示可直接复制的连接地址
4. 电脑上 `adb connect <地址>:5555`

## 编译

```bash
./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
```

- 单模块 `:app`，Kotlin + Jetpack Compose
- `minSdk 26` / `targetSdk 34` / `compileSdk 34`，Gradle 8.7
- 依赖仓库含阿里云镜像与 JitPack，国内可直连

## 与 Magisk 模块版的区别

模块版靠开机脚本拉起 `service.sh`，开关要改文件再等脚本跑；App 版把同样的巡检循环搬到前台服务里，开关即时生效，且不挑 root 方案。iptables 规则、白名单语义、Tailscale 网卡探测逻辑两者一致。

## License

MIT
