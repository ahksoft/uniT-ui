package com.unitool.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.Android.MoFlash.data.FdPass;

import java.io.Closeable;

/**
 * Gives the usb device of the phone to a program that runs beside the app.
 *
 * <p>The programs that come from the flash tool of another project (antumbra, qdl) were
 * made for a normal linux and open the device by themselves. Android does not allow that,
 * so the app opens the device with its own permission and passes the file descriptor over
 * an abstract unix socket: the program reads the name from the environment variable
 * {@code MOMIUI_USB_SOCK}, connects to the socket and receives the descriptor, which it
 * then uses as a normal usb device.
 *
 * <p>The app must keep the connection open while the program runs, closing it would close
 * the device under the feet of the program.
 */
public final class UsbFdBroker implements Closeable {
    private static final String TAG = "UsbFdBroker";

    /** Time the program has to connect, the helper library waits this long as well. */
    private static final long SEND_TIMEOUT_MS = 12_000;

    private final UsbHostManager usb;
    private final UsbDeviceInfo device;
    private final UsbDeviceConnection connection;
    private final int fd;
    private final String socketName;
    private volatile int result = Integer.MIN_VALUE;
    private Thread sender;

    private UsbFdBroker(UsbHostManager usb, UsbDeviceInfo device, UsbDeviceConnection connection,
                        int fd, String socketName) {
        this.usb = usb;
        this.device = device;
        this.connection = connection;
        this.fd = fd;
        this.socketName = socketName;
    }

    /**
     * Opens the MediaTek device and makes the socket for the program.
     *
     * @return the broker or {@code null} when no device is attached or it cannot be opened
     */
    public static UsbFdBroker open(UsbHostManager usb) {
        return open(usb, 0x0E8D);
    }

    /** Opens a device of one vendor, see {@link UsbWait#find}. */
    public static UsbFdBroker open(UsbHostManager usb, int vid) {
        return open(usb, vid, false);
    }

    /**
     * Opens a device of one vendor.
     *
     * @param raw true to leave the interfaces to the program, as adb and fastboot need it
     */
    public static UsbFdBroker open(UsbHostManager usb, int vid, boolean raw) {
        if (usb == null) return null;
        UsbDeviceInfo info = UsbWait.find(usb, vid);
        if (info == null) return null;
        if (!usb.hasPermission(info.device)) {
            Log.i(TAG, "No USB permission for " + info.getVidPidHex() + ", requesting automatically...");
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            usb.requestPermission(info.device, new UsbHostManager.UsbPermissionCallback() {
                @Override
                public void onPermissionGranted(android.hardware.usb.UsbDevice granted) {
                    latch.countDown();
                }
                @Override
                public void onPermissionDenied(android.hardware.usb.UsbDevice denied) {
                    latch.countDown();
                }
            });
            try {
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!usb.hasPermission(info.device)) {
            Log.w(TAG, "no usb permission for " + info.getVidPidHex());
            return null;
        }
        UsbDeviceConnection connection = raw ? usb.openRaw(info) : usb.openDevice(info);
        if (connection == null) {
            Log.w(TAG, "cannot open " + info.getVidPidHex() + ": " + usb.getLastError());
            return null;
        }
        int fd = connection.getFileDescriptor();
        if (fd < 0) {
            Log.w(TAG, "the device has no descriptor");
            connection.close();
            return null;
        }
        String name = "@unitool_usb_" + System.nanoTime();
        return new UsbFdBroker(usb, info, connection, fd, name);
    }

    /** The name the program has to read from {@code MOMIUI_USB_SOCK}. */
    public String getSocketName() {
        return socketName;
    }

    /** The descriptor of the device in this process, only useful for the log of a tool. */
    public int getFd() {
        return fd;
    }

    public UsbDeviceInfo getDevice() {
        return device;
    }

    /**
     * Starts waiting for the program.
     *
     * <p>The call blocks until the program took the descriptor, so it runs in its own
     * thread.
     */
    public void startSending() {
        sender = new Thread(() -> {
            try {
                result = FdPass.sendFd(fd, socketName);
                Log.i(TAG, "descriptor " + fd + " sent: " + result);
            } catch (Throwable e) {
                Log.e(TAG, "cannot send the descriptor: " + e.getMessage());
                result = -100;
            }
        }, "usb-fd-send");
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * Waits until the program took the descriptor.
     *
     * @return true when the descriptor arrived
     */
    public boolean awaitDelivery() {
        if (sender == null) return false;
        try {
            sender.join(SEND_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result >= 0;
    }

    /** A text that tells what happened, for the terminal. */
    public String describeResult() {
        if (result >= 0) return "usb device passed to the program (fd " + fd + ")";
        if (result == Integer.MIN_VALUE) return "the program did not ask for the usb device";
        switch (result) {
            case -1: return "the name of the socket was refused";
            case -6: return "the program did not connect within ten seconds";
            default: return "the usb device could not be passed (code " + result + ")";
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                usb.closeDevice(device.device);
            } catch (Exception ignored) {
            }
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        Log.d(TAG, "broker closed");
    }
}
