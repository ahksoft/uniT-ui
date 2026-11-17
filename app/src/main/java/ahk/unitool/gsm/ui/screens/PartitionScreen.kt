package ahk.unitool.gsm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    var superDefinitionPath by remember { mutableStateOf("") }
    val superMetadata by viewModel.superMetadata.collectAsState()
    val superMetadataError by viewModel.superMetadataError.collectAsState()

    val filteredPartitions = remember(partitions, searchQuery) {
        if (searchQuery.isBlank()) partitions
        else partitions.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Read-only partition information. Entries are shown only when returned by the connected device; no demo partition data is inserted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("SUPER IMAGE METADATA PREVIEW", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text(
                    "Parse super_def JSON into block devices, groups and partitions. This is a read-only preview; it does not build or flash an image.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = superDefinitionPath,
                    onValueChange = { superDefinitionPath = it },
                    label = { Text("Path to super_def JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = { viewModel.inspectSuperMetadata(superDefinitionPath) },
                    enabled = !isExecuting,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("INSPECT METADATA") }
                superMetadataError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                superMetadata?.let { metadata ->
                    Text("Metadata size: ${metadata.metadataSize} bytes",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Block devices (${metadata.blockDevices.size})",
                        fontWeight = FontWeight.SemiBold)
                    metadata.blockDevices.forEach { device ->
                        Text("${device.name} · ${device.sizeBytes} B · block ${device.blockSize} · alignment ${device.alignment}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text("Groups (${metadata.groups.size})", fontWeight = FontWeight.SemiBold)
                    metadata.groups.forEach { group ->
                        Text("${group.name} · max ${group.maximumSizeBytes} B",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Partitions (${metadata.partitions.size})",
                        fontWeight = FontWeight.SemiBold)
                    metadata.partitions.forEach { partition ->
                        Text(
                            "${partition.name} · ${partition.sizeBytes} B · group ${partition.groupName}" +
                                (partition.imagePath?.let { " · image $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        Button(
            onClick = { viewModel.readPartitions() },
            enabled = !isExecuting && !isLoading,
            modifier = Modifier.fillMaxWidth().testTag("partition_read_table"),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("READ PARTITION TABLE")
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter partitions (boot, super, vbmeta...)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("partition_search_input")
        )

        Text(
            "PARTITIONS (${filteredPartitions.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (filteredPartitions.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (partitions.isEmpty()) {
                        if (isLoading) "Reading partition table…" else "Read the partition table from a supported connected device."
                    } else {
                        "No partitions match '$searchQuery'"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredPartitions) { partition ->
                    PartitionCard(partition = partition)
                }
            }
        }
    }
}

@Composable
fun PartitionCard(partition: PartitionInfo) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                partition.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (partition.sizeFormatted.isNotEmpty()) {
                    Text(
                        "Size: ${partition.sizeFormatted}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (partition.offsetHex.isNotEmpty()) {
                    Text(
                        "Offset: ${partition.offsetHex}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (partition.flags.isNotEmpty()) {
                Text(
                    "Flags: ${partition.flags}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
