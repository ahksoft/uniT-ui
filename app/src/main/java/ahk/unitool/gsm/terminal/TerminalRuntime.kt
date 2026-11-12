package ahk.unitool.gsm.terminal

import android.content.Context
import android.util.Log
import ahk.unitool.gsm.usb.UsbDeviceManager

/**
 * Process-wide owner of the native terminal services.
 *
 * The interactive terminal needs two things that outlive a single Compose
 * screen:
 *
 *  * a [UsbDeviceManager] that owns Android USB permissions and the
 *    SCM_RIGHTS descriptor broker used by the packaged `adb`, `fastboot` and
 *    `heimdall` binaries, and
 *  * a [HeimdallCommandServer] so the `heimdall` RPC client can execute in the
 *    app process, which is the only process holding the authorized USB fd.
 *
 * Both are started lazily when the terminal is first opened. The Java USB
 * stack in `com.unitool.usb` remains the owner of permission dialogs, so the
 * native manager is created with `autoRequestPermissions = false`.
 */
object TerminalRuntime {
    private const val TAG = "TerminalRuntime"

    @Volatile
    private var manager: UsbDeviceManager? = null

    @Volatile
    private var heimdallServer: HeimdallCommandServer? = null

    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        val existing = manager
        if (existing != null) {
            existing.rescan()
            return
        }

        val usbManager = UsbDeviceManager(appContext, autoRequestPermissions = false)
        usbManager.register()
        manager = usbManager

        heimdallServer = HeimdallCommandServer { arguments, workingDirectory ->
            usbManager.runHeimdall(arguments, workingDirectory)
        }.also { it.start() }

        Log.i(TAG, "Terminal USB broker and Heimdall RPC server started")
    }

    fun usbManager(): UsbDeviceManager? = manager

    /** Opens any device whose permission was granted by the host UI. */
    fun rescan() {
        manager?.rescan()
    }

    @Synchronized
    fun stop() {
        heimdallServer?.close()
        heimdallServer = null
        manager?.close()
        manager = null
        Log.i(TAG, "Terminal USB broker and Heimdall RPC server stopped")
    }
}
