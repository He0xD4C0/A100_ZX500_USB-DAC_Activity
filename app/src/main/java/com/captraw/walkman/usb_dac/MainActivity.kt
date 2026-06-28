package com.captraw.walkman.usb_dac

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.captraw.walkman.usb_dac.ui.theme.USBDACTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

// ─── Socket 客户端（与 dacd 守护进程通信） ───────────────────────────────────

/**
 * 通过 Unix Domain Socket 与 dacd 原生守护进程通信。
 *
 * 协议（纯文本行，换行分隔）：
 *   → ENABLE [keep_adb=0|1] [low_power=0|1]
 *   ← OK RUNNING rate=44100 ch=2 fmt=S16_LE bridge=alive uptime=60
 *   → DISABLE
 *   ← OK OFF bridge=dead uptime=0
 *   → STATUS
 *   ← OK RUNNING rate=44100 ch=2 fmt=S16_LE bridge=alive uptime=60
 *   ← ERROR <message>
 */
class DacdClient {

    companion object {
        /** dacd 创建的 socket 路径（与 dacd.c 中 DACD_SOCKET_PATH 一致） */
        private const val SOCKET_PATH = "/data/local/tmp/dacd.sock"
        private const val CONNECT_TIMEOUT_MS = 3000
    }

    data class Status(
        val state: String = "OFF",
        val bridge: String = "dead",
        val rate: Int = 0,
        val channels: Int = 0,
        val format: String = "",
        val uptime: Long = 0,
        val isError: Boolean = false,
        val errorMessage: String = ""
    )

    /** 发送命令并返回解析后的状态。socket 用完即关闭（短连接）。 */
    fun command(cmd: String): Status = try {
        val socket = LocalSocket()
        socket.connect(
            LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM)
        )
        socket.soTimeout = CONNECT_TIMEOUT_MS

        val writer = OutputStreamWriter(socket.outputStream)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))

        writer.write("$cmd\n")
        writer.flush()

        val line = reader.readLine() ?: "ERROR no response"
        reader.close(); writer.close(); socket.close()

        parse(line)
    } catch (e: Exception) {
        Status(isError = true, errorMessage = "无法连接守护进程: ${e.message}")
    }

    private fun parse(line: String): Status {
        if (line.startsWith("ERROR ")) {
            return Status(isError = true, errorMessage = line.removePrefix("ERROR "))
        }
        if (!line.startsWith("OK ")) {
            return Status(isError = true, errorMessage = "协议错误: $line")
        }

        val parts = line.removePrefix("OK ").split(" ")
        var state  = "OFF"
        var bridge = "dead"
        var rate   = 0
        var ch     = 0
        var fmt    = ""
        var uptime = 0L

        for (p in parts) {
            when {
                p.startsWith("bridge=")  -> bridge = p.removePrefix("bridge=")
                p.startsWith("rate=")    -> rate   = p.removePrefix("rate=").toIntOrNull() ?: 0
                p.startsWith("ch=")      -> ch     = p.removePrefix("ch=").toIntOrNull() ?: 0
                p.startsWith("fmt=")     -> fmt    = p.removePrefix("fmt=")
                p.startsWith("uptime=")  -> uptime = p.removePrefix("uptime=").toLongOrNull() ?: 0L
                p == "RUNNING"           -> state  = "RUNNING"
                p == "STARTING"          -> state  = "STARTING"
                p == "STOPPING"          -> state  = "STOPPING"
                p == "OFF"               -> state  = "OFF"
                p == "ERROR"             -> state  = "ERROR"
            }
        }
        return Status(state = state, bridge = bridge, rate = rate,
                      channels = ch, format = fmt, uptime = uptime)
    }
}

// ─── ViewModel ─────────────────────────────────────────────────────────────────

class DacViewModel : ViewModel() {

    private val client = DacdClient()

    private val _dacEnabled = MutableStateFlow(false)
    val dacEnabled: StateFlow<Boolean> = _dacEnabled

    private val _status = MutableStateFlow("检测中...")
    val status: StateFlow<String> = _status

    private val _dacdAlive = MutableStateFlow(false)
    val dacdAlive: StateFlow<Boolean> = _dacdAlive

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    private val _audioInfo = MutableStateFlow("")
    val audioInfo: StateFlow<String> = _audioInfo

    // USB 相关设置
    val keepAdb = MutableStateFlow(true)
    val lowPowerPcm = MutableStateFlow(false)

    init {
        viewModelScope.launch { pollDaemon() }
    }

