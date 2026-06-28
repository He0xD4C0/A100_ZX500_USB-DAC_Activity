package com.captraw.walkman.usb_dac

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.captraw.walkman.usb_dac.ui.theme.USBDACTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─── ViewModel ─────────────────────────────────────────────────────────────────
class DacViewModel(private val appFilesDir: File) : ViewModel() {

    companion object {
        private const val GADGET_CONFIG  = "/config/usb_gadget/g1"
        private const val UAC2_FUNC      = "uac2.gs0"
        private const val BRIDGE_BIN_NAME = "uac2_bridge"

        // configfs 声明最大能力，PC 端按需协商，bridge 运行时跟随
        private const val MAX_SRATE = "192000"
        private const val MAX_SSIZE = "24"
        private const val CHMASK_STEREO = "3"
    }

    private val _dacEnabled = MutableStateFlow(false)
    val dacEnabled: StateFlow<Boolean> = _dacEnabled

    private val _status = MutableStateFlow("检测中...")
    val status: StateFlow<String> = _status

    private val _isRooted = MutableStateFlow(false)
    val isRooted: StateFlow<Boolean> = _isRooted

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    // USB 相关设置
    /** 保留 ADB：DAC 开启时是否同时保留 ADB 函数 */
    val keepAdb = MutableStateFlow(true)

    /**
     * 使用低功耗 PCM 接口 (pcmC1D2p) 还是 Hi-Res 接口 (pcmC1D0p)
     * false = Hi-Res (device 0), true = Low Power (device 2)
     */
    val lowPowerPcm = MutableStateFlow(false)

    private val bridgeBin: File get() = File(appFilesDir, BRIDGE_BIN_NAME)

    init {
        viewModelScope.launch {
            checkRoot()
            pollBridgeAlive()
        }
    }

    private suspend fun checkRoot() = withContext(Dispatchers.IO) {
        _isRooted.value = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out  = proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0 && out.contains("uid=0")
        }.getOrDefault(false)
        _status.value = if (_isRooted.value) "就绪" else "未获取 Root 权限"
    }

    private suspend fun pollBridgeAlive() {
        while (true) {
            delay(2000)
            if (_dacEnabled.value && !withContext(Dispatchers.IO) { isBridgeAlive() }) {
                _dacEnabled.value = false
                _status.value = "守护进程意外退出"
            }
        }
    }

    private fun isBridgeAlive(): Boolean = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof uac2_bridge"))
        val pid  = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor() == 0 && pid.isNotEmpty()
    }.getOrDefault(false)

    private suspend fun extractBridge(context: android.content.Context) = withContext(Dispatchers.IO) {
        if (!bridgeBin.exists()) {
            context.assets.open(BRIDGE_BIN_NAME).use { i ->
                bridgeBin.outputStream().use { o -> i.copyTo(o) }
            }
        }
        Runtime.getRuntime().exec(arrayOf("chmod", "755", bridgeBin.absolutePath)).waitFor()
    }

    fun setDacEnabled(enabled: Boolean, context: android.content.Context) {
        if (_isBusy.value || !_isRooted.value) return
        viewModelScope.launch {
            _isBusy.value = true
            if (enabled) enableDac(context) else disableDac()
            _isBusy.value = false
        }
    }

    private suspend fun enableDac(context: android.content.Context) = withContext(Dispatchers.IO) {
        runCatching {
            _status.value = "释放守护进程..."
            extractBridge(context)

            _status.value = "配置 UAC2 Gadget..."
            val funcDir = "$GADGET_CONFIG/functions/$UAC2_FUNC"
            // 声明最大硬件能力；PC 与设备的实际协商格式由 USB 标准决定
            su("mkdir -p $funcDir")
            su("echo $CHMASK_STEREO > $funcDir/p_chmask")
            su("echo $CHMASK_STEREO > $funcDir/c_chmask")
            su("echo $MAX_SRATE > $funcDir/p_srate")
            su("echo $MAX_SRATE > $funcDir/c_srate")
            su("echo $MAX_SSIZE > $funcDir/p_ssize")
            su("echo $MAX_SSIZE > $funcDir/c_ssize")

            _status.value = "绑定 UAC2 到 USB 配置..."
            val link = "$GADGET_CONFIG/configs/b.1/f2"
            su("[ -L $link ] || ln -s $funcDir $link")

            // 若不保留 ADB，先解绑再重绑（清除 ADB 函数）
            if (!keepAdb.value) {
                _status.value = "断开 ADB 并重枚举..."
                su("echo '' > $GADGET_CONFIG/UDC")
                su("[ -L $GADGET_CONFIG/configs/b.1/f1 ] && rm $GADGET_CONFIG/configs/b.1/f1; true")
                delay(500)
            } else {
                _status.value = "重枚举 USB..."
                su("echo '' > $GADGET_CONFIG/UDC")
                delay(500)
            }
            su("echo ci_hdrc.0 > $GADGET_CONFIG/UDC")
            delay(2000)

            // CXD3778GF PCM device: 0 = hires-out, 2 = hires-out-low-power
            val cxdDevice = if (lowPowerPcm.value) 2 else 0
            _status.value = "启动 Bridge Daemon..."
            su("nohup ${bridgeBin.absolutePath} start -d $cxdDevice > /data/local/tmp/uac2_bridge.log 2>&1 &")
            delay(1500)

            if (isBridgeAlive()) {
                _dacEnabled.value = true
                val pcmLabel = if (lowPowerPcm.value) "低功耗" else "Hi-Res"
                _status.value = "运行中 · $pcmLabel 输出"
            } else {
                _status.value = "守护进程启动失败（查看 /data/local/tmp/uac2_bridge.log）"
            }
        }.onFailure { _status.value = "启动错误: ${it.message}" }
    }

    private suspend fun disableDac() = withContext(Dispatchers.IO) {
        runCatching {
            _status.value = "停止守护进程..."
            su("killall uac2_bridge 2>/dev/null; true")
            delay(500)

            _status.value = "解绑 UAC2..."
            val link    = "$GADGET_CONFIG/configs/b.1/f2"
            val funcDir = "$GADGET_CONFIG/functions/$UAC2_FUNC"
            su("echo '' > $GADGET_CONFIG/UDC")
            su("[ -L $link ] && rm $link; true")
            su("[ -d $funcDir ] && rmdir $funcDir; true")

            // 若之前移除了 ADB，恢复 ADB 连接
            if (!keepAdb.value) {
                su("[ -L $GADGET_CONFIG/configs/b.1/f1 ] || ln -s $GADGET_CONFIG/functions/ffs.adb $GADGET_CONFIG/configs/b.1/f1; true")
            }
            su("echo ci_hdrc.0 > $GADGET_CONFIG/UDC")

            _dacEnabled.value = false
            _status.value = "已停止"
        }.onFailure { _status.value = "关闭错误: ${it.message}" }
    }

    private fun su(cmd: String): Int {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        return proc.waitFor()
    }
}

