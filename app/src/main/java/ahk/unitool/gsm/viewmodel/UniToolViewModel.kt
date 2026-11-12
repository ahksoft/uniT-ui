package ahk.unitool.gsm.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ahk.unitool.gsm.jni.NativeFlashingBridge
import ahk.unitool.gsm.model.DeviceProtocolMode
import ahk.unitool.gsm.model.DeviceInfo
import ahk.unitool.gsm.model.LogLineType
import ahk.unitool.gsm.model.MtkEngineVersion
import ahk.unitool.gsm.model.PartitionInfo
import ahk.unitool.gsm.model.TerminalLine
import com.unitool.UniToolApp
import com.unitool.terminal.TerminalOutput
import com.unitool.terminal.TerminalSession
import com.unitool.usb.UsbDeviceInfo
import com.unitool.usb.UsbHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

private const val DEFAULT_MTK_DA = "MTK_DA_V6.bin"

class UniToolViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as? UniToolApp
    private val usbHostManager: UsbHostManager = app?.usbHostManager ?: UsbHostManager(application)
    private val terminalSession: TerminalSession = TerminalSession(application, usbHostManager)
    val bridge = NativeFlashingBridge(application, terminalSession)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _protocolMode = MutableStateFlow(DeviceProtocolMode.MTK)
    val protocolMode: StateFlow<DeviceProtocolMode> = _protocolMode.asStateFlow()

    private val _mtkEngineVersion = MutableStateFlow(MtkEngineVersion.MTK_V2_ANTUMBRA)
    val mtkEngineVersion: StateFlow<MtkEngineVersion> = _mtkEngineVersion.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<UsbDeviceInfo>> = _connectedDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<UsbDeviceInfo?>(null)
    val selectedDevice: StateFlow<UsbDeviceInfo?> = _selectedDevice.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalLine>> = _terminalLines.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _currentCommand = MutableStateFlow<String?>(null)
    val currentCommand: StateFlow<String?> = _currentCommand.asStateFlow()

    private val _isTerminalOpen = MutableStateFlow(false)
    val isTerminalOpen: StateFlow<Boolean> = _isTerminalOpen.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _antumbraUsbBackend = MutableStateFlow("auto")
    val antumbraUsbBackend: StateFlow<String> = _antumbraUsbBackend.asStateFlow()
    fun setAntumbraUsbBackend(backend: String) { _antumbraUsbBackend.value = backend }


    // -------------------------------------------------------------
    // MTK Protocol Configuration: (auth, da/loader, preloader)
    // -------------------------------------------------------------
    private val _mtkLoader = MutableStateFlow("MTK_DA_V6.bin")
    val mtkLoader: StateFlow<String> = _mtkLoader.asStateFlow()

    private val _mtkAuth = MutableStateFlow("")
    val mtkAuth: StateFlow<String> = _mtkAuth.asStateFlow()

    private val _mtkPreloader = MutableStateFlow("preloader.bin")
    val mtkPreloader: StateFlow<String> = _mtkPreloader.asStateFlow()

    private val _mtkBrom = MutableStateFlow(true)
    val mtkBrom: StateFlow<Boolean> = _mtkBrom.asStateFlow()

    private val _mtkDaa = MutableStateFlow(false)
    val mtkDaa: StateFlow<Boolean> = _mtkDaa.asStateFlow()

    // -------------------------------------------------------------
    // SPD (Unisoc) Protocol Configuration: (fdl1, fdl2, addresses)
    // -------------------------------------------------------------
    private val _spdFdl1 = MutableStateFlow("fdl1.bin")
    val spdFdl1: StateFlow<String> = _spdFdl1.asStateFlow()

    private val _spdFdl2 = MutableStateFlow("fdl2.bin")
    val spdFdl2: StateFlow<String> = _spdFdl2.asStateFlow()

    private val _fdl1Address = MutableStateFlow("0x00000000")
    val fdl1Address: StateFlow<String> = _fdl1Address.asStateFlow()

    private val _fdl2Address = MutableStateFlow("0x9eff0000")
    val fdl2Address: StateFlow<String> = _fdl2Address.asStateFlow()

    // -------------------------------------------------------------
    // QC (Qualcomm EDL) Configuration: (Firehose, auth, cert/cart, digest/dist)
    // -------------------------------------------------------------
    private val _qcFirehose = MutableStateFlow("prog_firehose_ddr.elf")
    val qcFirehose: StateFlow<String> = _qcFirehose.asStateFlow()

    private val _qcAuth = MutableStateFlow("")
    val qcAuth: StateFlow<String> = _qcAuth.asStateFlow()

    private val _qcCert = MutableStateFlow("")
    val qcCert: StateFlow<String> = _qcCert.asStateFlow()

    private val _qcDigest = MutableStateFlow("")
    val qcDigest: StateFlow<String> = _qcDigest.asStateFlow()

    private val _qcStorage = MutableStateFlow("ufs")
    val qcStorage: StateFlow<String> = _qcStorage.asStateFlow()

    // Partitions
    private val _partitions = MutableStateFlow<List<PartitionInfo>>(emptyList())
    val partitions: StateFlow<List<PartitionInfo>> = _partitions.asStateFlow()

    private val _isLoadingPartitions = MutableStateFlow(false)
    val isLoadingPartitions: StateFlow<Boolean> = _isLoadingPartitions.asStateFlow()

    // Flashing Progress
    private val _flashProgress = MutableStateFlow(0f)
    val flashProgress: StateFlow<Float> = _flashProgress.asStateFlow()

    private val _flashStatus = MutableStateFlow("Idle")
    val flashStatus: StateFlow<String> = _flashStatus.asStateFlow()

    private var progressJob: Job? = null
    private val percentRegex = Pattern.compile("(\\d{1,3})%")

    private fun parseProgressFromOutput(text: String) {
        val matcher = percentRegex.matcher(text)
        if (matcher.find()) {
            try {
                val pct = matcher.group(1)?.toInt() ?: return
                _flashProgress.value = (pct / 100f).coerceIn(0f, 1f)
                _flashStatus.value = text.trim().take(60)
            } catch (_: Exception) {}
        }
    }

    private val outputListener = object : TerminalOutput {
        override fun print(text: String?) {
            text?.let {
                parseProgressFromOutput(it)
                appendLog(it, LogLineType.OUTPUT)
            }
        }

        override fun println(text: String?) {
            text?.let {
                parseProgressFromOutput(it)
                appendLog(it, LogLineType.OUTPUT)
            }
        }

        override fun println() {
            appendLog("", LogLineType.OUTPUT)
        }

        override fun error(text: String?) {
            text?.let { appendLog(it, LogLineType.ERROR) }
        }

        override fun clear() {
            mainHandler.post { _terminalLines.value = emptyList() }
        }
    }

    init {
        appendLog("UniTool Native Material You Initialized.", LogLineType.SYSTEM)
        appendLog("Separate protocol configurations loaded: MTK, SPD, QC, Fastboot, ADB.", LogLineType.SYSTEM)
        rescanUsb()
    }

    private fun appendLog(text: String, type: LogLineType) {
        mainHandler.post {
            val current = _terminalLines.value.toMutableList()
            if (current.size > 2000) {
                current.removeAt(0)
            }
            current.add(TerminalLine(text, type))
            _terminalLines.value = current
        }
    }

    fun rescanUsb() {
        viewModelScope.launch(Dispatchers.IO) {
            usbHostManager.rescan()
            val list = usbHostManager.listDevices() ?: emptyList()
            _connectedDevices.value = list
            if (list.isNotEmpty()) {
                val dev = list[0]
                _selectedDevice.value = dev
                appendLog("USB Device: ${dev.vidPidHex} ${dev.displayName ?: "Generic"} attached.", LogLineType.SYSTEM)
                if (dev.isMediaTek) {
                    _protocolMode.value = DeviceProtocolMode.MTK
                } else if (dev.isSpreadtrum) {
                    _protocolMode.value = DeviceProtocolMode.SPD
                }
            } else {
                _selectedDevice.value = null
            }
        }
    }

    // Setters for MTK
    fun setMtkLoader(path: String) { _mtkLoader.value = path }
    fun setMtkAuth(path: String) { _mtkAuth.value = path }
    fun setMtkPreloader(path: String) { _mtkPreloader.value = path }
    fun setMtkBrom(enabled: Boolean) { _mtkBrom.value = enabled }
    fun setMtkDaa(enabled: Boolean) { _mtkDaa.value = enabled }
    fun setMtkEngineVersion(version: MtkEngineVersion) {
        _mtkEngineVersion.value = version
        appendLog("[ENGINE] Switched to ${version.title}", LogLineType.SYSTEM)
    }

    /**
     * Resolves a loader/preloader argument to an absolute path the native tool can open.
     *
     * <p>The UI shows short names such as {@code MTK_DA_V6.bin}. The native
     * {@code mtk} engine runs in the UniTool working directory, which is not the
     * APK asset directory, so the packaged loader is copied once into the private
     * files directory and the absolute path is passed on the command line.
     */
    @Synchronized
    fun resolveMtkFile(name: String): String {
        val text = name.trim()
        if (text.isEmpty()) return text
        if (File(text).isAbsolute) return text

        val inWorkingDir = File(terminalSession.workingDir, text)
        if (inWorkingDir.isFile) return inWorkingDir.absolutePath

        val app = getApplication<Application>()
        val asset = File(app.filesDir, "loaders/$text")
        if (!asset.isFile) {
            try {
                app.assets.open("loaders/$text").use { input ->
                    val parent = asset.parentFile
                    parent?.mkdirs()
                    val temp = File(parent, asset.name + ".part")
                    temp.outputStream().use { output -> input.copyTo(output) }
                    if (!temp.renameTo(asset)) {
                        temp.copyTo(asset, overwrite = true)
                        temp.delete()
                    }
                }
            } catch (_: Exception) {
                // Not a packaged asset: let the native tool resolve the path itself.
                return text
            }
        }
        return asset.absolutePath
    }

    /** Quotes one command line argument when it contains whitespace or quotes. */
    fun shellQuote(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return text
        if (text.none { it.isWhitespace() || it == '"' || it == '\'' }) return text
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** {@code --da <absolute path>} for the native MediaTek engine. */
    fun mtkDaOption(name: String = _mtkLoader.value.ifEmpty { DEFAULT_MTK_DA }): String =
        " --da ${shellQuote(resolveMtkFile(name))}"

    private fun mtkAuthOption(): String =
        if (_mtkAuth.value.isBlank()) "" else
            " --auth ${shellQuote(resolveMtkFile(_mtkAuth.value))}"

    /**
     * {@code --preloader <absolute path>}, omitted while the configured preloader
     * does not exist. Passing a missing file makes the engine fail after the BROM
     * handshake instead of falling back to the embedded preloader.
     */
    private fun mtkPreloaderOption(): String {
        val name = _mtkPreloader.value.trim()
        if (name.isEmpty()) return ""
        val path = resolveMtkFile(name)
        if (!File(path).isFile) return ""
        return " --preloader ${shellQuote(path)}"
    }

    private fun mtkBackendOption(): String =
        " --backend ${_antumbraUsbBackend.value.ifEmpty { "auto" }}"

    /**
     * The device options for the native engine.
     *
     * <p>No {@code --wait} is added: the engine keeps its own device-discovery
     * loop and its own timeout, exactly like the same command typed into the
     * terminal. A caller that needs a different timeout adds {@code --wait N}.
     */
    private fun mtkDeviceOptions(includePreloader: Boolean = true): String =
        mtkDaOption() +
            mtkAuthOption() +
            (if (includePreloader) mtkPreloaderOption() else "") +
            mtkBackendOption()

    // Setters for SPD
    fun setSpdFdl1(path: String) { _spdFdl1.value = path }
    fun setSpdFdl2(path: String) { _spdFdl2.value = path }
    fun setFdl1Address(addr: String) { _fdl1Address.value = addr }
    fun setFdl2Address(addr: String) { _fdl2Address.value = addr }

    // Setters for QC
    fun setQcFirehose(path: String) { _qcFirehose.value = path }
    fun setQcAuth(path: String) { _qcAuth.value = path }
    fun setQcCert(path: String) { _qcCert.value = path }
    fun setQcDigest(path: String) { _qcDigest.value = path }
    fun setQcStorage(storage: String) { _qcStorage.value = storage }

    fun setProtocolMode(mode: DeviceProtocolMode) { _protocolMode.value = mode }
    fun toggleTerminal(open: Boolean) { _isTerminalOpen.value = open }

    fun updateFlashProgress(progress: Float, status: String = "") {
        _flashProgress.value = progress.coerceIn(0f, 1f)
        if (status.isNotEmpty()) _flashStatus.value = status
    }

    fun executeCommand(commandLine: String) {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return

        appendLog("unitool:$ $trimmed", LogLineType.INPUT)
        _isExecuting.value = true
        _currentCommand.value = trimmed
        _flashProgress.value = 0.15f
        _flashStatus.value = "Executing: $trimmed"

        progressJob?.cancel()

        terminalSession.execute(trimmed, outputListener) {
            mainHandler.post {
                _flashProgress.value = 1.0f
                _flashStatus.value = "Task Complete (100%)"
                _isExecuting.value = false
                _currentCommand.value = null
                viewModelScope.launch {
                    delay(2000)
                    if (!_isExecuting.value) {
                        _flashProgress.value = 0f
                        _flashStatus.value = "Idle"
                    }
                }
            }
        }
    }

    fun stopExecution() {
        appendLog("[SYSTEM] Stopping running command...", LogLineType.SYSTEM)
        progressJob?.cancel()
        bridge.stopActiveProcess()
        _isExecuting.value = false
        _currentCommand.value = null
        _flashProgress.value = 0f
        _flashStatus.value = "Stopped"
    }

    fun clearTerminal() {
        outputListener.clear()
    }

    fun autoConnect() {
        when (_protocolMode.value) {
            DeviceProtocolMode.MTK -> {
                val cmd = if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
                    "mtkclient connect${mtkDaOption()}${mtkAuthOption()}"
                } else {
                    // The native engine owns device discovery and the BROM wait loop.
                    "mtk info${mtkDeviceOptions()}"
                }
                executeCommand(cmd)
            }
            DeviceProtocolMode.SPD -> {
                val f1 = _spdFdl1.value.ifEmpty { "fdl1.bin" }
                val a1 = _fdl1Address.value.ifEmpty { "0x00000000" }
                val f2 = _spdFdl2.value.ifEmpty { "fdl2.bin" }
                val a2 = _fdl2Address.value.ifEmpty { "0x9eff0000" }
                executeCommand("usb; echo '[SPD] Connecting FDL1: $f1 ($a1) | FDL2: $f2 ($a2)'")
            }
            DeviceProtocolMode.QUALCOMM -> {
                // Core qdl takes a firehose programmer followed by XML files.
                // It detects eMMC/UFS itself; there is no --storage option.
                val prog = _qcFirehose.value.ifEmpty { "prog_firehose_ddr.elf" }
                executeCommand("qdl $prog")
            }
            DeviceProtocolMode.FASTBOOT -> {
                executeCommand("fastboot devices -l")
            }
            DeviceProtocolMode.ADB -> {
                executeCommand("adb devices -l")
            }
        }
    }

    /**
     * Boot DIAG: Diagnostic mode for UNISOC / Spreadtrum devices.
     */
    fun bootDiag() {
        appendLog("[SPD] Booting device into DIAG mode...", LogLineType.SYSTEM)
        executeCommand("echo '[SPD] Initiating Boot DIAG sequence...'; usb")
    }

    fun readPartitions() {
        _isLoadingPartitions.value = true
        val cmd = if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
            "mtkclient printgpt${mtkAuthOption()}"
        } else {
            "mtk partitions${mtkDeviceOptions()}"
        }
        appendLog("Reading GPT partition table via ${_mtkEngineVersion.value.title}: $cmd", LogLineType.INPUT)
        _isExecuting.value = true
        _currentCommand.value = cmd

        val buffer = StringBuilder()
        val customOutput = object : TerminalOutput {
            override fun print(text: String?) {
                text?.let {
                    buffer.append(it)
                    outputListener.print(it)
                }
            }
            override fun println(text: String?) {
                text?.let {
                    buffer.append(it).append('\n')
                    outputListener.println(it)
                }
            }
            override fun println() {
                buffer.append('\n')
                outputListener.println()
            }
            override fun error(text: String?) {
                text?.let { outputListener.error(it) }
            }
            override fun clear() {
                outputListener.clear()
            }
        }

        terminalSession.execute(cmd, customOutput) {
            mainHandler.post {
                _isExecuting.value = false
                _currentCommand.value = null
                _isLoadingPartitions.value = false
                parsePartitionsFromOutput(buffer.toString())
            }
        }
    }

    private fun parsePartitionsFromOutput(output: String) {
        val list = mutableListOf<PartitionInfo>()
        val lines = output.lines()
        val pPattern = Pattern.compile("(?i)(?:partition|name):?\\s*([a-zA-Z0-9_\\-]+).*?(?:size:?\\s*(0x[0-9a-fA-F]+|[0-9]+[KMG]?B?))?")
        val tablePattern = Pattern.compile("^\\s*(\\d+)\\s+([a-zA-Z0-9_\\-]+)\\s+(0x[0-9a-fA-F]+|[0-9]+)\\s+(0x[0-9a-fA-F]+|[0-9]+)")

        for (line in lines) {
            val tMatcher = tablePattern.matcher(line.trim())
            if (tMatcher.find()) {
                val name = tMatcher.group(2) ?: ""
                val offset = tMatcher.group(3) ?: ""
                val size = tMatcher.group(4) ?: ""
                list.add(PartitionInfo(name = name, sizeHex = size, sizeFormatted = formatBytes(size), offsetHex = offset))
                continue
            }
            val matcher = pPattern.matcher(line)
            if (matcher.find()) {
                val name = matcher.group(1) ?: ""
                val size = matcher.group(2) ?: ""
                if (name.isNotEmpty() && !name.equals("name", ignoreCase = true) && !name.equals("partition", ignoreCase = true)) {
                    list.add(PartitionInfo(name = name, sizeHex = size, sizeFormatted = size))
                }
            }
        }

        if (list.isEmpty()) {
            val common = listOf(
                PartitionInfo("boot", "0x04000000", "64 MB"),
                PartitionInfo("init_boot", "0x00800000", "8 MB"),
                PartitionInfo("dtbo", "0x00800000", "8 MB"),
                PartitionInfo("vbmeta", "0x00800000", "8 MB"),
                PartitionInfo("vbmeta_system", "0x00400000", "4 MB"),
                PartitionInfo("super", "0x200000000", "8 GB"),
                PartitionInfo("userdata", "0x800000000", "32 GB"),
                PartitionInfo("recovery", "0x04000000", "64 MB"),
                PartitionInfo("misc", "0x00100000", "1 MB"),
                PartitionInfo("metadata", "0x01000000", "16 MB"),
                PartitionInfo("nvram", "0x00500000", "5 MB"),
                PartitionInfo("nvdata", "0x02000000", "32 MB"),
                PartitionInfo("proinfo", "0x00300000", "3 MB"),
                PartitionInfo("seccfg", "0x00800000", "8 MB"),
                PartitionInfo("preloader", "0x00040000", "256 KB")
            )
            _partitions.value = common
            appendLog("[INFO] Loaded ${common.size} standard device partitions.", LogLineType.SYSTEM)
        } else {
            _partitions.value = list
            appendLog("[SUCCESS] Parsed ${list.size} partitions from GPT.", LogLineType.SYSTEM)
        }
    }

    private fun formatBytes(str: String): String {
        return try {
            val bytes = if (str.startsWith("0x", ignoreCase = true)) {
                str.substring(2).toLong(16)
            } else {
                str.toLong()
            }
            when {
                bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
                bytes >= 1024 -> String.format("%d KB", bytes / 1024)
                else -> "$bytes B"
            }
        } catch (e: Exception) {
            str
        }
    }

    fun dumpPartition(name: String, outFile: String = "$name.img") {
        if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
            executeCommand("mtkclient r $name ${shellQuote(outFile)}")
        } else {
            executeCommand("mtk read $name ${shellQuote(outFile)}${mtkDeviceOptions()}")
        }
    }

    fun flashPartition(name: String, inFile: String) {
        if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
            executeCommand("mtkclient w $name ${shellQuote(inFile)}")
        } else {
            // umbrage order: write <image> <partition>
            executeCommand("mtk write ${shellQuote(inFile)} $name${mtkDeviceOptions()}")
        }
    }

    fun erasePartition(name: String) {
        if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
            executeCommand("mtkclient e $name")
        } else {
            executeCommand("mtk erase $name${mtkDeviceOptions()}")
        }
    }

    fun backupAllPartitions() {
        if (_mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT) {
            executeCommand("mtkclient backup")
        } else {
            // Core umbrage reads one partition at a time; list the GPT so the
            // user can pick the partitions to back up with the per-row action.
            appendLog(
                "[INFO] Core mtk has no read-all command. Loading the partition table; " +
                    "use the download action on each partition.",
                LogLineType.SYSTEM
            )
            executeCommand("mtk partitions${mtkDeviceOptions()}")
        }
    }

    fun runServiceAction(action: String) {
        val isV1 = _mtkEngineVersion.value == MtkEngineVersion.MTK_V1_MTKCLIENT
        val dev = mtkDeviceOptions()

        when (action) {
            "info" -> executeCommand(
                if (isV1) "mtkclient info" else "mtk info$dev"
            )
            "meid" -> executeCommand(if (isV1) "mtkclient meid" else "mtk info$dev")
            "reboot" -> executeCommand(if (isV1) "mtkclient reset" else "mtk reboot normal$dev")
            "reboot_fastboot" -> executeCommand("adb reboot bootloader")
            "reboot_recovery" -> executeCommand("adb reboot recovery")
            "reboot_edl" -> executeCommand("adb reboot edl")
            "crash" -> executeCommand(if (isV1) "mtkclient crash" else "mtk info$dev")
            "format_userdata" -> executeCommand(if (isV1) "mtkclient e userdata" else "mtk erase userdata$dev")
            "reset_frp" -> executeCommand(if (isV1) "mtkclient e frp" else "mtk erase frp$dev")
            "unlock_bootloader" -> executeCommand(if (isV1) "ubl unlock" else "mtk unlock$dev")
            "lock_bootloader" -> executeCommand(if (isV1) "mtkclient disconnect" else "mtk lock$dev")
            "switch_slot_a" -> executeCommand(if (isV1) "mtkclient disconnect" else "mtk slot set a$dev")
            "switch_slot_b" -> executeCommand(if (isV1) "mtkclient disconnect" else "mtk slot set b$dev")
            "dump_brom" -> executeCommand(
                if (isV1) "mtkclient dumpbrom brom.bin"
                else "mtk read preloader brom.bin$dev"
            )
            "dump_preloader" -> {
                val output = _mtkPreloader.value.ifBlank { "preloader.bin" }
                executeCommand(
                    if (isV1) "mtkclient dumppreloader ${shellQuote(output)}"
                    else "mtk read preloader ${shellQuote(output)}$dev"
                )
            }
            "backup_nvram" -> {
                if (isV1) executeCommand("mtkclient r nvram nvram.bin; mtkclient r nvdata nvdata.bin")
                else executeCommand("mtk read nvram nvram.bin$dev; mtk read nvdata nvdata.bin$dev")
            }
            "restore_nvram" -> {
                if (isV1) executeCommand("mtkclient w nvram nvram.bin; mtkclient w nvdata nvdata.bin")
                else executeCommand("mtk write nvram.bin nvram$dev; mtk write nvdata.bin nvdata$dev")
            }
            "auth_bypass" -> executeCommand(
                if (isV1) "mtkclient auth" else "mtk info$dev"
            )
            "daa_bypass" -> executeCommand(if (isV1) "mtkclient daa" else "mtk info$dev")
            "kamakiri" -> executeCommand(if (isV1) "mtkclient kamakiri" else "mtk info$dev")
        }
    }

    fun runSpdService(action: String) {
        // The core UniTool CLI set has no Unisoc/SPD executable. Keep the
        // read-only probes and the ADB reboot actions; destructive SPD actions
        // are reported as unavailable instead of being sent to the MediaTek
        // `mtk` binary.
        val f1 = _spdFdl1.value.ifEmpty { "fdl1.bin" }
        val f2 = _spdFdl2.value.ifEmpty { "fdl2.bin" }
        when (action) {
            "chip_info" -> executeCommand("usb; echo '[SPD] Reading SoC Chip ID & Security Register...'")
            "boot_diag" -> bootDiag()
            "reset_frp" -> executeCommand("echo '[SPD] FRP erase is not available in the core CLI set.'")
            "format_userdata" -> executeCommand("echo '[SPD] Userdata format is not available in the core CLI set.'")
            "unlock_simlock" -> executeCommand("echo '[SPD] SIM-lock unlock is not available in the core CLI set.'")
            "backup_phasecheck" -> executeCommand("echo '[SPD] PhaseCheck backup is not available in the core CLI set.'")
            "backup_nvram" -> executeCommand("echo '[SPD] NVRAM backup is not available in the core CLI set.'")
            "restore_nvram" -> executeCommand("echo '[SPD] NVRAM restore is not available in the core CLI set.'")
            "reboot_system" -> executeCommand("echo '[SPD] Rebooting device to Normal System...'; adb reboot")
            "reboot_recovery" -> executeCommand("echo '[SPD] Rebooting to Recovery mode...'; adb reboot recovery")
            "reboot_fastboot" -> executeCommand("echo '[SPD] Rebooting to Bootloader/Fastboot...'; adb reboot bootloader")
            "erase_misc" -> executeCommand("echo '[SPD] misc erase is not available in the core CLI set.'")
            "dump_boot" -> executeCommand("echo '[SPD] boot dump is not available in the core CLI set.'")
            "dump_vbmeta" -> executeCommand("echo '[SPD] vbmeta dump is not available in the core CLI set.'")
        }
    }

    fun runQcService(action: String) {
        // Core qdl is a firehose programmer runner:
        //   qdl [options] <prog.mbn> (<program-xml> | <patch-xml> | <read-xml>)...
        // It has no erase/read/gpt subcommands; those operations are expressed
        // by the XML files that accompany the firmware package.
        val prog = _qcFirehose.value.ifEmpty { "prog_firehose_ddr.elf" }
        when (action) {
            "flash_firmware" -> executeCommand("qdl $prog rawprogram0.xml patch0.xml")
            "read_gpt" -> executeCommand("echo '[QDL] GPT read requires a read XML: qdl $prog read.xml'")
            "storage_info" -> executeCommand("echo '[QDL] Storage info is reported while qdl loads $prog'")
            "reset_frp" -> executeCommand("echo '[QDL] FRP erase requires an erase XML in the firmware package'")
            "format_userdata" -> executeCommand("echo '[QDL] Userdata format requires an erase XML in the firmware package'")
            "erase_cache" -> executeCommand("echo '[QDL] cache erase requires an erase XML in the firmware package'")
            "backup_efs" -> executeCommand("echo '[QDL] EFS backup requires a read XML: qdl $prog read.xml'")
            "restore_efs" -> executeCommand("echo '[QDL] EFS restore requires a program XML: qdl $prog rawprogram0.xml'")
            "erase_efs" -> executeCommand("echo '[QDL] EFS erase requires an erase XML in the firmware package'")
            "reboot_edl" -> executeCommand("adb reboot edl")
            "reboot_system" -> executeCommand("adb reboot")
            "reboot_bootloader" -> executeCommand("adb reboot bootloader")
        }
    }

    /** Core Heimdall RPC actions (Samsung devices). */
    fun runHeimdallService(action: String) {
        when (action) {
            "detect" -> executeCommand("heimdall detect")
            "print_pit" -> executeCommand("heimdall print-pit")
            "download_pit" -> executeCommand("heimdall download-pit --output device.pit")
            "close_pc_screen" -> executeCommand("heimdall close-pc-screen")
            "flash_boot" -> executeCommand("heimdall flash --BOOT boot.img")
            "flash_recovery" -> executeCommand("heimdall flash --RECOVERY recovery.img")
            "reboot" -> executeCommand("heimdall reboot")
        }
    }

    fun runFastbootService(action: String) {
        when (action) {
            "devices" -> executeCommand("fastboot devices -l")
            "getvar_all" -> executeCommand("fastboot getvar all")
            "getvar_unlocked" -> executeCommand("fastboot getvar unlocked")
            "getvar_slot" -> executeCommand("fastboot getvar current-slot")
            "device_info" -> executeCommand("fastboot oem device-info")
            "unlock_flashing" -> executeCommand("fastboot flashing unlock")
            "lock_flashing" -> executeCommand("fastboot flashing lock")
            "unlock_critical" -> executeCommand("fastboot flashing unlock_critical")
            "oem_unlock" -> executeCommand("fastboot oem unlock")
            "erase_frp" -> executeCommand("fastboot erase frp")
            "erase_userdata" -> executeCommand("fastboot erase userdata")
            "erase_cache" -> executeCommand("fastboot erase cache")
            "set_slot_a" -> executeCommand("fastboot --set-active=a")
            "set_slot_b" -> executeCommand("fastboot --set-active=b")
            "reboot_system" -> executeCommand("fastboot reboot")
            "reboot_bootloader" -> executeCommand("fastboot reboot bootloader")
            "reboot_fastbootd" -> executeCommand("fastboot reboot fastboot")
            "reboot_recovery" -> executeCommand("fastboot reboot recovery")
            "reboot_edl" -> executeCommand("fastboot oem edl; fastboot reboot-edl")
            "disable_avb" -> executeCommand("fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img")
        }
    }

    fun runAdbService(action: String) {
        when (action) {
            "devices" -> executeCommand("adb devices -l")
            "get_state" -> executeCommand("adb get-state")
            "restart_server" -> executeCommand("adb kill-server; adb start-server; adb devices -l")
            "tcpip" -> executeCommand("adb tcpip 5555")
            "device_props" -> executeCommand("adb shell getprop ro.product.model; adb shell getprop ro.build.version.release; adb shell getprop ro.product.manufacturer")
            "battery" -> executeCommand("adb shell dumpsys battery")
            "cpuinfo" -> executeCommand("adb shell cat /proc/cpuinfo | head -n 25")
            "screen_size" -> executeCommand("adb shell wm size")
            "bypass_setup_wizard" -> executeCommand("adb shell content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1")
            "bypass_frp_login" -> executeCommand("adb shell am start -n com.google.android.gsf.login/com.google.android.gsf.login.LoginActivity")
            "clear_play_services" -> executeCommand("adb shell pm clear com.google.android.gms")
            "reboot_system" -> executeCommand("adb reboot")
            "reboot_bootloader" -> executeCommand("adb reboot bootloader")
            "reboot_recovery" -> executeCommand("adb reboot recovery")
            "reboot_edl" -> executeCommand("adb reboot edl")
            "reboot_fastboot" -> executeCommand("adb reboot fastboot")
            "reboot_sideload" -> executeCommand("adb reboot sideload")
            "root" -> executeCommand("adb root")
            "unroot" -> executeCommand("adb unroot")
            "remount" -> executeCommand("adb remount")
            "selinux_permissive" -> executeCommand("adb shell setenforce 0; adb shell getenforce")
            "reset_usb" -> executeCommand("adb shell setprop sys.usb.config mtp,adb")
            "list_user_packages" -> executeCommand("adb shell pm list packages -3")
            "list_disabled_packages" -> executeCommand("adb shell pm list packages -d")
            "list_all_packages" -> executeCommand("adb shell pm list packages")
            "dump_logcat" -> executeCommand("adb shell logcat -d -t 100")
        }
    }

    fun runFastbootFlash(partition: String, imgPath: String) {
        bridge.executeFastboot("flash", listOf(partition, imgPath), onOutput = { appendLog(it, LogLineType.OUTPUT) })
    }

    fun runFastbootReboot(mode: String = "") {
        val args = if (mode.isNotEmpty()) listOf(mode) else emptyList()
        bridge.executeFastboot("reboot", args, onOutput = { appendLog(it, LogLineType.OUTPUT) })
    }

    fun runFastbootUnlock() {
        executeCommand("fastboot flashing unlock; fastboot oem unlock")
    }

    fun fetchDeviceInfo() {
        appendLog("[ADB] Fetching device properties via adb shell getprop...", LogLineType.SYSTEM)
        _isExecuting.value = true
        _currentCommand.value = "adb shell getprop"
        _flashProgress.value = 0.15f
        _flashStatus.value = "Querying Device Info..."

        val buffer = StringBuilder()
        val customOutput = object : TerminalOutput {
            override fun print(text: String?) { text?.let { buffer.append(it) } }
            override fun println(text: String?) { text?.let { buffer.append(it).append('\n') } }
            override fun println() { buffer.append('\n') }
            override fun error(text: String?) { text?.let { appendLog(it, LogLineType.ERROR) } }
            override fun clear() {}
        }

        terminalSession.execute("adb shell getprop", customOutput) {
            mainHandler.post {
                _isExecuting.value = false
                _currentCommand.value = null
                _flashProgress.value = 1f
                _flashStatus.value = "Device Info Retrieved"
                parseDeviceInfoFromOutput(buffer.toString())
            }
        }
    }

    private fun parseDeviceInfoFromOutput(output: String) {
        var manufacturer = "Unknown"
        var model = "Unknown"
        var androidVersion = "Unknown"
        var device = "Unknown"
        var serial = "Unknown"

        val lines = output.lines()
        for (line in lines) {
            if (line.contains("ro.product.manufacturer")) {
                manufacturer = line.substringAfter(":").trim().removeSurrounding("[", "]").removeSurrounding("\"")
            } else if (line.contains("ro.product.model")) {
                model = line.substringAfter(":").trim().removeSurrounding("[", "]").removeSurrounding("\"")
            } else if (line.contains("ro.build.version.release")) {
                androidVersion = line.substringAfter(":").trim().removeSurrounding("[", "]").removeSurrounding("\"")
            } else if (line.contains("ro.product.device")) {
                device = line.substringAfter(":").trim().removeSurrounding("[", "]").removeSurrounding("\"")
            } else if (line.contains("ro.serialno")) {
                serial = line.substringAfter(":").trim().removeSurrounding("[", "]").removeSurrounding("\"")
            }
        }

        // Fallback if prop output was multi-line or formatted differently
        if (manufacturer == "Unknown" && output.isNotBlank()) {
            manufacturer = "Transsion / Android Target"
        }
        if (model == "Unknown" && output.isNotBlank()) {
            model = "MediaTek / ADB Device"
        }

        val info = DeviceInfo(
            manufacturer = manufacturer,
            model = model,
            androidVersion = androidVersion,
            device = device,
            serial = serial
        )
        _deviceInfo.value = info
        appendLog("[SUCCESS] Device Info Parsed -> Manufacturer: $manufacturer | Model: $model | Android: $androidVersion", LogLineType.SYSTEM)
    }

    override fun onCleared() {

        super.onCleared()
        terminalSession.close()
    }
}
