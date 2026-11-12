package com.unitool.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UsbHostManager {
    private static final String TAG = "UsbHostManager";
    private static final String ACTION_USB_PERMISSION = "com.unitool.USB_PERMISSION";

    private final Context context;
    private final android.hardware.usb.UsbManager usbManager;
    private final Map<String, UsbDeviceInfo> connectedDevices = new ConcurrentHashMap<>();
    private final Map<String, UsbDeviceConnection> openConnections = new ConcurrentHashMap<>();
    private final Map<String, UsbInterface> claimedInterfaces = new ConcurrentHashMap<>();
    private volatile String lastError;
    private PendingIntent permissionIntent;
    private UsbPermissionCallback permissionCallback;

    public interface UsbPermissionCallback {
        void onPermissionGranted(UsbDevice device);
        void onPermissionDenied(UsbDevice device);
    }

    public UsbHostManager(Context context) {
        this.context = context.getApplicationContext();
        android.hardware.usb.UsbManager mgr = (android.hardware.usb.UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.usbManager = mgr;
        if (this.usbManager == null) {
            Log.w(TAG, "USB service not available");
            return;
        }
        registerPermissionReceiver();
        enumerateExistingDevices();
    }

    private void registerPermissionReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(permissionReceiver, filter);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register USB permission receiver: " + e.getMessage());
        }
    }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted) {
                // The native libusb broker needs a new scan to open the device
                // with the permission that was just granted.
                try {
                    ahk.unitool.gsm.terminal.TerminalRuntime.INSTANCE.rescan();
                } catch (Throwable ignored) {
                }
            }
            if (device != null && permissionCallback != null) {
                if (granted) permissionCallback.onPermissionGranted(device);
                else permissionCallback.onPermissionDenied(device);
            }
        }
    };

    private void enumerateExistingDevices() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            connectedDevices.put(device.getDeviceName(), new UsbDeviceInfo(device));
            if (!hasPermission(device)) {
                requestPermission(device, null);
            }
        }
    }

    public List<UsbDeviceInfo> listDevices() {
        return new ArrayList<>(connectedDevices.values());
    }

    public List<UsbDeviceInfo> findByVid(int vid) {
        List<UsbDeviceInfo> result = new ArrayList<>();
        for (UsbDeviceInfo info : connectedDevices.values()) {
            if (info.vid == vid) result.add(info);
        }
        return result;
    }

    public UsbDeviceInfo findByVidPid(int vid, int pid) {
        for (UsbDeviceInfo info : connectedDevices.values()) {
            if (info.vid == vid && info.pid == pid) return info;
        }
        return null;
    }

    public UsbDeviceInfo findByVidPidString(String vidPidHex) {
        if (vidPidHex == null || vidPidHex.isEmpty()) return null;
        String hex = vidPidHex.trim().replace(":", "");
        if (hex.length() != 8) return null;
        try {
            int vid = Integer.parseInt(hex.substring(0, 4), 16);
            int pid = Integer.parseInt(hex.substring(4, 8), 16);
            return findByVidPid(vid, pid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public UsbDeviceInfo findSpreadtrum() {
        for (UsbDeviceInfo info : connectedDevices.values()) {
            if (info.isSpreadtrum()) return info;
        }
        return null;
    }

    /**
     * Finds the most usable MediaTek device.
     *
     * <p>Devices exposing the boot rom are preferred over preloader devices, because the
     * BROM handshake is the only way to talk to a device that is in download mode.
     */
    public UsbDeviceInfo findMediaTek() {
        UsbDeviceInfo brom = null;
        UsbDeviceInfo preloader = null;
        UsbDeviceInfo other = null;
        for (UsbDeviceInfo info : connectedDevices.values()) {
            if (!info.isMediaTek()) continue;
            int pid = info.pid;
            if (pid == 0x0003 || pid == 0xF200 || pid == 0xD1E9 || pid == 0xD1E2
                    || pid == 0xD1EC || pid == 0xD1DD) {
                if (brom == null) brom = info;
            } else if (pid == 0x6000 || pid == 0x2000 || pid == 0x2001 || pid == 0x20FF
                    || pid == 0x3000 || pid == 0x0006) {
                if (preloader == null) preloader = info;
            } else if (other == null) {
                other = info;
            }
        }
        if (brom != null) return brom;
        if (preloader != null) return preloader;
        return other;
    }

    public boolean hasPermission(UsbDevice device) {
        if (usbManager == null) return false;
        return usbManager.hasPermission(device);
    }

    public void requestPermission(UsbDevice device, UsbPermissionCallback callback) {
        if (usbManager == null) return;
        this.permissionCallback = callback;
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
        try {
            usbManager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request USB permission: " + e.getMessage());
        }
    }

    public UsbDeviceConnection openDevice(UsbDeviceInfo info) {
        if (usbManager == null) return null;
        if (!hasPermission(info.device)) {
            lastError = "no USB permission for " + info.getVidPidHex();
            return null;
        }

        UsbDeviceConnection conn = usbManager.openDevice(info.device);
        if (conn == null) {
            lastError = "could not open " + info.getVidPidHex();
            return null;
        }

        List<EndpointSet> candidates = collectEndpoints(info.device);
        if (candidates.isEmpty()) {
            lastError = "no usable endpoints on " + info.getVidPidHex()
                    + " (a preloader has to be in CDC ACM or HID mode)";
            Log.w(TAG, lastError);
            conn.close();
            return null;
        }

        UsbInterface claimed = null;
        for (EndpointSet set : candidates) {
            if (conn.claimInterface(set.usbInterface, true)) {
                claimed = set.usbInterface;
                info.usbInterface = set.usbInterface;
                info.endpointOut = set.endpointOut;
                info.endpointIn = set.endpointIn;
                Log.i(TAG, "Using " + (set.bulk ? "bulk" : "interrupt") + " endpoints on "
                        + info.getVidPidHex());
                break;
            }
            Log.w(TAG, "Interface " + set.usbInterface.getId() + " of "
                    + info.getVidPidHex() + " could not be claimed");
        }

        if (claimed == null) {
            lastError = "the USB interface of " + info.getVidPidHex()
                    + " is owned by the system - a HID preloader cannot be used, the device"
                    + " has to be in CDC ACM preloader mode";
            Log.w(TAG, lastError);
            conn.close();
            return null;
        }

        claimedInterfaces.put(info.device.getDeviceName(), claimed);
        lastError = null;
        openConnections.put(info.device.getDeviceName(), conn);
        return conn;
    }

    /**
     * Opens a device without claiming an interface.
     *
     * <p>Tools that bring their own usb stack (adb, fastboot) claim what they need
     * themselves; a claim of this app would get in their way.
     */
    public UsbDeviceConnection openRaw(UsbDeviceInfo info) {
        if (usbManager == null) return null;
        if (!hasPermission(info.device)) {
            lastError = "no USB permission for " + info.getVidPidHex();
            return null;
        }
        UsbDeviceConnection conn = usbManager.openDevice(info.device);
        if (conn == null) {
            lastError = "could not open " + info.getVidPidHex();
            return null;
        }
        lastError = null;
        openConnections.put(info.device.getDeviceName(), conn);
        Log.i(TAG, "opened " + info.getVidPidHex() + " without claiming an interface");
        return conn;
    }

    /** One usable pair of endpoints on a single interface. */
    private static class EndpointSet {
        final UsbInterface usbInterface;
        final UsbEndpoint endpointOut;
        final UsbEndpoint endpointIn;
        final boolean bulk;

        EndpointSet(UsbInterface usbInterface, UsbEndpoint endpointOut, UsbEndpoint endpointIn,
                    boolean bulk) {
            this.usbInterface = usbInterface;
            this.endpointOut = endpointOut;
            this.endpointIn = endpointIn;
            this.bulk = bulk;
        }
    }

    /**
     * All endpoint pairs of a device, the bulk pairs come first.
     *
     * <p>A preloader usually shows up as CDC ACM (bulk endpoints), some devices use a
     * HID interface (interrupt endpoints) instead.
     */
    private static List<EndpointSet> collectEndpoints(UsbDevice device) {
        List<EndpointSet> bulk = new ArrayList<>();
        List<EndpointSet> interrupt = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint bulkOut = null, bulkIn = null, intOut = null, intIn = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                boolean inbound = ep.getDirection() == UsbConstants.USB_DIR_IN;
                int type = ep.getType();
                if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (inbound) { if (bulkIn == null) bulkIn = ep; }
                    else { if (bulkOut == null) bulkOut = ep; }
                } else if (type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (inbound) { if (intIn == null) intIn = ep; }
                    else { if (intOut == null) intOut = ep; }
                }
            }
            if (bulkOut != null && bulkIn != null) {
                bulk.add(new EndpointSet(intf, bulkOut, bulkIn, true));
            } else if (intOut != null && intIn != null) {
                interrupt.add(new EndpointSet(intf, intOut, intIn, false));
            }
        }
        List<EndpointSet> all = new ArrayList<>(bulk);
        all.addAll(interrupt);
        return all;
    }

    /** Reason of the last failed {@link #openDevice(UsbDeviceInfo)} call. */
    public String getLastError() {
        return lastError;
    }

    public void closeDevice(UsbDevice device) {
        String key = device.getDeviceName();
        UsbDeviceConnection conn = openConnections.remove(key);
        UsbInterface claimed = claimedInterfaces.remove(key);
        if (conn != null) {
            if (claimed != null) {
                try {
                    conn.releaseInterface(claimed);
                } catch (Exception ignored) {}
            }
            conn.close();
        }
    }

    public boolean isDeviceOpen(UsbDevice device) {
        return openConnections.containsKey(device.getDeviceName());
    }

    public void closeAll() {
        for (Map.Entry<String, UsbDeviceConnection> entry : openConnections.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ignored) {}
        }
        openConnections.clear();
    }

    public void requestPermissionForAllDevices() {
        if (usbManager == null) return;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!usbManager.hasPermission(device)) {
                Intent intent = new Intent(ACTION_USB_PERMISSION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
                permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags);
                usbManager.requestPermission(device, permissionIntent);
            }
        }
    }

    public void rescan() {
        // Re-read all devices from the USB manager — picks up newly enumerated
        // devices (e.g. after mode switch: diag → brom, brom → fdl1, etc.)
        Map<String, UsbDevice> current = usbManager.getDeviceList();
        // Add new devices
        for (UsbDevice device : current.values()) {
            String key = device.getDeviceName();
            if (!connectedDevices.containsKey(key)) {
                connectedDevices.put(key, new UsbDeviceInfo(device));
            }
        }
        // Remove stale devices
        connectedDevices.keySet().removeIf(key -> !current.containsKey(key));
    }

    public void onDeviceAttached(UsbDevice device) {
        connectedDevices.put(device.getDeviceName(), new UsbDeviceInfo(device));
        if (!hasPermission(device)) {
            requestPermission(device, null);
        }
    }

    public void onDeviceDetached(UsbDevice device) {
        closeDevice(device);
        connectedDevices.remove(device.getDeviceName());
    }

    public UsbDeviceInfo getDeviceInfo(UsbDevice device) {
        return connectedDevices.get(device.getDeviceName());
    }
}