    /** 定期轮询守护进程状态（守护进程负责实际桥接，Activity 只负责 UI） */
    private suspend fun pollDaemon() {
        var wasAlive = false
        while (true) {
            delay(2000)
            val st = withContext(Dispatchers.IO) { client.command("STATUS") }
            if (st.isError) {
                _dacdAlive.value = false
                if (wasAlive) {
                    _status.value = "守护进程已断开"
                    _dacEnabled.value = false
                }
            } else {
                _dacdAlive.value = true
                _dacEnabled.value = st.state == "RUNNING" || st.state == "STARTING"
                _status.value = buildStatusText(st)
                if (st.rate > 0) {
                    _audioInfo.value = "${st.rate}Hz / ${st.channels}ch / ${st.format}"
                } else if (_dacEnabled.value) {
                    _audioInfo.value = "等待 PC 连接协商..."
                } else {
                    _audioInfo.value = ""
                }
            }
            wasAlive = _dacdAlive.value
        }
    }

    private fun buildStatusText(st: DacdClient.Status): String = when {
        st.isError     -> "守护进程连接错误"
        st.state == "RUNNING" && st.bridge == "alive" ->
            if (st.rate > 0) "运行中 · ${st.rate}Hz ${st.channels}ch"
            else "运行中 · 等待 PC 串流"
        st.state == "STARTING"  -> "启动中..."
        st.state == "STOPPING"  -> "停止中..."
        st.state == "ERROR"     -> "守护进程错误"
        st.state == "OFF"       -> "待命"
        else                    -> "状态未知: ${st.state}"
    }

    fun setDacEnabled(enabled: Boolean) {
        if (_isBusy.value || !_dacdAlive.value) return
        viewModelScope.launch {
            _isBusy.value = true
            _status.value = if (enabled) "正在启动..." else "正在停止..."

            withContext(Dispatchers.IO) {
                if (enabled) {
                    val adb = if (keepAdb.value) "keep_adb=1" else "keep_adb=0"
                    val lp  = if (lowPowerPcm.value) "low_power=1" else "low_power=0"
                    client.command("ENABLE $adb $lp")
                } else {
                    client.command("DISABLE")
                }
            }

            _isBusy.value = false
        }
    }
}

// ─── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            USBDACTheme {
                val vm: DacViewModel = viewModel()
                DacScreen(vm)
            }
        }
    }
}

// ─── UI ────────────────────────────────────────────────────────────────────────

@Composable
fun DacScreen(vm: DacViewModel) {
    val dacEnabled by vm.dacEnabled.collectAsState()
    val status     by vm.status.collectAsState()
    val dacdAlive  by vm.dacdAlive.collectAsState()
    val isBusy     by vm.isBusy.collectAsState()
    val keepAdb    by vm.keepAdb.collectAsState()
    val lowPower   by vm.lowPowerPcm.collectAsState()
    val audioInfo  by vm.audioInfo.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(title = { Text("USB DAC") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 守护进程状态 ──────────────────────────────────────────────
            SectionHeader("守护进程")

            val daemonColor = if (dacdAlive)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error

            InfoRow(
                label = "dacd",
                value = if (dacdAlive) "已连接" else "未运行",
                valueColor = daemonColor
            )

            if (!dacdAlive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "dacd 守护进程未运行。\n请确保已通过 init.rc 启动，或手动执行：\n  adb shell /vendor/bin/dacd -f",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }

            // ── 状态 ───────────────────────────────────────────────────────
            SectionHeader("状态")

            InfoRow("Bridge", if (isBusy) "请稍候..." else status)

            if (audioInfo.isNotEmpty()) {
                InfoRow("音频格式", audioInfo)
            }

            // ── 主开关 ─────────────────────────────────────────────────────
            SectionHeader("控制")

            SwitchRow(
                label    = "USB DAC",
                sublabel = "以 USB Audio Class 2 (UAC2) 声卡模式向 PC 呈现",
                checked  = dacEnabled,
                enabled  = dacdAlive && !isBusy,
                onCheckedChange = { vm.setDacEnabled(it) }
            )

            // ── USB 设置 ───────────────────────────────────────────────────
            SectionHeader("USB 设置")

            SwitchRow(
                label    = "保留 ADB",
                sublabel = "DAC 开启时同时维持 ADB 连接",
                checked  = keepAdb,
                enabled  = !isBusy && !dacEnabled,
                onCheckedChange = { vm.keepAdb.value = it }
            )

            SwitchRow(
                label    = "低功耗 PCM 输出",
                sublabel = "使用 pcmC1D2p（低功耗 Hi-Res）而非 pcmC1D0p",
                checked  = lowPower,
                enabled  = !isBusy && !dacEnabled,
                onCheckedChange = { vm.lowPowerPcm.value = it }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── 通用组件 ──────────────────────────────────────────────────────────────────

@Composable
fun SectionHeader(title: String) {
    Text(
        text  = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
    HorizontalDivider()
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}

@Composable
fun SwitchRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(sublabel, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
}