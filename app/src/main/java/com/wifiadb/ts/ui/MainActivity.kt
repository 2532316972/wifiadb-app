package com.wifiadb.ts.ui

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wifiadb.ts.AdbApp
import com.wifiadb.ts.core.AdbManager
import com.wifiadb.ts.core.AdbStatus
import com.wifiadb.ts.data.AdbConfig
import com.wifiadb.ts.data.AllowSource
import com.wifiadb.ts.data.ConfigRepository
import com.wifiadb.ts.service.AdbService
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity。
 *
 * 按功能拆三页，互不挤占：
 * - 主页（home）：主开关 + 连接地址（核心操作）
 * - 白名单页（whitelist）：谁可以连
 * - 设置页（settings）：端口 / 巡检 / 开机自启 / 旧模块 / 诊断
 *
 * 用 NavHost 切换，二级页 TopAppBar 带返回按钮。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 状态栏图标颜色：edge-to-edge 下状态栏透明、内容延伸到状态栏后面，
        // 系统默认状态栏图标是白色，浅色顶栏上看不清。
        // 应用背景（Surface）恒为浅色 → 强制深色状态栏图标。
        window.findViewById<android.view.View>(android.R.id.content)
            ?.let { view ->
                view.post {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = true
                }
            }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // viewModel() 必须在 setContent 内顶层调用，
                    // 不能作为 @Composable 默认参数——否则 Compose 编译期
                    // 生成默认值 stub，首帧 subcomposeInto 会 AIOOBE。
                    AdbRoot(viewModel())
                }
            }
        }
    }
}

/** 界面状态与操作的 ViewModel。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AdbApp
    private val repo = app.configRepository
    val config = repo.config

    private val _status = mutableStateOf(AdbStatus())
    val status = _status

    private val _busy = mutableStateOf(false)
    val busy = _busy

    private val _message = mutableStateOf<String?>(null)
    val message = _message

    private val _legacyModule = mutableStateOf(false)
    val legacyModule = _legacyModule

    init {
        refresh()
        detectLegacy()
    }

    fun refresh() {
        viewModelScope.launch {
            val cfg = config.value
            _status.value = AdbManager(cfg).readStatus()
        }
    }

    private fun detectLegacy() {
        viewModelScope.launch {
            _legacyModule.value = AdbManager(config.value).detectLegacyModule()
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val cfg = config.value
                val result = AdbManager(cfg).setEnabled(enabled)
                if (result.isSuccess) {
                    repo.update { it.copy(enabled = enabled) }
                    // 服务只是巡检载体；起不来不能连累建规则（runCatching 兜底）。
                    runCatching {
                        if (enabled) AdbService.start(app) else AdbService.stop(app)
                    }
                    if (enabled) {
                        val status = AdbManager(cfg).readStatus()
                        AdbManager(cfg).applyRules(status.tailscaleInterface)
                    } else {
                        AdbManager(cfg).clearRules()
                    }
                    _message.value = if (enabled) "已开启：${cfg.port} 端口" else "已关闭"
                } else {
                    _message.value = "操作失败：${result.exceptionOrNull()?.message}"
                }
            } finally {
                _busy.value = false
                refresh()
            }
        }
    }

    fun applyNow() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val cfg = config.value
                val status = AdbManager(cfg).readStatus()
                val result = AdbManager(cfg).applyRules(status.tailscaleInterface)
                _message.value = if (result.isSuccess) "规则已应用"
                else "应用失败：${result.exceptionOrNull()?.message}"
            } finally {
                _busy.value = false
                refresh()
            }
        }
    }

    fun setPort(text: String) {
        val port = text.trim().toIntOrNull() ?: return
        if (port in AdbConfig.PORT_MIN..AdbConfig.PORT_MAX) {
            repo.update { it.copy(port = port) }
        }
    }

    fun setInterval(seconds: Int) {
        repo.update { it.copy(checkIntervalSeconds = seconds.coerceIn(1, 10)) }
    }

    fun addSource(kind: AllowSource.Kind, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty() && kind != AllowSource.Kind.TAILSCALE) {
            _message.value =
                if (kind == AllowSource.Kind.INTERFACE) "网卡名不能为空" else "IP 不能为空"
            return
        }
        val source = AllowSource(kind, trimmed)
        if (config.value.allowSources.any { it.kind == kind && it.value == trimmed }) {
            _message.value = "已存在：${source.display()}"
            return
        }
        repo.update { it.copy(allowSources = it.allowSources + source) }
        _message.value = "已添加：${source.display()}"
    }

    fun removeSource(source: AllowSource) {
        repo.update { it.copy(allowSources = it.allowSources - source) }
        _message.value = "已移除：${source.display()}"
    }

    fun disableLegacyModule() {
        viewModelScope.launch {
            val result = AdbManager(config.value).disableLegacyModule()
            if (result.isSuccess) {
                repo.update { it.copy(legacyModuleDisabled = true) }
                _message.value = "旧模块已禁用（重启后生效，删除 disable 文件即可恢复）"
            } else {
                _message.value = "禁用失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun enableLegacyModule() {
        viewModelScope.launch {
            val result = AdbManager(config.value).enableLegacyModule()
            if (result.isSuccess) {
                repo.update { it.copy(legacyModuleDisabled = false) }
                _message.value = "旧模块已恢复"
            } else {
                _message.value = "恢复失败：${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
private fun AdbRoot(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val status by vm.status
    val busy by vm.busy
    val message by vm.message
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 手动页面切换（不引入 Navigation Compose，避免拉新依赖）
    var currentScreen by remember { mutableStateOf("home") }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            "whitelist" -> WhitelistScreen(
                config = config,
                vm = vm,
                onBack = { currentScreen = "home" },
            )
            "settings" -> SettingsScreen(
                config = config,
                status = status,
                busy = busy,
                vm = vm,
                onBack = { currentScreen = "home" },
            )
            else -> HomeScreen(
                config = config,
                status = status,
                busy = busy,
                vm = vm,
                onGoWhitelist = { currentScreen = "whitelist" },
                onGoSettings = { currentScreen = "settings" },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}

// ============================================================
// 主页
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    config: AdbConfig,
    status: AdbStatus,
    busy: Boolean,
    vm: MainViewModel,
    onGoWhitelist: () -> Unit,
    onGoSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(title = { Text("无线 ADB") })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MasterSwitchCard(config = config, status = status, busy = busy, vm = vm)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("进入设置", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onGoWhitelist, modifier = Modifier.weight(1f)) {
                            Text("白名单")
                        }
                        Button(onClick = onGoSettings, modifier = Modifier.weight(1f)) {
                            Text("设置")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistChip(
                            onClick = onGoWhitelist,
                            label = { Text("${config.allowSources.size} 条来源") },
                        )
                        AssistChip(
                            onClick = onGoSettings,
                            label = {
                                Text(
                                    "Tailscale ${status.tailscaleInterface ?: "未连接"} · " +
                                        "规则 ${status.v4Rules.size + status.v6Rules.size} 条",
                                )
                            },
                        )
                    }
                    Text(
                        text = if (status.hasRoot) "root 已授权" else "未获取 root 权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.hasRoot) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** 主开关卡：一屏只问「开还是关」，并把「怎么连」直接摆出来。 */
