package ahk.unitool.gsm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.viewmodel.UniToolViewModel

/**
 * Task screen for the core UniTool CLI tools.
 *
 * Every button builds the same command line a user would type into the core
 * terminal. The binaries are the ones packaged from unitool-core:
 * `libadb.so`, `libfastboot.so`, `libheimdall_rpc.so`, `libqdl.so`,
 * `libumbrage_cli_exec.so`, `libmake_f2fs.so`, `libmke2fs.so` and the USB
 * probes. Authorized Android USB descriptors reach them through the
 * `libusb_bridge` broker started by `TerminalRuntime`.
 */
@Composable
fun ToolsScreen(
    viewModel: UniToolViewModel,
    modifier: Modifier = Modifier
) {
    val isExecuting by viewModel.isExecuting.collectAsState()

    var adbCommand by remember { mutableStateOf("adb devices -l") }
    var fastbootCommand by remember { mutableStateOf("fastboot devices -l") }
    var heimdallCommand by remember { mutableStateOf("heimdall detect") }

    var qdlProgrammer by remember { mutableStateOf("prog_firehose_ddr.elf") }
    var qdlRawProgram by remember { mutableStateOf("rawprogram0.xml") }
    var qdlPatch by remember { mutableStateOf("patch0.xml") }
    var qdlDebug by remember { mutableStateOf(false) }

    var mtkRawCommand by remember { mutableStateOf("mtk info") }
    var mtkDa by remember { mutableStateOf("MTK_DA_V6.bin") }
    var mtkBackend by remember { mutableStateOf("auto") }
    var mtkPartition by remember { mutableStateOf("boot") }
    var mtkImage by remember { mutableStateOf("boot.img") }
    var mtkScatter by remember { mutableStateOf("MT6768_Android_scatter.xml") }

    var fsImage by remember { mutableStateOf("userdata.img") }
    var fsSize by remember { mutableStateOf("512M") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "CORE CLI TOOLS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "adb, fastboot, heimdall, qdl, mtk/umbrage and the filesystem tools " +
                "run as the same ARM64 binaries used by the core terminal. USB access " +
                "goes through libusb_bridge.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ToolAvailabilityRow(viewModel)

        // ------------------------------------------------------------------
        // ADB
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "ADB",
            description = "Core adb client with ADB_LIBUSB=1. The authorized USB " +
                "descriptor is supplied by libusb_bridge.",
            icon = Icons.Default.Usb
        ) {
            OutlinedTextField(
                value = adbCommand,
                onValueChange = { adbCommand = it },
                label = { Text("adb command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.executeCommand(adbCommand) },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Run") }
                OutlinedButton(
                    onClick = { viewModel.runAdbService("devices") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Devices") }
                OutlinedButton(
                    onClick = { viewModel.runAdbService("device_props") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Props") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runAdbService("reboot_bootloader") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Bootloader") }
                OutlinedButton(
                    onClick = { viewModel.runAdbService("reboot_recovery") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Recovery") }
                OutlinedButton(
                    onClick = { viewModel.runAdbService("reboot_edl") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("EDL") }
            }
        }

        // ------------------------------------------------------------------
        // Fastboot
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "FASTBOOT",
            description = "Core fastboot client. Flash images, read variables and " +
                "change the active slot.",
            icon = Icons.Default.FlashOn
        ) {
            OutlinedTextField(
                value = fastbootCommand,
                onValueChange = { fastbootCommand = it },
                label = { Text("fastboot command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.executeCommand(fastbootCommand) },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Run") }
                OutlinedButton(
                    onClick = { viewModel.runFastbootService("devices") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Devices") }
                OutlinedButton(
                    onClick = { viewModel.runFastbootService("getvar_all") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Getvar") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runFastbootService("unlock_flashing") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Unlock") }
                OutlinedButton(
                    onClick = { viewModel.runFastbootService("set_slot_a") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Slot A") }
                OutlinedButton(
                    onClick = { viewModel.runFastbootService("set_slot_b") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Slot B") }
            }
        }

        // ------------------------------------------------------------------
        // Heimdall
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "HEIMDALL (SAMSUNG)",
            description = "The core heimdall client forwards commands to the in-app " +
                "RPC bridge, which owns the authorized USB descriptor.",
            icon = Icons.Default.Security
        ) {
            OutlinedTextField(
                value = heimdallCommand,
                onValueChange = { heimdallCommand = it },
                label = { Text("heimdall command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.executeCommand(heimdallCommand) },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Run") }
                OutlinedButton(
                    onClick = { viewModel.runHeimdallService("detect") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Detect") }
                OutlinedButton(
                    onClick = { viewModel.runHeimdallService("print_pit") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("PIT") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.runHeimdallService("flash_boot") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Flash boot") }
                OutlinedButton(
                    onClick = { viewModel.runHeimdallService("flash_recovery") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Flash recovery") }
                OutlinedButton(
                    onClick = { viewModel.runHeimdallService("close_pc_screen") },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Close PC") }
            }
        }

        // ------------------------------------------------------------------
        // Qualcomm qdl
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "QUALCOMM QDL (EDL)",
            description = "Core qdl: qdl [options] <prog.mbn> (<program-xml> | " +
                "<patch-xml> | <read-xml>)...  Storage type is detected automatically.",
            icon = Icons.Default.Memory
        ) {
            OutlinedTextField(
                value = qdlProgrammer,
                onValueChange = { qdlProgrammer = it },
                label = { Text("Firehose programmer (.mbn / .elf)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = qdlRawProgram,
                onValueChange = { qdlRawProgram = it },
                label = { Text("rawprogram XML") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = qdlPatch,
                onValueChange = { qdlPatch = it },
                label = { Text("patch XML (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = qdlDebug, onCheckedChange = { qdlDebug = it })
                Text("--debug", fontSize = 13.sp)
            }
            Button(
                onClick = {
                    val xmls = buildList {
                        if (qdlRawProgram.isNotBlank()) add(qdlRawProgram)
                        if (qdlPatch.isNotBlank()) add(qdlPatch)
                    }
                    viewModel.executeCommand(
                        buildString {
                            append("qdl ")
                            if (qdlDebug) append("--debug ")
                            append(qdlProgrammer)
                            for (xml in xmls) append(' ').append(xml)
                        }
                    )
                },
                enabled = !isExecuting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("RUN QDL", fontWeight = FontWeight.Bold) }
        }

        // ------------------------------------------------------------------
        // MediaTek mtk / umbrage
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "MEDIATEK MTK / UMBRAGE",
            description = "Core native MediaTek engine (libumbrage_cli_exec.so). " +
                "Commands: info, partitions, read, write, erase, flash-scatter, " +
                "unlock, lock, slot, reboot.",
            icon = Icons.Default.Build
        ) {
            // Run a command exactly as the CLI accepts it. The engine owns the
            // device-discovery loop, so the phone can be plugged in after Run.
            OutlinedTextField(
                value = mtkRawCommand,
                onValueChange = { mtkRawCommand = it },
                label = { Text("mtk command (run exactly as typed)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { viewModel.executeCommand(mtkRawCommand) },
                enabled = !isExecuting && mtkRawCommand.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("RUN MTK COMMAND", fontWeight = FontWeight.Bold) }
            OutlinedTextField(
                value = mtkDa,
                onValueChange = { mtkDa = it },
                label = { Text("DA / loader file") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Backend", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                listOf("auto", "libusb").forEach { backend ->
                    val selected = mtkBackend == backend
                    OutlinedButton(
                        onClick = { mtkBackend = backend },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            backend,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand(
                            "mtk info${viewModel.mtkDaOption(mtkDa)} --backend $mtkBackend"
                        )
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Info") }
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand(
                            "mtk partitions${viewModel.mtkDaOption(mtkDa)} --backend $mtkBackend"
                        )
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Partitions") }
            }
            OutlinedTextField(
                value = mtkPartition,
                onValueChange = { mtkPartition = it },
                label = { Text("Partition") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = mtkImage,
                onValueChange = { mtkImage = it },
                label = { Text("Image / output file") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand(
                            "mtk read ${viewModel.shellQuote(mtkPartition)} " +
                                "${viewModel.shellQuote(mtkImage)}${viewModel.mtkDaOption(mtkDa)}"
                        )
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Read") }
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand(
                            "mtk write ${viewModel.shellQuote(mtkImage)} " +
                                "${viewModel.shellQuote(mtkPartition)}${viewModel.mtkDaOption(mtkDa)}"
                        )
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Write") }
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand(
                            "mtk erase ${viewModel.shellQuote(mtkPartition)}" +
                                viewModel.mtkDaOption(mtkDa)
                        )
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("Erase") }
            }
            OutlinedTextField(
                value = mtkScatter,
                onValueChange = { mtkScatter = it },
                label = { Text("Scatter file (XML or YAML)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    viewModel.executeCommand(
                        "mtk flash-scatter ${viewModel.shellQuote(mtkScatter)}" +
                            viewModel.mtkDaOption(mtkDa)
                    )
                },
                enabled = !isExecuting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("FLASH SCATTER", fontWeight = FontWeight.Bold) }
        }

        // ------------------------------------------------------------------
        // Filesystem tools
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "FILESYSTEM IMAGES",
            description = "Core make_f2fs and mke2fs helpers for building partition images.",
            icon = Icons.Default.Storage
        ) {
            OutlinedTextField(
                value = fsImage,
                onValueChange = { fsImage = it },
                label = { Text("Output image") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = fsSize,
                onValueChange = { fsSize = it },
                label = { Text("Size (for example 512M)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand("make_f2fs -l userdata $fsImage $fsSize")
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("make_f2fs") }
                OutlinedButton(
                    onClick = {
                        viewModel.executeCommand("mke2fs -t ext4 -L userdata $fsImage $fsSize")
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.weight(1f)
                ) { Text("mke2fs") }
            }
        }

        // ------------------------------------------------------------------
        // USB probes
        // ------------------------------------------------------------------
        ToolSectionCard(
            title = "USB / LIBUSB PROBES",
            description = "Diagnostics for the Android USB fd -> libusb_bridge -> libusb " +
                "chain. Run these before reporting a device that adb/fastboot cannot see.",
            icon = Icons.Default.Usb
        ) {
            val probes = listOf(
                "unitool-usb-probe",
                "unitool-libusb-probe",
                "unitool-libusb-wrap-probe",
                "unitool-mtk-usb-probe",
                "tst"
            )
            probes.chunked(2).forEach { rowTools ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTools.forEach { tool ->
                        OutlinedButton(
                            onClick = { viewModel.executeCommand(tool) },
                            enabled = !isExecuting,
                            modifier = Modifier.weight(1f)
                        ) { Text(tool, fontSize = 11.sp) }
                    }
                    if (rowTools.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ToolAvailabilityRow(viewModel: UniToolViewModel) {
    val tools = listOf("adb", "fastboot", "heimdall", "qdl", "mtk")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tools.forEach { name ->
            val available = viewModel.bridge.isToolAvailable(name)
            Text(
                text = "$name ${if (available) "ready" else "missing"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (available) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ToolSectionCard(
    title: String,
    description: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}
