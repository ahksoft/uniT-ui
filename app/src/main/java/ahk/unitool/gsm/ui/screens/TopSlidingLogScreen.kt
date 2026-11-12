package ahk.unitool.gsm.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.model.LogLineType
import ahk.unitool.gsm.model.TerminalLine
import ahk.unitool.gsm.ui.theme.TerminalBackground
import ahk.unitool.gsm.ui.theme.TerminalCyan
import ahk.unitool.gsm.ui.theme.TerminalGreen
import ahk.unitool.gsm.ui.theme.TerminalRed
import ahk.unitool.gsm.ui.theme.TerminalYellow
import ahk.unitool.gsm.viewmodel.UniToolViewModel

/**
 * Top-sliding terminal screen that slides down from top edge to slightly above middle of the screen.
 * Triggers automatically when executing any task/command.
 * After task is finished, it can be closed by a simple swipe up gesture or close button.
 */
@Composable
fun TopSlidingLogScreen(
    viewModel: UniToolViewModel,
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by viewModel.terminalLines.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val currentCommand by viewModel.currentCommand.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Automatically autoscroll to the very bottom on every incoming log line
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    // Infinite pulsing animation for running badge
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Takes approx 58% of screen height (slightly upper from middle of screen)
            val dropHeight = (maxHeight * 0.58f).coerceIn(340.dp, 580.dp)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dropHeight)
                    .shadow(16.dp, shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .testTag("top_sliding_log_screen")
                    // Handle swipe-up gesture anywhere on the sheet to dismiss
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -18f) {
                                onClose()
                            }
                        }
                    },
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                color = TerminalBackground,
                border = BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF0F172A), Color(0xFF0A0F1D))
                                )
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Status Indicator & Title
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isExecuting) TerminalYellow.copy(alpha = pulseAlpha)
                                        else TerminalGreen
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isExecuting) "TASK RUNNING" else "EXECUTION LOG",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 12.sp,
                                        letterSpacing = 0.5.sp,
                                        color = if (isExecuting) TerminalYellow else TerminalCyan
                                    )
                                    if (isExecuting) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                            color = TerminalYellow
                                        )
                                    }
                                }
                                if (currentCommand != null) {
                                    Text(
                                        text = "$ ${currentCommand!!}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8),
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        // Right: Actions (Stop, Copy, Clear, Close)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isExecuting) {
                                IconButton(
                                    onClick = { viewModel.stopExecution() },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("top_log_stop_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop Active Process",
                                        tint = TerminalRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    val allText = lines.joinToString("\n") { it.text }
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Terminal Logs", allText))
                                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("top_log_copy_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Logs",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { viewModel.clearTerminal() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("top_log_clear_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = "Clear Logs",
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("top_log_close_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Log Screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Linear Progress Indicator & Status tracking active flashing task
                    val flashProgress by viewModel.flashProgress.collectAsState()
                    val flashStatus by viewModel.flashStatus.collectAsState()
                    if (isExecuting || flashProgress > 0f) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = flashStatus,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TerminalCyan,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${(flashProgress * 100).toInt()}%",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TerminalYellow
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { flashProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = TerminalCyan,
                                trackColor = Color(0xFF1E293B)
                            )
                        }
                    }

                    // Middle: Real-Time Scrollable Log Console
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF060911))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (lines.isEmpty()) {
                            Text(
                                text = "Awaiting task execution or CLI command...",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF475569),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(lines) { line ->
                                    TerminalLineRow(line)
                                }
                            }
                        }
                    }

                    // Bottom: Swipe-Up Handle & Quick Action Footer
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClose() }
                            .testTag("top_log_swipe_handle"),
                        color = Color(0xFF0F172A)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // Drag handle pill
                            Box(
                                modifier = Modifier
                                    .size(width = 46.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF475569))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "SWIPE UP TO CLOSE",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.8.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single log line rendered inside the top-sliding terminal console.
 * Colors are chosen based on the type of terminal output so errors, prompts,
 * system messages and regular output are easy to distinguish at a glance.
 */
@Composable
private fun TerminalLineRow(line: TerminalLine) {
    val color = when (line.type) {
        LogLineType.INPUT -> TerminalCyan
        LogLineType.OUTPUT -> Color(0xFFCBD5E1)
        LogLineType.ERROR -> TerminalRed
        LogLineType.SYSTEM -> TerminalYellow
        LogLineType.PROMPT -> TerminalGreen
    }

    Text(
        text = line.text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = color
    )
}

/**
 * Bottom docked real-time terminal bar placed at the very bottom of the screen.
 * Displays live command execution status, latest output snippet, and a button to slide down
 * the full top log viewer.
 */
@Composable
fun BottomTerminalDock(
    viewModel: UniToolViewModel,
    onExpandTopLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by viewModel.terminalLines.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val currentCommand by viewModel.currentCommand.collectAsState()
    val lastLine = lines.lastOrNull()?.text ?: "Ready - Waiting for task/command"

    // Infinite pulsing animation for running badge
    val transition = rememberInfiniteTransition(label = "dockPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dockPulseAlpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandTopLog() }
            .testTag("bottom_terminal_dock"),
        color = Color(0xFF090D16),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Status Pill & Scrolling text ticker
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isExecuting) Color(0xFF422006) else Color(0xFF064E3B),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isExecuting) TerminalYellow.copy(alpha = pulseAlpha)
                                    else TerminalGreen
                                )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isExecuting) "RUNNING" else "IDLE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            color = if (isExecuting) TerminalYellow else TerminalGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Scrollable latest command / output snippet
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isExecuting && currentCommand != null) {
                            "$ ${currentCommand!!}"
                        } else {
                            lastLine
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (isExecuting) TerminalCyan else Color(0xFFCBD5E1),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Quick Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isExecuting) {
                    IconButton(
                        onClick = { viewModel.stopExecution() },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("dock_stop_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Command",
                            tint = TerminalRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Expand button to slide down the top log screen
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onExpandTopLog() }
                        .testTag("dock_expand_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand Log Screen",
                            tint = TerminalCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LOGS",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TerminalCyan
                        )
                    }
                }
            }
        }
    }
}
