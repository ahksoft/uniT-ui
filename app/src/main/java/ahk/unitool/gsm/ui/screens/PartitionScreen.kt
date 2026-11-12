package ahk.unitool.gsm.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ahk.unitool.gsm.model.PartitionInfo
import ahk.unitool.gsm.viewmodel.UniToolViewModel

@Composable
fun PartitionScreen(
    viewModel: UniToolViewModel,
    modifier: Modifier = Modifier
) {
    val partitions by viewModel.partitions.collectAsState()
    val isLoading by viewModel.isLoadingPartitions.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedPartitionForDump by remember { mutableStateOf<PartitionInfo?>(null) }
    var selectedPartitionForWrite by remember { mutableStateOf<PartitionInfo?>(null) }
    var selectedPartitionForErase by remember { mutableStateOf<PartitionInfo?>(null) }

    var dumpFileName by remember { mutableStateOf("") }
    var writeFileName by remember { mutableStateOf("") }

    val filteredPartitions = remember(partitions, searchQuery) {
        if (searchQuery.isBlank()) partitions
        else partitions.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.readPartitions() },
                enabled = !isExecuting && !isLoading,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Load GPT")
            }

            OutlinedButton(
                onClick = { viewModel.backupAllPartitions() },
                enabled = !isExecuting && !isLoading,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Backup All")
            }
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter partitions (e.g. boot, super, vbmeta)...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("partition_search_input")
        )

        // Summary Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PARTITIONS (${filteredPartitions.size})",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (partitions.isNotEmpty()) {
                Text(
                    text = "Tap actions to dump, flash or erase",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Partition List
        if (filteredPartitions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (partitions.isEmpty()) "Tap 'Load GPT' to query device partition table." else "No partitions matching '$searchQuery'",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredPartitions) { partition ->
                    PartitionCard(
                        partition = partition,
                        onDump = {
                            selectedPartitionForDump = partition
                            dumpFileName = "${partition.name}.img"
                        },
                        onWrite = {
                            selectedPartitionForWrite = partition
                            writeFileName = "${partition.name}.img"
                        },
                        onErase = {
                            selectedPartitionForErase = partition
                        }
                    )
                }
            }
        }
    }

    // Dump Dialog
    selectedPartitionForDump?.let { p ->
        AlertDialog(
            onDismissRequest = { selectedPartitionForDump = null },
            title = { Text("Dump Partition: ${p.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Read partition '${p.name}' from device flash and save to file:")
                    OutlinedTextField(
                        value = dumpFileName,
                        onValueChange = { dumpFileName = it },
                        label = { Text("Destination File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fn = dumpFileName.ifEmpty { "${p.name}.img" }
                        viewModel.dumpPartition(p.name, fn)
                        selectedPartitionForDump = null
                    }
                ) {
                    Text("Start Dump")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPartitionForDump = null }) { Text("Cancel") }
            }
        )
    }

    // Write Dialog
    selectedPartitionForWrite?.let { p ->
        AlertDialog(
            onDismissRequest = { selectedPartitionForWrite = null },
            title = { Text("Flash to Partition: ${p.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Flash input image file to target partition '${p.name}':")
                    OutlinedTextField(
                        value = writeFileName,
                        onValueChange = { writeFileName = it },
                        label = { Text("Source Image File") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fn = writeFileName.ifEmpty { "${p.name}.img" }
                        viewModel.flashPartition(p.name, fn)
                        selectedPartitionForWrite = null
                    }
                ) {
                    Text("Start Flash")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPartitionForWrite = null }) { Text("Cancel") }
            }
        )
    }

    // Erase Dialog
    selectedPartitionForErase?.let { p ->
        AlertDialog(
            onDismissRequest = { selectedPartitionForErase = null },
            title = { Text("Erase Partition: ${p.name}?") },
            text = {
                Text("Are you sure you want to erase partition '${p.name}'? All data on this partition will be permanently cleared.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.erasePartition(p.name)
                        selectedPartitionForErase = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Erase")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedPartitionForErase = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun PartitionCard(
    partition: PartitionInfo,
    onDump: () -> Unit,
    onWrite: () -> Unit,
    onErase: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = partition.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (partition.sizeFormatted.isNotEmpty()) {
                        Text(
                            text = partition.sizeFormatted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (partition.sizeHex.isNotEmpty()) {
                        Text(
                            text = partition.sizeHex,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDump,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Dump Partition",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onWrite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = "Flash to Partition",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }

                IconButton(
                    onClick = onErase,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Erase Partition",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
