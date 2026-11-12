package ahk.unitool.gsm.terminal

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import ahk.unitool.gsm.usb.UsbNativeBridge
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Bridges the terminal's heimdall command into the app process. A local
 * abstract-domain socket is used because only this process owns the Android
 * UsbDeviceConnection and its permission-granted file descriptor.
 */
internal class HeimdallCommandServer(
    private val executor: (Array<String>, String) -> UsbNativeBridge.CommandResult
) : Closeable {
    @Volatile
    private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        val socket = LocalServerSocket(SOCKET_NAME)
        serverSocket = socket
        running = true
        worker = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "Heimdall local socket accept failed", e)
                    break
                }
                handle(client)
            }
        }, "UniTool-Heimdall-RPC").apply {
            isDaemon = true
            start()
        }
    }

    private fun handle(client: LocalSocket) {
        client.use { socket ->
            val result = try {
                // The terminal child runs under the same app UID. Reject any
                // other local process that manages to reach the abstract socket.
                if (socket.peerCredentials.uid != Process.myUid()) {
                    throw SecurityException("Heimdall bridge client UID mismatch")
                }

                val input = DataInputStream(BufferedInputStream(socket.inputStream))
                val argumentCount = input.readInt()
                require(argumentCount in 0..MAX_ARGUMENT_COUNT) {
                    "Invalid Heimdall argument count"
                }

                val workingDirectory = readString(input, MAX_PATH_BYTES)
                val arguments = Array(argumentCount) {
                    readString(input, MAX_ARGUMENT_BYTES)
                }
                executor(arguments, workingDirectory)
            } catch (e: Exception) {
                Log.w(TAG, "Rejected or failed Heimdall RPC request", e)
                UsbNativeBridge.CommandResult(
                    1,
                    "ERROR: UniTool Heimdall bridge: ${e.message ?: "invalid request"}\n"
                )
            }

            try {
                val output = result.output.toByteArray(StandardCharsets.UTF_8)
                require(output.size <= MAX_OUTPUT_BYTES) {
                    "Heimdall output exceeds the RPC limit"
                }
                val response = DataOutputStream(BufferedOutputStream(socket.outputStream))
                response.writeInt(result.exitCode)
                response.writeInt(output.size)
                response.write(output)
                response.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to return Heimdall command output", e)
            }
        }
    }

    private fun readString(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readInt()
        require(length in 0..maximumBytes) { "Invalid Heimdall string length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    @Synchronized
    override fun close() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        worker?.interrupt()
        worker = null
    }

    companion object {
        private const val TAG = "UniToolHeimdall"
        private const val SOCKET_NAME = "unitool_heimdall_rpc"
        private const val MAX_ARGUMENT_COUNT = 128
        private const val MAX_PATH_BYTES = 4096
        private const val MAX_ARGUMENT_BYTES = 1024 * 1024
        private const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
    }
}
