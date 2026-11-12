package ahk.unitool.gsm.model

enum class DeviceProtocolMode(val displayName: String, val tag: String) {
    MTK("MediaTek BROM / DA", "MTK"),
    SPD("Unisoc / Spreadtrum", "SPD"),
    QUALCOMM("Qualcomm EDL", "QDL"),
    FASTBOOT("Fastboot Mode", "FB"),
    ADB("ADB Shell & Tools", "ADB")
}

enum class MtkEngineVersion(
    val title: String,
    val subtitle: String,
    val commandPrefix: String
) {
    MTK_V1_MTKCLIENT("MTK v1 (mtkclient)", "BROM & Preloader Java Protocol Engine", "mtkclient"),
    MTK_V2_ANTUMBRA("MTK v2 (mtk/umbrage)", "Core native MediaTek engine (libumbrage_cli_exec.so)", "mtk")
}

data class PartitionInfo(
    val name: String,
    val sizeHex: String = "",
    val sizeFormatted: String = "",
    val offsetHex: String = "",
    val flags: String = ""
)

enum class LogLineType {
    INPUT,
    OUTPUT,
    ERROR,
    SYSTEM,
    PROMPT
}

data class TerminalLine(
    val text: String,
    val type: LogLineType = LogLineType.OUTPUT,
    val timestamp: Long = System.currentTimeMillis()
)

data class ServiceTask(
    val id: String,
    val title: String,
    val subtitle: String,
    val command: String,
    val category: String,
    val dangerous: Boolean = false
)

data class DeviceInfo(
    val manufacturer: String = "Unknown",
    val model: String = "Unknown",
    val androidVersion: String = "Unknown",
    val device: String = "Unknown",
    val serial: String = "Unknown"
)

