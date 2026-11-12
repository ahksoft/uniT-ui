package ahk.unitool.gsm.jni

import android.content.Context
import ahk.unitool.gsm.model.MtkEngineVersion
import com.unitool.terminal.NativeTools
import com.unitool.terminal.TerminalOutput
import com.unitool.terminal.TerminalSession
import java.io.File

/**
 * Bridge between the Compose task screens and the core UniTool CLI binaries.
 *
 * The command lines built here mirror what a user would type into the core
 * terminal:
 *
 *  * `adb`, `fastboot`, `heimdall`, `qdl`, `mtk`/`umbrage` are the native
 *    executables from `jniLibs/arm64-v8a`.
 *  * `mtk` is the core MediaTek engine (`libumbrage_cli_exec.so`); the
 *    unitool-specific Java engine is available as `mtkclient`.
 *
 * All tools talk to authorized Android USB devices through the process-wide
 * `libusb_bridge` broker started by `TerminalRuntime`.
 */
class NativeFlashingBridge(
    private val context: Context,
    private val session: TerminalSession
) {

    /**
     * Checks if a native tool binary is installed and executable in nativeLibraryDir.
     */
    fun isToolAvailable(name: String): Boolean {
        val tool = NativeTools.find(name) ?: return false
        return NativeTools.isAvailable(context, tool)
    }

    /**
     * Lists all native CLI binaries present in the package.
     */
    fun availableTools(): List<String> {
        return NativeTools.tools().map { it.name }
    }

    /**
     * Resolves absolute file path of an installed native binary.
     */
    fun getToolExecutable(name: String): File? {
        val tool = NativeTools.find(name) ?: return null
        return NativeTools.executable(context, tool)
    }

    /**
     * Direct execution of MediaTek commands.
     *
     * v1 is the unitool Java engine (`mtkclient`). v2 is the core native
     * engine (`mtk`, backed by `libumbrage_cli_exec.so`).
     */
    fun executeMtk(
        engine: MtkEngineVersion,
        subcommand: String,
        args: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit = {}
    ) {
        val cmdBuilder = StringBuilder()
        cmdBuilder.append(
            if (engine == MtkEngineVersion.MTK_V1_MTKCLIENT) "mtkclient " else "mtk "
        ).append(subcommand)

        for (arg in args) {
            cmdBuilder.append(' ').append(arg)
        }
        options.forEach { (k, v) ->
            when {
                engine == MtkEngineVersion.MTK_V2_ANTUMBRA && k == "d" -> {
                    cmdBuilder.append(" --da ").append(v)
                }
                engine == MtkEngineVersion.MTK_V2_ANTUMBRA && k == "da" -> {
                    cmdBuilder.append(" --da ").append(v)
                }
                engine == MtkEngineVersion.MTK_V2_ANTUMBRA &&
                    (k == "usb-backend" || k == "backend") -> {
                    cmdBuilder.append(" --backend ").append(v)
                }
                v.equals("true", ignoreCase = true) -> {
                    cmdBuilder.append(" --").append(k)
                }
                v.isNotEmpty() -> {
                    cmdBuilder.append(" --").append(k).append(' ').append(v)
                }
            }
        }

        executeRaw(cmdBuilder.toString(), onOutput, onComplete)
    }

    /**
     * Executes Android Fastboot CLI command (`fastboot <subcommand> ...`).
     */
    fun executeFastboot(
        subcommand: String,
        args: List<String> = emptyList(),
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit = {}
    ) {
        val cmd = buildString {
            append("fastboot ").append(subcommand)
            for (arg in args) append(' ').append(arg)
        }
        executeRaw(cmd, onOutput, onComplete)
    }

    /**
     * Executes the core Heimdall RPC client (`heimdall <command> ...`).
     *
     * The client forwards the arguments to the in-app Heimdall bridge, which
     * owns the authorized USB descriptor.
     */
    fun executeHeimdall(
        subcommand: String,
        args: List<String> = emptyList(),
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit = {}
    ) {
        val cmd = buildString {
            append("heimdall ").append(subcommand)
            for (arg in args) append(' ').append(arg)
        }
        executeRaw(cmd, onOutput, onComplete)
    }

    /**
     * Executes the core Qualcomm EDL tool.
     *
     * qdl takes a firehose programmer followed by one or more XML files:
     * `qdl [options] <prog.mbn> (<program-xml> | <patch-xml> | <read-xml>)...`.
     * It detects eMMC/UFS itself; there is no `--storage` option.
     */
    fun executeQdl(
        programmerElf: String,
        xmlFiles: List<String>,
        debug: Boolean = false,
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit = {}
    ) {
        val cmd = buildString {
            append("qdl ")
            if (debug) append("--debug ")
            append(programmerElf)
            for (xml in xmlFiles) append(' ').append(xml)
        }
        executeRaw(cmd, onOutput, onComplete)
    }

    /**
     * Executes a raw CLI command through the terminal session and the USB broker.
     */
    fun executeRaw(
        commandLine: String,
        onOutput: (String) -> Unit,
        onComplete: (Int) -> Unit = {}
    ) {
        val output = object : TerminalOutput {
            override fun print(text: String?) {
                text?.let { onOutput(it) }
            }

            override fun println(text: String?) {
                text?.let { onOutput(it + "\n") }
            }

            override fun println() {
                onOutput("\n")
            }

            override fun error(text: String?) {
                text?.let { onOutput("[ERROR] $it\n") }
            }

            override fun clear() {
                onOutput("[CLEAR]\n")
            }
        }

        session.execute(commandLine, output) {
            onComplete(0)
        }
    }

    /**
     * Immediately terminates the active flashing process.
     */
    fun stopActiveProcess() {
        session.stopRunningProcess()
    }
}
