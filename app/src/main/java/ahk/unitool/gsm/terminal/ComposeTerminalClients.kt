package ahk.unitool.gsm.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import ahk.unitool.gsm.terminal.view.TerminalView
import ahk.unitool.gsm.terminal.view.TerminalViewClient
import ahk.unitool.gsm.utils.AndroidUtilities

/**
 * Session callbacks for the Compose-hosted terminal. All callbacks arrive on
 * the main thread, so the attached [TerminalView] can be updated directly.
 */
class ComposeTerminalSessionClient(
    private val context: Context
) : TerminalSessionClientBase() {

    @Volatile
    var view: TerminalView? = null

    @Volatile
    var onSessionFinished: ((TerminalSession) -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(updatedSession: TerminalSession) {
        // The Compose header shows a static title; the shell title is not used.
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished?.invoke(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) view?.mEmulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onColorsChanged(changedSession: TerminalSession) {
        view?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.setTerminalCursorBlinkerState(state, false)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
        Log.i(TAG, "Shell started (pid=$pid)")
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }

    private companion object {
        const val TAG = "TerminalSession"
    }
}

/**
 * View callbacks for the Compose-hosted terminal: pinch to change the font
 * size, tap to show the keyboard and keep the cursor blinking.
 */
class ComposeTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?
) : TerminalViewClientBase() {

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            TerminalSettingsHelper.changeFontSize(context, scale > 1f)
            viewProvider()?.setTextSize(TerminalSettingsHelper.getFontSize(context))
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = viewProvider() ?: return
        val emulator = view.mEmulator
        if (emulator == null ||
            (!emulator.isMouseTrackingActive && !e.isFromSource(InputDevice.SOURCE_MOUSE))
        ) {
            view.requestFocus()
            AndroidUtilities.showSoftKeyboard(view)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
        false

    override fun onEmulatorSet() {
        val view = viewProvider() ?: return
        if (view.setTerminalCursorBlinkerRate(0)) {
            view.setTerminalCursorBlinkerState(true, true)
        }
    }

    override fun logError(tag: String, message: String) { Log.e(tag, message) }

    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }

    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }

    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }

    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }
}
