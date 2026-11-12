package ahk.unitool.gsm.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * JNI adapter for Android-authorized USB sessions and the source-built
 * Heimdall core. The Android UsbDeviceConnection remains owned by UsbSession.
 */
internal object UsbNativeBridge {
    internal data class CommandResult(val exitCode: Int, val output: String)

    private val available: Boolean by lazy {
        try {
            System.loadLibrary("unitool_usb_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun isAvailable(): Boolean = available

    @Synchronized
    fun open(fileDescriptor: Int): Long {
        check(available) { "Native USB bridge is not available for this ABI" }
        require(fileDescriptor >= 0) { "Invalid Android USB file descriptor" }
        val handle = nativeOpen(fileDescriptor)
        check(handle != 0L) { "libusb could not wrap the Android USB descriptor" }
        return handle
    }

    @Synchronized
    fun close(handle: Long) {
        if (handle != 0L && available) nativeClose(handle)
    }

    fun runHeimdall(
        fileDescriptor: Int,
        arguments: Array<String>,
        workingDirectory: String
    ): CommandResult {
        check(available) { "Native Heimdall core is not available for this ABI" }
        require(fileDescriptor >= -1) { "Invalid Android USB file descriptor" }
        val response = nativeRun(fileDescriptor, arguments, workingDirectory)
            ?: throw IllegalStateException("Heimdall native bridge returned no response")
        require(response.size >= Int.SIZE_BYTES) { "Invalid Heimdall native response" }
        val buffer = ByteBuffer.wrap(response).order(ByteOrder.BIG_ENDIAN)
        val exitCode = buffer.int
        val output = ByteArray(buffer.remaining())
        buffer.get(output)
        return CommandResult(exitCode, String(output, StandardCharsets.UTF_8))
    }

    @JvmStatic
    private external fun nativeOpen(fileDescriptor: Int): Long

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeRun(
        fileDescriptor: Int,
        arguments: Array<String>,
        workingDirectory: String
    ): ByteArray
}
