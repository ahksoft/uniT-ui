package ahk.unitool.gsm.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns Android USB discovery, permission requests and connection lifetimes.
 *
 * The application owns this manager so its receiver, broker and authorized
 * sessions remain available across Activity recreation and backgrounding.
 * Open sessions are closed automatically on detach and manager.close().
 */
class UsbDeviceManager(
    context: Context,
    listener: Listener? = null,
    /**
     * When false, already-connected devices are opened only if Android has
     * already granted this app access. The host UI (com.unitool.usb) owns the
     * permission dialogs, so the terminal broker does not prompt a second time.
     */
    private val autoRequestPermissions: Boolean = true
) : Closeable {
    @Volatile
    private var listener: Listener? = listener

    /** Attach a UI observer without transferring ownership of this manager. */
    fun setListener(listener: Listener?) {
        this.listener = listener
    }
    interface Listener {
        fun onDevicesChanged(devices: List<DeviceInfo>) {}
        fun onPermissionResult(device: DeviceInfo, granted: Boolean) {}
        fun onDeviceDetached(deviceName: String) {}
        fun onUsbError(message: String, error: Throwable? = null) {}
    }

    data class DeviceInfo(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val deviceSubclass: Int,
        val interfaceCount: Int,
        val hasPermission: Boolean
    ) {
        val displayName: String
            get() = "USB %04X:%04X".format(vendorId, productId)
    }

    private val appContext = context.applicationContext
    private val usbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sessions = ConcurrentHashMap<String, UsbSession>()
    private val fdBroker = UsbFdBroker {
        sessions.entries.mapNotNull { entry ->
            try {
                val serial = try {
                    entry.value.device.serialNumber.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                UsbFdBroker.AuthorizedDevice(
                    entry.key,
                    entry.value.fileDescriptor,
                    serial
                )
            } catch (_: Exception) {
                null
            }
        }
    }.also { it.start() }
    // Avoid duplicate permission dialogs from the Activity and USB receiver.
    private val pendingPermissionRequests = ConcurrentHashMap.newKeySet<String>()
    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    // The Compose USB screen (com.unitool.usb.UsbHostManager) owns the
    // permission dialog and broadcasts this action. Listen for it so the
    // native broker opens devices granted after the terminal has started.
    private val hostPermissionAction = "com.unitool.USB_PERMISSION"
    private var registered = false
    private var closed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDeviceExtra() ?: return
                    pendingPermissionRequests.remove(device.deviceName)
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    Log.i(TAG, "USB permission result: device=${device.deviceName}, granted=$granted")
                    if (granted) openAuthorizedSession(device)
                    listener?.onPermissionResult(device.toInfo(granted), granted)
                    if (!granted) {
                        listener?.onUsbError(
                            "USB permission denied for ${device.deviceName}"
                        )
                    }
                    publishDevices()
                }
                hostPermissionAction -> {
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false
                        )
                    ) {
                        Log.i(TAG, "Host USB permission granted; rescanning devices")
                        rescan()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDeviceExtra()
                    if (device != null) {
                        Log.i(TAG, "USB attached: ${device.deviceName}, permission=${usbManager.hasPermission(device)}")
                        if (usbManager.hasPermission(device)) openAuthorizedSession(device)
                        else if (autoRequestPermissions) try {
                            requestPermission(device)
                        } catch (e: Exception) {
                            Log.e(TAG, "Automatic USB permission request failed", e)
                        }
                    }
                    publishDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra()
                    if (device != null) {
                        closeSession(device.deviceName)
                        listener?.onDeviceDetached(device.deviceName)
                    }
                    publishDevices()
                }
            }
        }
    }

    @Synchronized
    fun register() {
        check(!closed) { "UsbDeviceManager is closed" }
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(hostPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        Log.i(TAG, "USB receiver registered; scanning already-connected devices")
        rescan()
    }

    /**
     * Opens sessions for every device this app may already access. When
     * [autoRequestPermissions] is enabled, missing permissions are requested
     * as well. Safe to call repeatedly (for example when the terminal opens).
     */
    @Synchronized
    fun rescan() {
        if (closed) return
        val connectedDevices = getDevices()
        Log.i(TAG, "USB scan found ${connectedDevices.size} device(s)")
        connectedDevices.forEach { info ->
            Log.i(
                TAG,
                "USB device: name=${info.deviceName}, vid=%04X, pid=%04X, interfaces=${info.interfaceCount}, permission=${info.hasPermission}"
                    .format(info.vendorId, info.productId)
            )
            val device = findDevice(info.deviceName) ?: return@forEach
            if (info.hasPermission) {
                openAuthorizedSession(device)
            } else if (autoRequestPermissions) {
                try {
                    Log.i(TAG, "Requesting USB permission for already-connected device: ${info.deviceName}")
                    requestPermission(device)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request permission for ${info.deviceName}", e)
                }
            }
        }
        publishDevices()
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "USB receiver was already unregistered", e)
        } finally {
            registered = false
        }
    }

    fun getDevices(): List<DeviceInfo> {
        return usbManager.deviceList.values
            .map { it.toInfo(usbManager.hasPermission(it)) }
            .sortedWith(compareBy(DeviceInfo::vendorId, DeviceInfo::productId))
    }

    fun requestPermission(deviceName: String) {
        val device = findDevice(deviceName)
            ?: throw IllegalArgumentException("USB device is no longer connected")
        requestPermission(device)
    }

    fun requestPermission(device: UsbDevice) {
        check(!closed) { "UsbDeviceManager is closed" }
        if (usbManager.hasPermission(device)) {
            pendingPermissionRequests.remove(device.deviceName)
            listener?.onPermissionResult(device.toInfo(true), true)
            return
        }
        if (!pendingPermissionRequests.add(device.deviceName)) return
        val requestCode = device.deviceId and 0x7fffffff
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
            .putExtra(EXTRA_DEVICE_NAME, device.deviceName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            appContext, requestCode, intent, flags
        )
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            pendingPermissionRequests.remove(device.deviceName)
            listener?.onUsbError("Unable to request USB permission", e)
            throw e
        }
    }

    /**
     * Opens an Android USB connection after permission has been granted.
     * Interfaces are claimed explicitly by UsbSession.claimInterface().
     */
    @Synchronized
    fun open(deviceName: String): UsbSession {
        check(!closed) { "UsbDeviceManager is closed" }
        val device = findDevice(deviceName)
            ?: throw IllegalArgumentException("USB device is no longer connected")
        check(usbManager.hasPermission(device)) {
            "USB permission has not been granted for ${device.deviceName}"
        }
        closeSession(device.deviceName)
        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Android could not open ${device.deviceName}")
        if (connection.fileDescriptor < 0) {
            connection.close()
            throw IllegalStateException("Android returned an invalid USB file descriptor")
        }
        val session = UsbSession(device, connection)
        sessions[device.deviceName] = session
        Log.i(TAG, "Opened authorized USB session: ${device.deviceName}, fd=${connection.fileDescriptor}")
        return session
    }

    private fun openAuthorizedSession(device: UsbDevice) {
        try {
            if (sessions.containsKey(device.deviceName)) {
                Log.d(TAG, "USB session already open: ${device.deviceName}")
                return
            }
            open(device.deviceName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open authorized USB session: ${device.deviceName}", e)
            listener?.onUsbError("Unable to open USB device ${device.deviceName}: ${e.message}", e)
        }
    }

    /**
     * Opens a USB session and wraps Android's authorized file descriptor with
     * libusb_wrap_sys_device(). The Android connection remains owned by this
     * session and must not be closed while native code is using the handle.
     */
    @Synchronized
    fun openNative(deviceName: String): UsbSession {
        val session = open(deviceName)
        try {
            session.openNativeBridge()
            return session
        } catch (e: Exception) {
            closeSession(deviceName)
            throw e
        }
    }

    /**
     * Runs a Heimdall command against the currently authorized Android USB
     * connection. The session lock prevents detach/close while native code
     * is using the descriptor.
     */
    internal fun runHeimdall(arguments: Array<String>, workingDirectory: String): UsbNativeBridge.CommandResult {
        val session = sessions.values.firstOrNull()
        return session?.runHeimdall(arguments, workingDirectory)
            ?: UsbNativeBridge.runHeimdall(-1, arguments, workingDirectory)
    }

    @Synchronized
    fun closeSession(deviceName: String) {
        sessions.remove(deviceName)?.close()
    }

    private fun findDevice(deviceName: String): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }

    private fun publishDevices() {
        val devices = getDevices()
        Log.i(TAG, "Publishing ${devices.size} USB device(s); ${sessions.size} authorized session(s) open")
        listener?.onDevicesChanged(devices)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            unregister()
            sessions.keys.toList().forEach(::closeSession)
            pendingPermissionRequests.clear()
            fdBroker.close()
            closed = true
        }
    }

    private fun UsbDevice.toInfo(granted: Boolean) = DeviceInfo(
        deviceName = deviceName,
        vendorId = vendorId,
        productId = productId,
        deviceClass = deviceClass,
        deviceSubclass = deviceSubclass,
        interfaceCount = interfaceCount,
        hasPermission = granted
    )

    private fun Intent.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    inner class UsbSession internal constructor(
        val device: UsbDevice,
        private val connection: UsbDeviceConnection
    ) : Closeable {
        private val claimedInterfaces = linkedSetOf<Int>()
        private var nativeHandle = 0L
        private var sessionClosed = false

        val fileDescriptor: Int
            get() {
                check(!sessionClosed) { "USB session is closed" }
                return connection.fileDescriptor
            }

        val rawDescriptors: ByteArray
            get() {
                check(!sessionClosed) { "USB session is closed" }
                return connection.rawDescriptors ?: byteArrayOf()
            }

        @Synchronized
        fun claimInterface(interfaceId: Int, force: Boolean = false): Boolean {
            check(!sessionClosed) { "USB session is closed" }
            require(interfaceId in 0 until device.interfaceCount) {
                "Invalid USB interface index: $interfaceId"
            }
            if (claimedInterfaces.contains(interfaceId)) return true
            val usbInterface: UsbInterface = device.getInterface(interfaceId)
            val claimed = connection.claimInterface(usbInterface, force)
            if (claimed) claimedInterfaces.add(interfaceId)
            return claimed
        }

        @Synchronized
        fun releaseInterface(interfaceId: Int): Boolean {
            if (sessionClosed || !claimedInterfaces.contains(interfaceId)) return false
            val released = connection.releaseInterface(device.getInterface(interfaceId))
            if (released) claimedInterfaces.remove(interfaceId)
            return released
        }

        @Synchronized
        internal fun runHeimdall(
            arguments: Array<String>,
            workingDirectory: String
        ): UsbNativeBridge.CommandResult {
            check(!sessionClosed) { "USB session is closed" }
            return UsbNativeBridge.runHeimdall(
                connection.fileDescriptor,
                arguments,
                workingDirectory
            )
        }

        @Synchronized
        fun openNativeBridge(): Long {
            check(!sessionClosed) { "USB session is closed" }
            if (nativeHandle == 0L) {
                nativeHandle = UsbNativeBridge.open(connection.fileDescriptor)
            }
            return nativeHandle
        }

        @Synchronized
        override fun close() {
            if (sessionClosed) return
            sessionClosed = true
            if (nativeHandle != 0L) {
                try {
                    UsbNativeBridge.close(nativeHandle)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close native USB handle", e)
                }
                nativeHandle = 0L
            }
            claimedInterfaces.toList().forEach { index ->
                try {
                    connection.releaseInterface(device.getInterface(index))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to release USB interface $index", e)
                }
            }
            claimedInterfaces.clear()
            connection.close()
        }
    }

    companion object {
        private const val TAG = "UniToolUsb"
        private const val EXTRA_DEVICE_NAME = "usb_device_name"
    }
}
