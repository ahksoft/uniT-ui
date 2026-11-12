package ahk.unitool.gsm.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ahk.unitool.gsm.terminal.TerminalController
import ahk.unitool.gsm.terminal.view.TerminalView
import ahk.unitool.gsm.ui.theme.TerminalBackground
import ahk.unitool.gsm.ui.theme.TerminalCyan
import ahk.unitool.gsm.ui.theme.TerminalGreen
import ahk.unitool.gsm.ui.theme.TerminalRed
import ahk.unitool.gsm.ui.theme.TerminalYellow
import ahk.unitool.gsm.viewmodel.UniToolViewModel

/**
 * Full-screen interactive terminal backed by the real PTY emulator
 * (`:terminalview`) and the terminal bootstrap installed by
 * `ahk.unitool.gsm.terminal.NativeToolchain`.
 */
@Composable
fun TerminalSheet(
    viewModel: UniToolViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val controller = remember { TerminalController(context) }
    val isReady = controller.isReady
    val sessionFinished = controller.isSessionFinished
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }

    LaunchedEffect(isReady) {
        if (isReady) controller.requestFocusAndKeyboard()
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("terminal_container"),
        color = TerminalBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalHeader(
                isReady = isReady,
                sessionFinished = sessionFinished,
                onCopy = {
                    val text = controller.transcript()
                    if (text.isBlank()) {
                        Toast.makeText(context, "Terminal is empty", Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                        Toast.makeText(context, "Terminal transcript copied", Toast.LENGTH_SHORT)
                            .show()
                    }
                },
                onClear = { controller.clearDisplay() },
                onRestart = { controller.restart() },
                onClose = onClose
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("terminal_view"),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            controller.attachView(this)
                        }
                    }
                )

                if (!isReady) {
                    TerminalStatusOverlay(
                        sessionFinished = sessionFinished,
                        onRestart = { controller.restart() }
                    )
                }
            }

            QuickCommandRow(
                onCommand = { controller.sendCommand(it) }
            )

            TerminalExtraKeys(
                ctrlActive = ctrlActive,
                altActive = altActive,
                onCtrl = { ctrlActive = !ctrlActive },
                onAlt = { altActive = !altActive },
                onKey = { key ->
                    val sequence = extraKeySequence(key, ctrlActive, altActive)
                    if (sequence != null) controller.write(sequence)
                    if (key != "CTRL" && key != "ALT") {
                        ctrlActive = false
                        altActive = false
                    }
                }
            )
        }
    }
}

@Composable
private fun TerminalHeader(
    isReady: Boolean,
    sessionFinished: Boolean,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            sessionFinished -> TerminalRed
                            isReady -> TerminalGreen
                            else -> TerminalYellow
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "TERMINAL",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = TerminalCyan
            )
            if (!isReady && !sessionFinished) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = TerminalYellow
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.testTag("terminal_copy_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy transcript",
                    tint = Color.LightGray
                )
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.testTag("terminal_clear_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear screen",
                    tint = Color.LightGray
                )
            }
            IconButton(
                onClick = onRestart,
                modifier = Modifier.testTag("terminal_restart_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Restart shell",
                    tint = Color.LightGray
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("terminal_close_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close terminal",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun TerminalStatusOverlay(
    sessionFinished: Boolean,
    onRestart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (sessionFinished) {
                    "Shell session ended"
                } else {
                    "Starting shell…"
                },
                color = if (sessionFinished) TerminalRed else TerminalYellow,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            if (sessionFinished) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Tap to restart",
                    color = TerminalCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRestart() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private val quickCommands = listOf(
    "adb devices -l",
    "fastboot devices",
    "heimdall detect",
    "qdl --help",
    "mtk --help",
    "unitool-usb-probe",
    "unitool-libusb-probe",
    "usb",
    "help",
    "clear"
)

@Composable
private fun QuickCommandRow(onCommand: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        quickCommands.forEach { cmd ->
            AssistChip(
                onClick = { onCommand(cmd) },
                label = {
                    Text(
                        text = cmd,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xFF1E293B)
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = Color(0xFF334155)
                )
            )
        }
    }
}

@Composable
private fun TerminalExtraKeys(
    ctrlActive: Boolean,
    altActive: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0B132B))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("ESC", "/", "-", "UP", "HOME", "END", "PGUP").forEach { key ->
                ExtraKeyButton(label = key, modifier = Modifier.weight(1f)) { onKey(key) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ExtraKeyButton(
                label = if (ctrlActive) "CTRL*" else "CTRL",
                active = ctrlActive,
                modifier = Modifier.weight(1f),
                onClick = onCtrl
            )
            ExtraKeyButton(label = "TAB", modifier = Modifier.weight(1f)) { onKey("TAB") }
            ExtraKeyButton(
                label = if (altActive) "ALT*" else "ALT",
                active = altActive,
                modifier = Modifier.weight(1f),
                onClick = onAlt
            )
            listOf("LEFT", "DOWN", "RIGHT", "PGDN").forEach { key ->
                ExtraKeyButton(label = key, modifier = Modifier.weight(1f)) { onKey(key) }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) TerminalCyan else Color(0xFF1E293B))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.Black else Color.White
        )
    }
}

private fun extraKeySequence(key: String, ctrl: Boolean, alt: Boolean): String? {
    val modifier = when {
        ctrl && alt -> 7
        ctrl -> 5
        alt -> 3
        else -> 0
    }
    return when (key) {
        "ESC" -> "\u001b"
        "/" -> if (ctrl) "\u001f" else "/"
        "-" -> if (ctrl) "\u001f" else "-"
        "TAB" -> when {
            ctrl && alt -> "\u001b[9;7u"
            ctrl -> "\u001b[9;5u"
            alt -> "\u001b\t"
            else -> "\t"
        }
        "UP" -> if (modifier == 0) "\u001b[A" else "\u001b[1;${modifier}A"
        "DOWN" -> if (modifier == 0) "\u001b[B" else "\u001b[1;${modifier}B"
        "RIGHT" -> if (modifier == 0) "\u001b[C" else "\u001b[1;${modifier}C"
        "LEFT" -> if (modifier == 0) "\u001b[D" else "\u001b[1;${modifier}D"
        "HOME" -> if (modifier == 0) "\u001b[H" else "\u001b[1;${modifier}H"
        "END" -> if (modifier == 0) "\u001b[F" else "\u001b[1;${modifier}F"
        "PGUP" -> if (modifier == 0) "\u001b[5~" else "\u001b[5;${modifier}~"
        "PGDN" -> if (modifier == 0) "\u001b[6~" else "\u001b[6;${modifier}~"
        else -> null
    }
}
