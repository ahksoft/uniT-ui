package ahk.unitool.gsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.ui.screens.BottomTerminalDock
import ahk.unitool.gsm.ui.screens.ConnectScreen
import ahk.unitool.gsm.ui.screens.FlashScreen
import ahk.unitool.gsm.ui.screens.PartitionScreen
import ahk.unitool.gsm.ui.screens.ServiceScreen
import ahk.unitool.gsm.ui.screens.TerminalSheet
import ahk.unitool.gsm.ui.screens.ToolsScreen
import ahk.unitool.gsm.ui.screens.TopSlidingLogScreen
import ahk.unitool.gsm.ui.theme.MyApplicationTheme
import ahk.unitool.gsm.ui.theme.TerminalCyan
import ahk.unitool.gsm.ui.theme.TerminalGreen
import ahk.unitool.gsm.ui.theme.TerminalRed
import ahk.unitool.gsm.ui.theme.TerminalYellow
import ahk.unitool.gsm.viewmodel.UniToolViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: UniToolViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_NOTIFICATION_CODE
            )
        }
    }

    companion object {
        private const val PERMISSION_NOTIFICATION_CODE = 881

        fun newInstance(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }
}

enum class NavigationTab(val title: String, val icon: ImageVector) {
    CONNECT("Connect", Icons.Default.Cable),
    SERVICE("Service", Icons.Default.Build),
    PARTITIONS("Partitions", Icons.Default.Layers),
    FLASH("Flash", Icons.Default.Bolt),
    TOOLS("Tools", Icons.Default.Extension)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: UniToolViewModel) {
    var currentTab by remember { mutableIntStateOf(0) }
    var isTopLogOpen by remember { mutableStateOf(false) }
    val isTerminalOpen by viewModel.isTerminalOpen.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    // Automatic slide-down of the top log screen whenever any task or CLI execution begins
    androidx.compose.runtime.LaunchedEffect(isExecuting) {
        if (isExecuting) {
            isTopLogOpen = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "UniTool",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // Device badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedDevice != null) Color(0xFF064E3B) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.rescanUsb() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (selectedDevice != null) TerminalGreen else Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (selectedDevice != null) selectedDevice!!.vidPidHex else "No Device",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedDevice != null) TerminalGreen else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isExecuting) {
                        IconButton(
                            onClick = { viewModel.stopExecution() },
                            modifier = Modifier.testTag("appbar_stop_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Process",
                                tint = TerminalRed
                            )
                        }
                    }

                    // Toggle Top Log Screen
                    IconButton(
                        onClick = { isTopLogOpen = !isTopLogOpen },
                        modifier = Modifier.testTag("appbar_log_btn")
                    ) {
                        BadgedBox(
                            badge = {
                                if (isExecuting) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(TerminalYellow)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Toggle Log Screen",
                                tint = if (isTopLogOpen) TerminalCyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Docked real-time scrollable terminal output area at very bottom
                BottomTerminalDock(
                    viewModel = viewModel,
                    onExpandTopLog = { isTopLogOpen = true }
                )

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    NavigationTab.values().forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = currentTab == index,
                            onClick = { currentTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title, fontSize = 11.sp) },
                            modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!isTerminalOpen && !isTopLogOpen) {
                FloatingActionButton(
                    onClick = { viewModel.toggleTerminal(true) },
                    containerColor = TerminalCyan,
                    contentColor = Color.Black,
                    shape = CircleShape,
                    modifier = Modifier.testTag("fab_terminal")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = "Open Interactive CLI")
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Screen Switcher positioned below top sliding log when open
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (isTopLogOpen) 350.dp else 0.dp)
            ) {
                when (currentTab) {
                    0 -> ConnectScreen(viewModel = viewModel)
                    1 -> ServiceScreen(viewModel = viewModel)
                    2 -> PartitionScreen(viewModel = viewModel)
                    3 -> FlashScreen(viewModel = viewModel)
                    4 -> ToolsScreen(viewModel = viewModel)
                }
            }

            // Top-Sliding Real-Time Log Screen
            // Slides down from top edge to slightly above middle of screen when any task runs
            // Dismissible with swipe-up gesture or close button
            TopSlidingLogScreen(
                viewModel = viewModel,
                isVisible = isTopLogOpen,
                onClose = { isTopLogOpen = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )


            // Fullscreen Interactive CLI Terminal Sheet (when opened by FAB or command prompt)
            AnimatedVisibility(
                visible = isTerminalOpen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.fillMaxSize()
            ) {
                TerminalSheet(
                    viewModel = viewModel,
                    onClose = { viewModel.toggleTerminal(false) }
                )
            }
        }
    }
}
