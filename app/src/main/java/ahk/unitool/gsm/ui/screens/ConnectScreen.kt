package ahk.unitool.gsm.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import ahk.unitool.gsm.model.DeviceProtocolMode
import ahk.unitool.gsm.model.MtkEngineVersion
import ahk.unitool.gsm.ui.theme.TerminalCyan
import ahk.unitool.gsm.ui.theme.TerminalGreen
import ahk.unitool.gsm.viewmodel.UniToolViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    viewModel: UniToolViewModel,
    modifier: Modifier = Modifier
) {
    val protocolMode by viewModel.protocolMode.collectAsState()
    val mtkEngineVersion by viewModel.mtkEngineVersion.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()

    // MTK state
    val mtkLoader by viewModel.mtkLoader.collectAsState()
    val mtkAuth by viewModel.mtkAuth.collectAsState()
    val mtkPreloader by viewModel.mtkPreloader.collectAsState()
    val mtkBrom by viewModel.mtkBrom.collectAsState()
    val mtkDaa by viewModel.mtkDaa.collectAsState()

    // SPD state
    val spdFdl1 by viewModel.spdFdl1.collectAsState()
    val spdFdl2 by viewModel.spdFdl2.collectAsState()
    val fdl1Addr by viewModel.fdl1Address.collectAsState()
    val fdl2Addr by viewModel.fdl2Address.collectAsState()

    // QC state
    val qcFirehose by viewModel.qcFirehose.collectAsState()
    val qcAuth by viewModel.qcAuth.collectAsState()
    val qcCert by viewModel.qcCert.collectAsState()
    val qcDigest by viewModel.qcDigest.collectAsState()
    val qcStorage by viewModel.qcStorage.collectAsState()

    var storageMenuExpanded by remember { mutableStateOf(false) }

    // Helper launcher for picking files
    var targetFilePickerTarget by remember { mutableStateOf<String?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: it.toString()
            when (targetFilePickerTarget) {
                "mtk_da" -> viewModel.setMtkLoader(fileName)
                "mtk_auth" -> viewModel.setMtkAuth(fileName)
                "mtk_preloader" -> viewModel.setMtkPreloader(fileName)
                "spd_fdl1" -> viewModel.setSpdFdl1(fileName)
                "spd_fdl2" -> viewModel.setSpdFdl2(fileName)
                "qc_firehose" -> viewModel.setQcFirehose(fileName)
                "qc_auth" -> viewModel.setQcAuth(fileName)
                "qc_cert" -> viewModel.setQcCert(fileName)
                "qc_digest" -> viewModel.setQcDigest(fileName)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector Row with Platform Icons
        Text(
            text = "PROTOCOL & TARGET HARDWARE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DeviceProtocolMode.values().forEach { mode ->
                val isSelected = protocolMode == mode
                val platformIcon = when (mode) {
                    DeviceProtocolMode.MTK -> Icons.Default.Memory
                    DeviceProtocolMode.SPD -> Icons.Default.SettingsInputAntenna
                    DeviceProtocolMode.QUALCOMM -> Icons.Default.Bolt
                    DeviceProtocolMode.FASTBOOT -> Icons.Default.Android
                    DeviceProtocolMode.ADB -> Icons.Default.Cable
                }
                val shortName = when (mode) {
                    DeviceProtocolMode.MTK -> "MTK"
                    DeviceProtocolMode.SPD -> "SPD"
                    DeviceProtocolMode.QUALCOMM -> "QUALCOMM"
                    DeviceProtocolMode.FASTBOOT -> "FASTBOOT"
                    DeviceProtocolMode.ADB -> "ADB"
                }
                val protocolSub = when (mode) {
                    DeviceProtocolMode.MTK -> "BROM / DA"
                    DeviceProtocolMode.SPD -> "FDL / DIAG"
                    DeviceProtocolMode.QUALCOMM -> "EDL / QDL"
                    DeviceProtocolMode.FASTBOOT -> "BOOTLOADER"
                    DeviceProtocolMode.ADB -> "USB DEBUG"
                }

                Surface(
                    onClick = { viewModel.setProtocolMode(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF334155)
                    ),
                    modifier = Modifier.testTag("mode_chip_${mode.tag}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = platformIcon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = shortName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = protocolSub,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }
                    }
                }
            }
        }

        // Slim USB Status Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (selectedDevice != null) TerminalGreen else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectedDevice != null) "${selectedDevice!!.connectionModeLabel} · ${selectedDevice!!.displayName ?: "Connected"}" else "No USB Device Connected",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedDevice != null) TerminalGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { viewModel.rescanUsb() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan", modifier = Modifier.size(16.dp))
                }
            }
        }

        // Dedicated Protocol Configuration Cards (Strictly separated per protocol mode)

        when (protocolMode) {
            DeviceProtocolMode.MTK -> {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("mtk_config_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Compact Header with Title and Smaller Flash Engine Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "MEDIATEK FLASH CONFIG",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            // Compact Engine Version Selector Pills
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                MtkEngineVersion.values().forEach { ver ->
                                    val isSelected = mtkEngineVersion == ver
                                    Surface(
                                        onClick = { viewModel.setMtkEngineVersion(ver) },
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.testTag("engine_toggle_${ver.commandPrefix}")
                                    ) {
                                        Text(
                                            text = if (ver == MtkEngineVersion.MTK_V1_MTKCLIENT) "v1 mtkclient" else "v2 mtk/umbrage",
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (mtkEngineVersion == MtkEngineVersion.MTK_V1_MTKCLIENT)
                                "Engine v1: mtkclient (Java protocol engine)"
                            else
                                "Engine v2: core mtk/umbrage (native libumbrage_cli_exec.so)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (mtkEngineVersion == MtkEngineVersion.MTK_V2_ANTUMBRA) {
                            val antumbraBackend by viewModel.antumbraUsbBackend.collectAsState()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("USB Backend (--backend)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("auto", "libusb").forEach { backend ->
                                        val isSelected = antumbraBackend == backend
                                        Surface(
                                            onClick = { viewModel.setAntumbraUsbBackend(backend) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = backend,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // MTK Download Agent (DA) / Loader
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = mtkLoader,
                                onValueChange = { viewModel.setMtkLoader(it) },
                                label = { Text("DA / Loader Binary (mtk: da/loader)") },
                                modifier = Modifier.fillMaxWidth().testTag("mtk_da_input"),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        targetFilePickerTarget = "mtk_da"
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse DA")
                                    }
                                }
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("MTK_DA_V6.bin", "MTK_AllInOne_DA.bin", "DA_PL.bin").forEach { daPreset ->
                                    AssistChip(
                                        onClick = { viewModel.setMtkLoader(daPreset) },
                                        label = { Text(daPreset, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        // MTK Auth File
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = mtkAuth,
                                onValueChange = { viewModel.setMtkAuth(it) },
                                label = { Text("Auth File (mtk: auth - optional)") },
                                placeholder = { Text("e.g. auth_sv5.auth") },
                                modifier = Modifier.fillMaxWidth().testTag("mtk_auth_input"),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        targetFilePickerTarget = "mtk_auth"
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse Auth")
                                    }
                                }
                            )
                        }

                        // MTK Preloader File (Preloader suggestions removed as requested)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = mtkPreloader,
                                onValueChange = { viewModel.setMtkPreloader(it) },
                                label = { Text("Preloader Binary (mtk: preloader)") },
                                modifier = Modifier.fillMaxWidth().testTag("mtk_preloader_input"),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        targetFilePickerTarget = "mtk_preloader"
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse Preloader")
                                    }
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("BROM Handshake", fontWeight = FontWeight.SemiBold)
                                Text("Force bootrom mode handshake", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = mtkBrom,
                                onCheckedChange = { viewModel.setMtkBrom(it) }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("DAA / SLA Bypass", fontWeight = FontWeight.SemiBold)
                                Text("Bypass auth challenge allowlist", fontSize = 11.sp, color = Color.Gray)
                            }
                            Switch(
                                checked = mtkDaa,
                                onCheckedChange = { viewModel.setMtkDaa(it) }
                            )
                        }
                    }
                }
            }

            DeviceProtocolMode.SPD -> {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("spd_config_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "UNISOC / SPREADTRUM (SPD) CONFIGURATION",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )

                        // SPD FDL1 + Address
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = spdFdl1,
                                    onValueChange = { viewModel.setSpdFdl1(it) },
                                    label = { Text("FDL1 Binary (spd: fdl1)") },
                                    modifier = Modifier.weight(1.3f).testTag("spd_fdl1_input"),
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            targetFilePickerTarget = "spd_fdl1"
                                            filePickerLauncher.launch(arrayOf("*/*"))
                                        }) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = "Browse FDL1")
                                        }
                                    }
                                )
                                OutlinedTextField(
                                    value = fdl1Addr,
                                    onValueChange = { viewModel.setFdl1Address(it) },
                                    label = { Text("FDL1 Base Address") },
                                    modifier = Modifier.weight(0.9f).testTag("spd_fdl1_addr_input"),
                                    singleLine = true
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("fdl1.bin", "fdl1_sign.bin", "fdl1_ums9230.bin").forEach { preset ->
                                    AssistChip(
                                        onClick = { viewModel.setSpdFdl1(preset) },
                                        label = { Text(preset, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        // SPD FDL2 + Address
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = spdFdl2,
                                    onValueChange = { viewModel.setSpdFdl2(it) },
                                    label = { Text("FDL2 Binary (spd: fdl2)") },
                                    modifier = Modifier.weight(1.3f).testTag("spd_fdl2_input"),
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            targetFilePickerTarget = "spd_fdl2"
                                            filePickerLauncher.launch(arrayOf("*/*"))
                                        }) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = "Browse FDL2")
                                        }
                                    }
                                )
                                OutlinedTextField(
                                    value = fdl2Addr,
                                    onValueChange = { viewModel.setFdl2Address(it) },
                                    label = { Text("FDL2 Base Address") },
                                    modifier = Modifier.weight(0.9f).testTag("spd_fdl2_addr_input"),
                                    singleLine = true
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("fdl2.bin", "fdl2_sign.bin", "fdl2_ums9230.bin").forEach { preset ->
                                    AssistChip(
                                        onClick = { viewModel.setSpdFdl2(preset) },
                                        label = { Text(preset, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "FDL (First/Second Download Loader) handles bootrom communication and memory staging on Unisoc / Spreadtrum chips.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            DeviceProtocolMode.QUALCOMM -> {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("qc_config_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "QUALCOMM FIREHOSE & EDL CONFIGURATION",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // QC Firehose Programmer (ELF/MBN)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = qcFirehose,
                                onValueChange = { viewModel.setQcFirehose(it) },
                                label = { Text("Firehose Programmer (qc: Firehose ELF/MBN)") },
                                modifier = Modifier.fillMaxWidth().testTag("qc_firehose_input"),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        targetFilePickerTarget = "qc_firehose"
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse Firehose")
                                    }
                                }
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("prog_firehose_ddr.elf", "prog_emmc_firehose.mbn", "prog_ufs_firehose.elf").forEach { preset ->
                                    AssistChip(
                                        onClick = { viewModel.setQcFirehose(preset) },
                                        label = { Text(preset, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        // QC Auth File
                        OutlinedTextField(
                            value = qcAuth,
                            onValueChange = { viewModel.setQcAuth(it) },
                            label = { Text("Authentication Token (qc: auth - optional)") },
                            placeholder = { Text("e.g. edl_auth.bin") },
                            modifier = Modifier.fillMaxWidth().testTag("qc_auth_input"),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    targetFilePickerTarget = "qc_auth"
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse QC Auth")
                                }
                            }
                        )

                        // QC Cert/Cart File
                        OutlinedTextField(
                            value = qcCert,
                            onValueChange = { viewModel.setQcCert(it) },
                            label = { Text("Certificate / CART (qc: cert/cart - optional)") },
                            placeholder = { Text("e.g. cert.bin or cart.bin") },
                            modifier = Modifier.fillMaxWidth().testTag("qc_cert_input"),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    targetFilePickerTarget = "qc_cert"
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse QC Cert")
                                }
                            }
                        )

                        // QC Digest/Dist File
                        OutlinedTextField(
                            value = qcDigest,
                            onValueChange = { viewModel.setQcDigest(it) },
                            label = { Text("Digest / DIST (qc: digest/dist - optional)") },
                            placeholder = { Text("e.g. digest.bin") },
                            modifier = Modifier.fillMaxWidth().testTag("qc_digest_input"),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    targetFilePickerTarget = "qc_digest"
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Browse QC Digest")
                                }
                            }
                        )

                        // QC Target Storage Type
                        Box {
                            OutlinedTextField(
                                value = qcStorage.uppercase(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Target Storage Type") },
                                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { storageMenuExpanded = true }
                                    .testTag("qc_storage_dropdown")
                            )
                            DropdownMenu(
                                expanded = storageMenuExpanded,
                                onDismissRequest = { storageMenuExpanded = false }
                            ) {
                                listOf("ufs", "emmc", "nand", "nvme", "spinor").forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(type.uppercase()) },
                                        onClick = {
                                            viewModel.setQcStorage(type)
                                            storageMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            DeviceProtocolMode.FASTBOOT -> {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("fastboot_config_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ANDROID FASTBOOT & FASTBOOTD",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Native binary: libfastboot.so. Connect phone in Bootloader mode (Vol Down + Power). Commands: fastboot devices, getvar all, flashing unlock, boot, flash.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            DeviceProtocolMode.ADB -> {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("adb_config_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ANDROID DEBUG BRIDGE (ADB)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Native binary: libadb.so. Connect phone with USB Debugging enabled. Commands: adb devices, shell, pull, push, sideload, reboot bootloader, reboot recovery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Action Buttons Row / ADB Tasks Grid
        if (protocolMode == DeviceProtocolMode.ADB) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ADB QUICK ACTIONS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Devices" to "devices",
                        "Get State" to "get_state",
                        "Restart Server" to "restart_server",
                        "TCP/IP 5555" to "tcpip",
                        "Device Props" to "device_props",
                        "Battery Info" to "battery",
                        "CPU Info" to "cpuinfo",
                        "Root" to "root",
                        "Remount" to "remount",
                        "Reboot System" to "reboot_system",
                        "Reboot Recovery" to "reboot_recovery",
                        "Reboot Bootloader" to "reboot_bootloader"
                    ).forEach { (label, action) ->
                        OutlinedButton(
                            onClick = { viewModel.runAdbService(action) },
                            enabled = !isExecuting,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.autoConnect() },
                    enabled = !isExecuting,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("btn_auto_connect"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.FlashOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AUTO-CONNECT", fontWeight = FontWeight.Bold)
                }

                // BOOT DIAG: strictly visible ONLY when SPD mode is selected
                if (protocolMode == DeviceProtocolMode.SPD) {
                    OutlinedButton(
                        onClick = { viewModel.bootDiag() },
                        enabled = !isExecuting,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("btn_boot_diag"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.SettingsInputAntenna, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("BOOT DIAG")
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

