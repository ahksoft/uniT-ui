package ahk.unitool.gsm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.viewmodel.UniToolViewModel

@Composable
fun FlashScreen(
    viewModel: UniToolViewModel,
    modifier: Modifier = Modifier
) {
    val isExecuting by viewModel.isExecuting.collectAsState()
    val flashProgress by viewModel.flashProgress.collectAsState()
    val flashStatus by viewModel.flashStatus.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("MTK Scatter", "Qualcomm EDL", "Fastboot Flash", "Heimdall")

    // Flashing input fields
    var scatterPath by remember { mutableStateOf("MT6789_Android_scatter.xml") }
    var mtkDa by remember { mutableStateOf("MTK_DA_V6.bin") }
    var firehosePath by remember { mutableStateOf("prog_firehose_ddr.elf") }
    var rawprogramPath by remember { mutableStateOf("rawprogram0.xml") }
    var patchPath by remember { mutableStateOf("patch0.xml") }
    var fastbootPartition by remember { mutableStateOf("boot") }
    var fastbootImage by remember { mutableStateOf("boot.img") }
    var heimdallPartition by remember { mutableStateOf("BOOT") }
    var heimdallImage by remember { mutableStateOf("boot.img") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tab Row
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex,
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) }
                )
            }
        }

        // Safety Advisory Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Ensure device has at least 50% battery before flashing. Never disconnect USB cable while writing partitions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Dynamic Form based on Tab
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTabIndex) {
                    0 -> { // MTK Scatter
                        Text(
                            text = "MEDIATEK SCATTER FLASHER",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = scatterPath,
                            onValueChange = { scatterPath = it },
                            label = { Text("Scatter File (.txt / .xml)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = mtkDa,
                            onValueChange = { mtkDa = it },
                            label = { Text("DA / Loader file") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = {
                                viewModel.executeCommand(
                                    "mtk flash-scatter ${viewModel.shellQuote(scatterPath)}" +
                                        viewModel.mtkDaOption(mtkDa)
                                )
                            },
                            enabled = !isExecuting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("btn_flash_scatter"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("FLASH SCATTER FIRMWARE", fontWeight = FontWeight.Bold)
                        }
                    }

                    1 -> { // Qualcomm EDL
                        Text(
                            text = "QUALCOMM FIREHOSE EDL FLASHER",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = firehosePath,
                            onValueChange = { firehosePath = it },
                            label = { Text("Programmer ELF (prog_firehose_*.elf)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = rawprogramPath,
                            onValueChange = { rawprogramPath = it },
                            label = { Text("Rawprogram XML") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = patchPath,
                            onValueChange = { patchPath = it },
                            label = { Text("Patch XML") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                viewModel.executeCommand("qdl $firehosePath $rawprogramPath $patchPath")
                            },
                            enabled = !isExecuting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("START EDL FIREHOSE FLASH", fontWeight = FontWeight.Bold)
                        }
                    }

                    2 -> { // Fastboot Flash
                        Text(
                            text = "ANDROID FASTBOOT PARTITION FLASHER",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = fastbootPartition,
                            onValueChange = { fastbootPartition = it },
                            label = { Text("Target Partition (e.g. boot, recovery, super)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = fastbootImage,
                            onValueChange = { fastbootImage = it },
                            label = { Text("Image File Path (.img)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.runFastbootFlash(fastbootPartition, fastbootImage)
                                },
                                enabled = !isExecuting,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("FLASH IMAGE", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { viewModel.runFastbootReboot() },
                                enabled = !isExecuting,
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("REBOOT")
                            }
                        }
                    }

                    3 -> { // Heimdall
                        Text(
                            text = "SAMSUNG HEIMDALL FLASHER",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = heimdallPartition,
                            onValueChange = { heimdallPartition = it },
                            label = { Text("Partition name (BOOT, RECOVERY, SYSTEM, ...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = heimdallImage,
                            onValueChange = { heimdallImage = it },
                            label = { Text("Image file (.img)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                viewModel.executeCommand(
                                    "heimdall flash --$heimdallPartition $heimdallImage"
                                )
                            },
                            enabled = !isExecuting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("FLASH WITH HEIMDALL", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Live Execution / Progress Indicator
        if (isExecuting) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "FLASH OPERATION IN PROGRESS",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Streaming live CLI binary output to terminal...",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