@Composable
private fun MasterSwitchCard(
    config: AdbConfig,
    status: AdbStatus,
    busy: Boolean,
    vm: MainViewModel,
) {
    val on = config.enabled && status.isListening
    val containerColor = when {
        !status.hasRoot -> MaterialTheme.colorScheme.errorContainer
        on -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        !status.hasRoot -> MaterialTheme.colorScheme.onErrorContainer
        on -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !status.hasRoot -> "没有 root 权限"
                            on -> "已开启"
                            config.enabled -> "正在开启…"
                            else -> "已关闭"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            !status.hasRoot -> "请在 Magisk 中给本应用授权"
                            on -> "端口 ${status.propPort}，仅白名单来源可连"
                            config.enabled -> "正在设置属性并重启 adbd"
                            else -> "打开后本机 5555 端口才会监听"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { vm.setEnabled(it) },
                        enabled = status.hasRoot,
                    )
                }
            }

            if (on) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = contentColor.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "连接地址",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val addresses = status.connectAddresses
                        addresses.forEach { iface ->
                            Text(
                                text = "adb connect ${iface.address.substringBefore('/')}:${status.propPort}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (addresses.isEmpty()) {
                            Text(
                                text = "没有局域网或 Tailscale 地址",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 白名单页
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhitelistScreen(
    config: AdbConfig,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    // 拦截系统返回（返回手势/返回键）：回首页而不是退出 App
    androidx.activity.compose.BackHandler(onBack = { onBack() })

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("白名单") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("来源", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "${config.allowSources.size} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加来源")
                        }
                    }

                    if (config.allowSources.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "名单为空，任何设备都能连上本机 5555 端口。" +
                                    "传统 5555 没有任何认证，请勿在公共网络下这样用。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            config.allowSources.forEach { source ->
                                AllowSourceChip(
                                    source = source,
                                    onRemove = { vm.removeSource(source) },
                                )
                            }
                        }
                        Text(
                            text = "点 chip 上的 × 移除。只放行你信任的那台设备，不要放整个网段。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { kind, value ->
                vm.addSource(kind, value)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AllowSourceChip(source: AllowSource, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = source.display(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "移除 ${source.display()}",
                modifier = Modifier
                    .size(16.dp)
                    .selectable(selected = false, onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ============================================================
// 设置页
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    config: AdbConfig,
    status: AdbStatus,
    busy: Boolean,
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    // 拦截系统返回（返回手势/返回键）：回首页而不是退出 App
    androidx.activity.compose.BackHandler(onBack = { onBack() })

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AdvancedCard(config = config, vm = vm)
            DiagnosticsCard(status = status, config = config, busy = busy, vm = vm)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ============================================================
// 诊断卡
// ============================================================

@Composable
private fun DiagnosticsCard(
    status: AdbStatus,
    config: AdbConfig,
    busy: Boolean,
    vm: MainViewModel,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("诊断", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Tailscale ${status.tailscaleInterface ?: "未连接"} · " +
                            "规则 ${status.v4Rules.size + status.v6Rules.size} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.refresh() }, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!status.hasRoot) {
                        Text(
                            text = "未获取 root 权限：${status.rootError ?: "请在 Magisk 中授权"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        StatusRow("端口属性", status.propPort.ifEmpty { "（空）" })
                        StatusRow("adbd", status.adbdState.ifEmpty { "未知" })
                        StatusRow(
                            label = "Tailscale 网卡",
                            value = status.tailscaleInterface ?: "未连接",
                            highlight = status.tailscaleInterface == null,
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("网络地址", style = MaterialTheme.typography.labelLarge)
                        val list = status.usableInterfaces.ifEmpty { status.interfaces }
                        list.forEach { iface ->
                            Text(
                                text = iface.display(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("IPv4 规则（${status.v4Rules.size}）", style = MaterialTheme.typography.labelLarge)
                        RulesList(status.v4Rules)

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("IPv6 规则（${status.v6Rules.size}）", style = MaterialTheme.typography.labelLarge)
                        RulesList(status.v6Rules)

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { vm.applyNow() },
                            enabled = !busy && config.enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("立即应用规则")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesList(rules: List<String>) {
    if (rules.isEmpty()) {
        Text(
            text = "（无）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rules.forEach { rule ->
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// 高级卡
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedCard(
    config: AdbConfig,
    vm: MainViewModel,
) {
    var portText by remember(config.port) { mutableStateOf(config.port.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("高级", style = MaterialTheme.typography.titleMedium)

            Spacer(modifier = Modifier.height(12.dp))

            // 端口
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.setPort(portText) }) { Text("保存") }
            }
            Text(
                text = "范围 ${AdbConfig.PORT_MIN}-${AdbConfig.PORT_MAX}，改完要重新开关一次才生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 巡检周期
            Text("巡检周期", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10).forEach { seconds ->
                    FilterChip(
                        selected = config.checkIntervalSeconds == seconds,
                        onClick = { vm.setInterval(seconds) },
                        label = { Text("${seconds}s") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = "巡检服务每 N 秒检查一次，发现 Tailscale 网卡或规则变动时自动重建规则。" +
                    "部分系统（ColorOS 等）会限制后台服务，若服务起不来可手动点诊断卡里的「立即应用」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

        }
    }
}

// ============================================================
// 添加来源弹窗
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (AllowSource.Kind, String) -> Unit,
) {
    var selected by remember { mutableStateOf(AllowSource.Kind.TAILSCALE) }
    var text by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("添加来源", style = MaterialTheme.typography.titleLarge)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AllowSource.Kind.values().forEach { kind ->
                        FilterChip(
                            selected = selected == kind,
                            onClick = {
                                selected = kind
                                text = ""
                            },
                            label = {
                                Text(
                                    when (kind) {
                                        AllowSource.Kind.TAILSCALE -> "Tailscale"
                                        AllowSource.Kind.INTERFACE -> "网卡"
                                        AllowSource.Kind.SOURCE_IP -> "IP"
                                    }
                                )
                            },
                        )
                    }
                }

                if (selected != AllowSource.Kind.TAILSCALE) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = {
                            Text(
                                if (selected == AllowSource.Kind.INTERFACE) {
                                    "网卡名，如 tun1"
                                } else {
                                    "IP，如 192.168.0.110"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (selected == AllowSource.Kind.SOURCE_IP) {
                                KeyboardType.Number
                            } else {
                                KeyboardType.Text
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "自动探测当前生效的 Tailscale 网卡（100.x 段），" +
                            "不用填网卡名。Tailscale 断开时这条自动失效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onConfirm(selected, text) }) { Text("添加") }
                }
            }
        }
    }
}
