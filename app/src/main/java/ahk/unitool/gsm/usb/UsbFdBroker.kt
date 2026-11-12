package ahk.unitool.gsm.usb

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Transfers only UsbManager-authorized descriptors to native child processes.
 * The abstract socket is restricted to this app UID; descriptors use SCM_RIGHTS.
 */
internal class UsbFdBroker(
    private val deviceProvider: () -> List<AuthorizedDevice>
) : Closeable {
    internal data class AuthorizedDevice(
        val deviceName: String,
        val fileDescriptor: Int,
        val serialNumber: String
    )

    private val running = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        running.set(true)
        worker = thread(name = "UniToolUsbFdBroker", isDaemon = true) {
            try {
                // LocalServerSocket(String) binds in Android's abstract namespace.
                val localServer = LocalServerSocket(SOCKET_NAME)
                server = localServer
                while (running.get()) {
                    val client = try {
                        localServer.accept()
                    } catch (e: Exception) {
                        if (running.get()) Log.e(TAG, "USB broker accept failed", e)
                        break
                    }
                    client.use { handleClient(it) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "USB broker failed to start", e)
            } finally {
                running.set(false)
                try { server?.close() } catch (_: Exception) {}
                server = null
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        try {
            if (client.peerCredentials.uid != Process.myUid()) {
                Log.w(TAG, "Rejected USB broker client from another UID")
                return
            }
            val reader = BufferedReader(InputStreamReader(client.inputStream, StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            when {
                request == "LIST" -> {
                    val devices = deviceProvider().filter {
                        it.deviceName.isNotBlank() && it.fileDescriptor >= 0
                    }.take(256)
                    Log.d(TAG, "LIST request from uid=${client.peerCredentials.uid}; active sessions=${devices.size}")
                    val output = client.outputStream
                    Log.d(TAG, "LIST requested; returning ${devices.size} authorized USB session(s)")
                    writeLine(output, devices.size.toString())
                    devices.forEach { writeLine(output, it.deviceName) }
                }
                request.startsWith("SERIAL ") -> {
                    val name = request.substring(7)
                    val device = deviceProvider().firstOrNull {
                        it.deviceName == name && it.fileDescriptor >= 0
                    }
                    val serial = device?.serialNumber
                        ?.takeIf { '\n' !in it && '\r' !in it }
                        .orEmpty()
                    writeLine(client.outputStream, serial)
                }
                request.startsWith("FD ") -> {
                    val name = request.substring(3)
                    val device = deviceProvider().firstOrNull {
                        it.deviceName == name && it.fileDescriptor >= 0
                    } ?: return
                    val descriptor = ParcelFileDescriptor.fromFd(device.fileDescriptor)
                    try {
                        client.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                        client.outputStream.write('1'.code)
                        client.outputStream.flush()
                        client.setFileDescriptorsForSend(null)
                        Log.d(TAG, "Transferred authorized USB descriptor for $name")
                    } finally {
                        descriptor.close()
                    }
                }
                else -> Log.w(TAG, "Rejected unknown USB broker request")
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB broker request failed", e)
        }
    }

    private fun writeLine(output: OutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    @Synchronized
    override fun close() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val TAG = "UniToolUsbBroker"
        private const val SOCKET_NAME = "unitool_usb_fd_broker"
    }
}
