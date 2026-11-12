package ahk.unitool.gsm.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import ahk.unitool.gsm.terminal.view.TerminalView
import ahk.unitool.gsm.utils.AndroidUtilities

/**
 * Binds the Compose terminal UI to [TerminalService] and keeps the
 * [TerminalView] attached to the live PTY session.
 *
 * A controller is created when the terminal sheet is shown and disposed when
 * it is closed. The foreground service and its shell keep running, so
 * reopening the sheet reattaches to the same session.
 */
class TerminalController(context: Context) {

    private val appContext = context.applicationContext

    private var service: TerminalService? = null
    private var terminalView: TerminalView? = null
    private var bound = false

    /** True once the service is connected and a shell session is attached. */
    var isReady by mutableStateOf(false)
        private set

    /** True after the shell exits (for example after typing `exit`). */
    var isSessionFinished by mutableStateOf(false)
        private set

    var title by mutableStateOf("Terminal")
        private set

    private val sessionClient = ComposeTerminalSessionClient(appContext).apply {
        onSessionFinished = {
            isSessionFinished = true
            isReady = false
        }
    }

    private val viewClient = ComposeTerminalViewClient(appContext) { terminalView }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TerminalService.LocalBinder ?: return
            service = localBinder.service
            service?.setTerminalSessionClient(sessionClient)
            attachSession()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isReady = false
        }
    }

    fun start() {
        if (bound) return
        TerminalRuntime.start(appContext)

        val intent = Intent(appContext, TerminalService::class.java)
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start TerminalService", e)
        }
        bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind TerminalService", e)
            false
        }
    }

    fun attachView(view: TerminalView) {
        if (terminalView === view) return
        terminalView = view
        sessionClient.view = view
        view.setTerminalViewClient(viewClient)
        view.setTextSize(TerminalSettingsHelper.getFontSize(appContext))
        view.setTypeface(Typeface.MONOSPACE)
        view.setKeepScreenOn(TerminalSettingsHelper.shouldKeepScreenOn(appContext))
        view.post {
            view.updateSize()
            view.requestFocus()
        }
        attachSession()
    }

    private var attaching = false

    private fun attachSession(forceNew: Boolean = false) {
        val view = terminalView ?: return
        val terminalService = service ?: return

        val current = view.currentSession
        if (!forceNew && current != null) {
            bindSession(view, current)
            return
        }
        if (attaching) return

        // Installing the Termux bootstrap and creating the PTY can take a
        // moment, so keep it off the main thread and show the overlay instead.
        attaching = true
        Thread({
            try {
                if (terminalService.getTerminalSession()?.isRunning == false) {
                    terminalService.resetTerminalSession()
                }
                val session = terminalService.getOrCreateTerminalSession()
                view.post {
                    attaching = false
                    bindSession(view, session)
                }
            } catch (t: Throwable) {
                attaching = false
                Log.e(TAG, "Could not create terminal session", t)
                view.post { isReady = false }
            }
        }, "TerminalSessionInit").start()
    }

    private fun bindSession(view: TerminalView, session: TerminalSession) {
        if (view.currentSession !== session) {
            view.attachSession(session)
            view.onScreenUpdated()
        }
        isSessionFinished = !session.isRunning
        isReady = true
        title = session.title?.takeIf { it.isNotBlank() } ?: "Terminal"
        view.post {
            view.updateSize()
            view.requestFocus()
        }
    }

    fun requestFocusAndKeyboard() {
        val view = terminalView ?: return
        view.requestFocus()
        AndroidUtilities.showSoftKeyboard(view)
    }

    /** Sends raw text (including escape sequences) to the shell. */
    fun write(text: String) {
        val session = service?.getTerminalSession() ?: return
        if (!session.isRunning) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
        terminalView?.requestFocus()
    }

    /** Sends a command followed by Enter. */
    fun sendCommand(command: String) {
        write(command + "\r")
    }

    fun clearScreen() {
        write("clear\r")
    }

    /** Clears the rendered screen without sending input to the running shell. */
    fun clearDisplay() {
        terminalView?.currentSession?.reset()
    }

    fun transcript(): String =
        terminalView?.currentSession?.emulator?.screen?.transcriptText.orEmpty()

    fun restart() {
        isSessionFinished = false
        isReady = false
        service?.resetTerminalSession()
        attachSession(forceNew = true)
    }

    fun stop() {
        sessionClient.view = null
        if (bound) {
            try {
                appContext.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "TerminalService was already unbound", e)
            }
            bound = false
        }
        service?.unsetTerminalSessionClient()
        service = null
        terminalView = null
        isReady = false
    }

    private companion object {
        const val TAG = "TerminalController"
    }
}