class DacViewModelFactory(private val appFilesDir: File) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DacViewModel(appFilesDir) as T
}

// ─── Activity ──────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            USBDACTheme {
                val vm: DacViewModel = viewModel(factory = DacViewModelFactory(filesDir))
                DacScreen(vm)
            }
        }
    }
}

// ─── UI ────────────────────────────────────────────────────────────────────────
@Composable
fun DacScreen(vm: DacViewModel) {
    val context    = LocalContext.current
    val dacEnabled by vm.dacEnabled.collectAsState()
    val status     by vm.status.collectAsState()
    val isRooted   by vm.isRooted.collectAsState()
    val isBusy     by vm.isBusy.collectAsState()
    val keepAdb    by vm.keepAdb.collectAsState()
    val lowPower   by vm.lowPowerPcm.collectAsState()

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(title = { Text("USB DAC 模式") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── 状态 ─────────────────────────────────────────────────────────
            SectionHeader("状态")

            InfoRow("Root 权限", if (isRooted) "已授权" else "未获取")
            InfoRow("Bridge", if (isBusy) "请稍候..." else status)

            // ── 主开关 ───────────────────────────────────────────────────────
            SectionHeader("控制")

            SwitchRow(
                label    = "USB DAC",
                sublabel = "以 USB Audio Class 2 (UAC2) 声卡模式向 PC 呈现",
                checked  = dacEnabled,
                enabled  = isRooted && !isBusy,
                onCheckedChange = { vm.setDacEnabled(it, context) }
            )

            // ── USB 设置 ─────────────────────────────────────────────────────
            SectionHeader("USB 设置")

            SwitchRow(
                label    = "保留 ADB",
                sublabel = "DAC 开启时继续维持 ADB 连接（禁用时 DAC 关闭后自动恢复）",
                checked  = keepAdb,
                enabled  = !dacEnabled,
                onCheckedChange = { vm.keepAdb.value = it }
            )

            SwitchRow(
                label    = "低功耗 PCM 输出",
                sublabel = "使用 pcmC1D2p（低功耗 Hi-Res）而非 pcmC1D0p（Hi-Res）",
                checked  = lowPower,
                enabled  = !dacEnabled,
                onCheckedChange = { vm.lowPowerPcm.value = it }
            )

            // ── 说明 ─────────────────────────────────────────────────────────
            if (!isRooted) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "需要 Root 权限才能操作 USB Gadget 和启动 Bridge。\n请通过 Magisk / KernelSU 授权本 App。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }

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
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
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