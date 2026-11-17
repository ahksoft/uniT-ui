package ahk.unitool.gsm.model

import org.json.JSONObject

/**
 * Read-only representation of the super_def JSON metadata used by MOFLASH.
 * This class parses metadata only; it never invokes lpmake or writes images.
 */
data class SuperImageMetadata(
    val metadataSize: String,
    val blockDevices: List<BlockDevice>,
    val groups: List<Group>,
    val partitions: List<Partition>
) {
    data class BlockDevice(
        val name: String,
        val sizeBytes: Long?,
        val blockSize: Long,
        val alignment: Long
    )

    data class Group(val name: String, val maximumSizeBytes: Long?)

    data class Partition(
        val name: String,
        val imagePath: String?,
        val sizeBytes: Long?,
        val groupName: String
    )

    companion object {
        fun parse(jsonText: String): SuperImageMetadata {
            require(jsonText.isNotBlank()) { "Metadata file is empty" }
            require(jsonText.length <= 2 * 1024 * 1024) { "Metadata file exceeds 2 MiB" }
            val root = JSONObject(jsonText)
            val metaSize = root.optJSONObject("super_meta")
                ?.optString("size", "65536") ?: "65536"
            val metadataSize = positiveLong(metaSize, "super_meta.size").toString()

            val devices = buildList {
                val array = root.optJSONArray("block_devices")
                    ?: throw IllegalArgumentException("Missing block_devices array")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        BlockDevice(
                            name = requiredName(item.optString("name"), "block_devices[$i].name"),
                            sizeBytes = optionalPositiveLong(item.optString("size"), "block_devices[$i].size"),
                            blockSize = positiveLong(item.optString("block_size", "4096"), "block_devices[$i].block_size"),
                            alignment = positiveLong(item.optString("alignment", "1048576"), "block_devices[$i].alignment")
                        )
                    )
                }
            }
            val groups = buildList {
                val array = root.optJSONArray("groups")
                    ?: throw IllegalArgumentException("Missing groups array")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        Group(
                            name = requiredName(item.optString("name"), "groups[$i].name"),
                            maximumSizeBytes = optionalPositiveLong(item.optString("maximum_size"), "groups[$i].maximum_size")
                        )
                    )
                }
            }
            val partitions = buildList {
                val array = root.optJSONArray("partitions")
                    ?: throw IllegalArgumentException("Missing partitions array")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val path = item.optString("path").takeIf { it.isNotBlank() && it != "null" }
                    add(
                        Partition(
                            name = requiredName(item.optString("name"), "partitions[$i].name"),
                            imagePath = path,
                            sizeBytes = optionalPositiveLong(item.optString("size"), "partitions[$i].size"),
                            groupName = requiredName(item.optString("group_name"), "partitions[$i].group_name")
                        )
                    )
                }
            }
            require(devices.isNotEmpty()) { "No block devices found" }
            require(groups.isNotEmpty()) { "No groups found" }
            require(partitions.isNotEmpty()) { "No partitions found" }
            val groupNames = groups.map { it.name }.toSet()
            partitions.forEach {
                require(it.groupName in groupNames) {
                    "Partition ${it.name} references unknown group ${it.groupName}"
                }
            }
            return SuperImageMetadata(metadataSize, devices, groups, partitions)
        }

        private fun requiredName(value: String, field: String): String {
            val clean = value.trim()
            require(clean.isNotEmpty() && clean.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) {
                "Invalid $field"
            }
            return clean
        }

        private fun optionalPositiveLong(value: String, field: String): Long? {
            if (value.isBlank() || value == "null") return null
            val parsed = value.trim().toLongOrNull()
                ?: throw IllegalArgumentException("Invalid numeric value for $field")
            require(parsed > 0) { "$field must be greater than zero" }
            return parsed
        }
    }
}
