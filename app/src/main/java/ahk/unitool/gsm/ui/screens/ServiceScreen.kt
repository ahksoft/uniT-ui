package ahk.unitool.gsm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.model.DeviceProtocolMode
import ahk.unitool.gsm.model.MtkEngineVersion
import ahk.unitool.gsm.viewmodel.UniToolViewModel

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(
    viewModel: UniToolViewModel,
    modifier: Modifier = Modifier
) {
    val isExecuting by viewModel.isExecuting.collectAsState()
    val protocolMode by viewModel.protocolMode.collectAsState()
    val mtkEngineVersion by viewModel.mtkEngineVersion.collectAsState()

    // Loaders / Programmers
    val mtkLoader by viewModel.mtkLoader.collectAsState()
    val spdFdl1 by viewModel.spdFdl1.collectAsState()
    val spdFdl2 by viewModel.spdFdl2.collectAsState()
    val qcFirehose by viewModel.qcFirehose.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Platform Loader / Programmer Configuration Card (Changes based on selected protocol mode)
        ServiceCard(
            title = "ACTIVE PLATFORM & LOADER CONFIGURATION",
            description = "Selected Protocol: ${protocolMode.name}. Configure loader/programmer binaries for active service operations."
        ) {
            when (protocolMode) {
                DeviceProtocolMode.MTK -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                                MtkEngineVersion.values().forEachIndexed { index, ver ->
                                    SegmentedButton(
                                        selected = mtkEngineVersion == ver,
                                        onClick = { viewModel.setMtkEngineVersion(ver) },
                                        shape = SegmentedButtonDefaults.itemShape(index = index, count = MtkEngineVersion.values().size)
                                    ) {
                                        Text(
                                            text = if (ver == MtkEngineVersion.MTK_V1_MTKCLIENT) "v1 mtkclient" else "v2 mtk/umbrage",
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = mtkLoader,
                            onValueChange = { viewModel.setMtkLoader(it) },
                            label = { Text("DA / Loader Binary (mtk)") },
                            modifier = Modifier.fillMaxWidth().testTag("service_mtk_loader_input"),
                            singleLine = true
                        )
                    }
                }
                DeviceProtocolMode.SPD -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = spdFdl1,
                            onValueChange = { viewModel.setSpdFdl1(it) },
                            label = { Text("FDL1 Binary (spd)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = spdFdl2,
                            onValueChange = { viewModel.setSpdFdl2(it) },
                            label = { Text("FDL2 Binary (spd)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                DeviceProtocolMode.QUALCOMM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = qcFirehose,
                            onValueChange = { viewModel.setQcFirehose(it) },
                            label = { Text("Firehose Programmer (qdl)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
                DeviceProtocolMode.FASTBOOT -> {
                    Text("Fastboot mode active. USB bootloader commands ready.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                DeviceProtocolMode.ADB -> {
                    Text("ADB mode active. USB debugging commands ready.", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Dynamic Services based on selected protocol mode
        when (protocolMode) {
            DeviceProtocolMode.MTK -> {
                ServiceCard(
                    title = "MEDIATEK DIAGNOSTICS & HARDWARE INFO",
                    description = "Read target SoC configurations, hardware IDs, and dump low-level firmware components."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Target Config", Icons.Default.Info, enabled = !isExecuting) { viewModel.runServiceAction("info") }
                        ServiceActionButton("Read MEID", Icons.Default.Fingerprint, enabled = !isExecuting) { viewModel.runServiceAction("meid") }
                        ServiceActionButton("Dump BROM", Icons.Default.Save, enabled = !isExecuting) { viewModel.runServiceAction("dump_brom") }
                        ServiceActionButton("Dump Preloader", Icons.Default.Save, enabled = !isExecuting) { viewModel.runServiceAction("dump_preloader") }
                    }
                }

                ServiceCard(
                    title = "BOOTLOADER, EXPLOITS & SLOTS",
                    description = "Manage seccfg bootloader locking states, run hardware bypasses, or toggle active slots."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Unlock BL", Icons.Default.LockOpen, enabled = !isExecuting) { viewModel.runServiceAction("unlock_bootloader") }
                        ServiceActionButton("Lock BL", Icons.Default.Lock, enabled = !isExecuting) { viewModel.runServiceAction("lock_bootloader") }
                        ServiceActionButton("Bypass SLA/DAA", Icons.Default.Key, enabled = !isExecuting) { viewModel.runServiceAction("auth_bypass") }
                        ServiceActionButton("DAA Allowlist", Icons.Default.Security, enabled = !isExecuting) { viewModel.runServiceAction("daa_bypass") }
                        ServiceActionButton("Kamakiri (MT6789)", Icons.Default.Warning, enabled = !isExecuting) { viewModel.runServiceAction("kamakiri") }
                        ServiceActionButton("Set Slot A", Icons.Default.Storage, enabled = !isExecuting) { viewModel.runServiceAction("switch_slot_a") }
                        ServiceActionButton("Set Slot B", Icons.Default.Storage, enabled = !isExecuting) { viewModel.runServiceAction("switch_slot_b") }
                    }
                }

                ServiceCard(
                    title = "USERDATA FORMAT & FRP REMOVAL",
                    description = "Wipe user data partition or clear Factory Reset Protection (FRP) partition."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Reset FRP Lock", Icons.Default.Security, enabled = !isExecuting) { viewModel.runServiceAction("reset_frp") }
                        ServiceActionButton("Wipe Userdata", Icons.Default.Delete, enabled = !isExecuting, isDestructive = true) { viewModel.runServiceAction("format_userdata") }
                    }
                }

                ServiceCard(
                    title = "NVRAM & IMEI BACKUP",
                    description = "Preserve vital radio calibration, baseband parameters, and device IMEI partitions."
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.runServiceAction("backup_nvram") },
                            enabled = !isExecuting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup NVRAM")
                        }
                        OutlinedButton(
                            onClick = { viewModel.runServiceAction("restore_nvram") },
                            enabled = !isExecuting,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore NVRAM")
                        }
                    }
                }

                ServiceCard(
                    title = "REBOOT & PROTOCOL MODE SWITCH",
                    description = "Issue hardware reset or trigger device boot into specific recovery/flashing states."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Reboot System", Icons.Default.PowerSettingsNew, enabled = !isExecuting) { viewModel.runServiceAction("reboot") }
                        ServiceActionButton("Reboot Fastboot", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runServiceAction("reboot_fastboot") }
                        ServiceActionButton("Reboot Recovery", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runServiceAction("reboot_recovery") }
                        ServiceActionButton("Reboot EDL", Icons.Default.Warning, enabled = !isExecuting) { viewModel.runServiceAction("reboot_edl") }
                        ServiceActionButton("Crash Preloader", Icons.Default.Warning, enabled = !isExecuting) { viewModel.runServiceAction("crash") }
                    }
                }
            }

            DeviceProtocolMode.SPD -> {
                ServiceCard(
                    title = "UNISOC / SPREADTRUM SERVICES",
                    description = "Specialized FDL diagnostics, boot diagnostics, SIM-lock removal, and partition tools."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Chip Info", Icons.Default.Info, enabled = !isExecuting) { viewModel.runSpdService("chip_info") }
                        ServiceActionButton("Boot DIAG", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runSpdService("boot_diag") }
                        ServiceActionButton("Reset FRP", Icons.Default.Security, enabled = !isExecuting) { viewModel.runSpdService("reset_frp") }
                        ServiceActionButton("Format Userdata", Icons.Default.Delete, enabled = !isExecuting, isDestructive = true) { viewModel.runSpdService("format_userdata") }
                        ServiceActionButton("Unlock SIM-Lock", Icons.Default.LockOpen, enabled = !isExecuting) { viewModel.runSpdService("unlock_simlock") }
                        ServiceActionButton("Backup PhaseCheck", Icons.Default.Save, enabled = !isExecuting) { viewModel.runSpdService("backup_phasecheck") }
                        ServiceActionButton("Backup NVRAM", Icons.Default.Save, enabled = !isExecuting) { viewModel.runSpdService("backup_nvram") }
                        ServiceActionButton("Restore NVRAM", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runSpdService("restore_nvram") }
                        ServiceActionButton("Reboot System", Icons.Default.PowerSettingsNew, enabled = !isExecuting) { viewModel.runSpdService("reboot_system") }
                        ServiceActionButton("Reboot Recovery", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runSpdService("reboot_recovery") }
                        ServiceActionButton("Reboot Fastboot", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runSpdService("reboot_fastboot") }
                    }
                }
            }

            DeviceProtocolMode.QUALCOMM -> {
                ServiceCard(
                    title = "QUALCOMM QDL (EDL / FIREHOSE)",
                    description = "Core qdl takes a firehose programmer plus rawprogram/patch XML files. " +
                        "Erase/read operations are expressed by the XML files in the firmware package."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("Flash Firmware", Icons.Default.FlashOn, enabled = !isExecuting) { viewModel.runQcService("flash_firmware") }
                        ServiceActionButton("Reboot EDL", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runQcService("reboot_edl") }
                        ServiceActionButton("Reboot Bootloader", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runQcService("reboot_bootloader") }
                        ServiceActionButton("Reboot System", Icons.Default.PowerSettingsNew, enabled = !isExecuting) { viewModel.runQcService("reboot_system") }
                    }
                }
            }

            DeviceProtocolMode.FASTBOOT -> {
                ServiceCard(
                    title = "FASTBOOT & BOOTLOADER MAINTENANCE",
                    description = "Bootloader unlock status, flashing locks, active slots, and partition erase operations."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("List Devices", Icons.Default.Info, enabled = !isExecuting) { viewModel.runFastbootService("devices") }
                        ServiceActionButton("Getvar All", Icons.Default.Info, enabled = !isExecuting) { viewModel.runFastbootService("getvar_all") }
                        ServiceActionButton("Unlock Flashing", Icons.Default.LockOpen, enabled = !isExecuting) { viewModel.runFastbootService("unlock_flashing") }
                        ServiceActionButton("Lock Flashing", Icons.Default.Lock, enabled = !isExecuting) { viewModel.runFastbootService("lock_flashing") }
                        ServiceActionButton("Erase FRP", Icons.Default.Security, enabled = !isExecuting) { viewModel.runFastbootService("erase_frp") }
                        ServiceActionButton("Erase Userdata", Icons.Default.Delete, enabled = !isExecuting, isDestructive = true) { viewModel.runFastbootService("erase_userdata") }
                        ServiceActionButton("Set Slot A", Icons.Default.Storage, enabled = !isExecuting) { viewModel.runFastbootService("set_slot_a") }
                        ServiceActionButton("Set Slot B", Icons.Default.Storage, enabled = !isExecuting) { viewModel.runFastbootService("set_slot_b") }
                        ServiceActionButton("Reboot System", Icons.Default.PowerSettingsNew, enabled = !isExecuting) { viewModel.runFastbootService("reboot_system") }
                        ServiceActionButton("Reboot Fastbootd", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runFastbootService("reboot_fastbootd") }
                    }
                }
            }

            DeviceProtocolMode.ADB -> {
                ServiceCard(
                    title = "ANDROID DEBUG BRIDGE (ADB) SERVICES",
                    description = "Advanced ADB shell commands, setup wizard bypass, package manager actions, and logcat."
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ServiceActionButton("List Devices", Icons.Default.Info, enabled = !isExecuting) { viewModel.runAdbService("devices") }
                        ServiceActionButton("Device Props", Icons.Default.Info, enabled = !isExecuting) { viewModel.runAdbService("device_props") }
                        ServiceActionButton("TCP/IP 5555", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runAdbService("tcpip") }
                        ServiceActionButton("Restart Server", Icons.Default.Refresh, enabled = !isExecuting) { viewModel.runAdbService("restart_server") }
                        ServiceActionButton("Battery Info", Icons.Default.Info, enabled = !isExecuting) { viewModel.runAdbService("battery") }
                        ServiceActionButton("Bypass Setup", Icons.Default.Security, enabled = !isExecuting) { viewModel.runAdbService("bypass_setup_wizard") }
                        ServiceActionButton("Root ADB", Icons.Default.Key, enabled = !isExecuting) { viewModel.runAdbService("root") }
                        ServiceActionButton("Remount FS", Icons.Default.Storage, enabled = !isExecuting) { viewModel.runAdbService("remount") }
                        ServiceActionButton("SELinux Permissive", Icons.Default.Warning, enabled = !isExecuting) { viewModel.runAdbService("selinux_permissive") }
                        ServiceActionButton("Reboot System", Icons.Default.PowerSettingsNew, enabled = !isExecuting) { viewModel.runAdbService("reboot_system") }
                        ServiceActionButton("Dump Logcat", Icons.Default.Save, enabled = !isExecuting) { viewModel.runAdbService("dump_logcat") }
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceCard(
    title: String,
    description: String,
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
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
fun ServiceActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = if (isDestructive) {
            ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        }
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
}
