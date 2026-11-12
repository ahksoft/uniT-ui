package ahk.unitool.gsm.terminal

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ahk.unitool.gsm.MainActivity
import ahk.unitool.gsm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class TerminalService : Service() {
    private val binder: IBinder = LocalBinder()

    private var terminalSession: TerminalSession? = null
    private var terminalSessionClient: TerminalSessionClient? = null

    private var scope: CoroutineScope = MainScope()
    private val toolchainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolchain by lazy { NativeToolchain.install(this) }

    override fun onCreate() {
        super.onCreate()
        runStartForeground()
        // Extracting the Termux bootstrap takes a while. Warm it up off the
        // main thread so opening the terminal does not block the UI.
        toolchainScope.launch {
            try {
                toolchain
            } catch (e: Throwable) {
                Log.e(TAG, "Terminal bootstrap installation failed", e)
            }
        }
    }

    override fun onDestroy() {
        toolchainScope.cancel()
        runStopForeground()
        super.onDestroy()
    }

    override fun onBind(p1: Intent): IBinder = binder

    override fun onUnbind(p1: Intent): Boolean {
        if (terminalSessionClient != null) unsetTerminalSessionClient()
        return false
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        runStartForeground()
        val action: String? = intent.action
        if (ACTION_STOP_SERVICE == action) actionStopService()
        return START_NOT_STICKY
    }

    private fun runStartForeground() {
        setupNotificationChannel()
        try {
            startForeground(NOTIFICATION_CHANNEL_ID, buildNotification())
        } catch (e: Throwable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                Log.e(TAG, "start service in Foreground failed: ${e.message}")
            }
        }
    }

    private fun runStopForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    private fun requestStopService() {
        runStopForeground()
        stopSelf()
    }

    fun actionStopService() {
        terminalSession?.finishIfRunning()
        requestStopService()
    }

    private fun setupNotificationChannel() {
        val channel = NotificationChannelCompat.Builder(
            "" + NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(getString(R.string.notification_terminal_session))
            .setDescription(getString(R.string.notification_terminal_session_des))
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TerminalService::class.java).setAction(ACTION_STOP_SERVICE)
        val contentIntent = MainActivity.newInstance(this)
        return NotificationCompat.Builder(this, "" + NOTIFICATION_CHANNEL_ID)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setContentTitle(getString(R.string.notification_terminal_session))
            .setShowWhen(false)
            .setSilent(true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_unitool)
            .setContentText(getString(R.string.notification_running))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.notification_action_stop),
                PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
    }

    @Synchronized
    fun getTerminalSession(): TerminalSession? = terminalSession

    @Synchronized
    fun getTerminalSessionClient(): TerminalSessionClient? = terminalSessionClient

    @Synchronized
    fun setTerminalSessionClient(client: TerminalSessionClient?) {
        terminalSessionClient = client
        if (client != null) terminalSession?.updateTerminalSessionClient(client)
    }

    @Synchronized
    fun unsetTerminalSessionClient() {
        // Keep the client installed on the live session: TerminalSession uses it
        // for logging and must never hold a null reference. The client itself
        // detaches its view when the Compose terminal is disposed.
        terminalSessionClient = null
    }

    /** Finish and forget the current session so the next attach starts a fresh shell. */
    @Synchronized
    fun resetTerminalSession() {
        val session = terminalSession
        terminalSession = null
        session?.finishIfRunning()
    }

    @Synchronized
    fun getOrCreateTerminalSession(): TerminalSession {
        if (terminalSession?.isRunning == false) {
            terminalSession = null
        }
        if (terminalSession == null) {
            val env = toolchain
            val envs = arrayOf(
                "TERM=screen",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "HOME=${env.home}",
                "TMPDIR=${env.tmp}",
                "ADB=${env.adb}",
                // The packaged ADB binary includes both Linux usbfs and libusb
                // backends. Android UsbManager-authorized devices are exposed
                // through libusb_bridge, not /dev/bus/usb, so force libusb.
                "ADB_LIBUSB=1",
                "PREFIX=${env.prefix}",
                "TERMUX_PREFIX=${env.prefix}",
                "DPKG_ROOT=${env.prefix}",
                "DPKG_ADMINDIR=${env.prefix}/var/lib/dpkg",
                "APT_CONFIG=${env.prefix}/etc/apt/apt.conf",
                "DEBIAN_FRONTEND=noninteractive",
                "APT_LISTCHANGES_FRONTEND=none",
                "SHELL=${env.shell}",
                "PATH=${env.bin}:/system/bin:/system/xbin",
                // Git's Android build retains Termux's compiled-in defaults.
                // Export app-private helper/template paths directly so Git
                // works even when the interactive shell does not source profile.
                "GIT_EXEC_PATH=${env.prefix}/libexec/git-core",
                "GIT_TEMPLATE_DIR=${env.prefix}/share/git-core/templates",
                "GIT_CONFIG_NOSYSTEM=1",
                // Git, curl, and OpenSSL must use UniTool's bundled CA store,
                // not the Termux path compiled into upstream Android binaries.
                "SSL_CERT_FILE=${env.prefix}/etc/tls/cert.pem",
                "CURL_CA_BUNDLE=${env.prefix}/etc/tls/cert.pem",
                "GIT_SSL_CAINFO=${env.prefix}/etc/tls/cert.pem",
                "PYTHONHOME=${env.prefix}",
                "PYTHONNOUSERSITE=1",
                "PYTHONPATH=${env.prefix}/lib/python3.14/site-packages:${env.prefix}/lib/python/site-packages",
                "LD_LIBRARY_PATH=${env.lib}",
                // PyUSB must load the exact broker-patched libusb shipped by UniTool.
                // Keep the absolute APK native-library path; do not resolve a system libusb.
                "UNITOOL_LIBUSB_PATH=${env.libusb}",
                "UNITOOL_LIBUSB_BRIDGE_PATH=${File(env.libusb).parent}/libusb_bridge.so",
                "PYUSB_LIBUSB_PATH=${env.libusb}",
                "ENV=${env.prefix}/etc/profile",
                "BASH_ENV=${env.prefix}/etc/profile",
                "INPUTRC=${env.prefix}/etc/inputrc",
                "MKE2FS_CONFIG=${env.prefix}/bin/mke2fs.conf",
                "TERMINFO_DIRS=${env.prefix}/share/terminfo:/system/usr/share/terminfo"
            )
            Log.i(TAG, "Native tools available: ${env.availableCommands.sorted().joinToString()}")
            if (env.missingLibraries.isNotEmpty()) {
                Log.w(TAG, "Native libraries not packaged: ${env.missingLibraries.sorted().joinToString()}")
            }
            terminalSession = TerminalSession(
                env.shell,
                "/",
                arrayOf(),
                envs,
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                terminalSessionClient ?: TerminalSessionClientBase()
            )
        }
        return terminalSession as TerminalSession
    }

    inner class LocalBinder : Binder() {
        val service = this@TerminalService
    }

    companion object {
        private const val TAG = "TerminalService"
        private const val NOTIFICATION_CHANNEL_ID = 882
        private const val ACTION_STOP_SERVICE = "ahk.unitool.gsm.STOP_SERVICE"
    }
}
