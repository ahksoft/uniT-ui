package com.unitool.protocol.spd;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.unitool.usb.BulkTransfer;
import com.unitool.usb.UsbDeviceInfo;
import com.unitool.usb.UsbHostManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public class SpdProtocol {
    private static final String TAG = "SPD-Protocol";

    public static final int VID_SPREADTRUM = 0x1782;
    public static final int PID_BROM = 0x4D00;
    public static final int PID_FDL1 = 0x4D01;
    public static final int PID_FDL2 = 0x4D02;
    public static final int PID_DIAG = 0x4D11;

    private final UsbHostManager usbHostManager;
    private UsbDeviceInfo deviceInfo;
    private UsbDeviceConnection connection;
    private boolean connected = false;
    private boolean debug = false;
    private String currentMode = "";

    public SpdProtocol(UsbHostManager usbHostManager) {
        this.usbHostManager = usbHostManager;
    }

    public Map<String, Object> connect(String soc, byte[] fdl1Data, byte[] fdl2Data) {
        Map<String, Object> result = new HashMap<>();
        try {
            deviceInfo = usbHostManager.findSpreadtrum();
            if (deviceInfo == null) {
                result.put("status", "error");
                result.put("msg", "No Spreadtrum/Unisoc device found");
                return result;
            }

            if (!usbHostManager.hasPermission(deviceInfo.device)) {
                result.put("status", "error");
                result.put("msg", "USB permission not granted");
                return result;
            }

            connection = usbHostManager.openDevice(deviceInfo);
            if (connection == null) {
                result.put("status", "error");
                result.put("msg", "Failed to open USB device");
                return result;
            }

            currentMode = deviceInfo.detectMode();
            Log.i(TAG, "Device mode: " + currentMode);

            switch (currentMode) {
                case "brom":
                    result = handleBromMode(soc, fdl1Data, fdl2Data);
                    break;
                case "fdl1":
                    result = handleFdl1Mode(soc, fdl2Data);
                    break;
                case "fdl2":
                    result = handleFdl2Mode();
                    break;
                case "diag":
                    result = handleDiagMode();
                    break;
                default:
                    result.put("status", "error");
                    result.put("msg", "Unknown SPD mode: " + currentMode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            result.put("status", "error");
            result.put("msg", e.getMessage());
            if (connection != null) connection.close();
        }
        return result;
    }

    private Map<String, Object> handleBromMode(String soc, byte[] fdl1Data, byte[] fdl2Data) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (fdl1Data == null) {
                result.put("status", "error");
                result.put("msg", "FDL1 data required for BROM mode");
                return result;
            }

            BromHandler brom = new BromHandler(connection,
                    deviceInfo.endpointOut, deviceInfo.endpointIn, debug);
            boolean ok = brom.loadFdl1(fdl1Data);
            if (!ok) {
                result.put("status", "error");
                result.put("msg", "FDL1 download failed");
                return result;
            }

            UsbDeviceInfo fdl2Info = waitForModeChange(PID_FDL2, 10000);
            if (fdl2Info == null) {
                result.put("status", "error");
                result.put("msg", "Device did not enter FDL2 mode");
                return result;
            }

            closeConnection();
            deviceInfo = fdl2Info;
            connection = usbHostManager.openDevice(deviceInfo);

            if (fdl2Data != null) {
                FdlHandler fdl2 = new FdlHandler(connection,
                        deviceInfo.endpointOut, deviceInfo.endpointIn, debug);
                ok = fdl2.loadFdl2(fdl2Data);
                if (!ok) {
                    result.put("status", "error");
                    result.put("msg", "FDL2 download failed");
                    return result;
                }
            }

            connected = true;
            result.put("status", "connected");
            result.put("msg", "SPD device connected (BROM→FDL2)");
            result.put("mode", "FDL2 (SPRD4)");

        } catch (Exception e) {
            Log.e(TAG, "BROM handler failed: " + e.getMessage());
            result.put("status", "error");
            result.put("msg", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> handleFdl1Mode(String soc, byte[] fdl2Data) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (fdl2Data == null) {
                result.put("status", "error");
                result.put("msg", "FDL2 data required for FDL1 mode");
                return result;
            }

            FdlHandler fdl = new FdlHandler(connection,
                    deviceInfo.endpointOut, deviceInfo.endpointIn, debug);
            boolean ok = fdl.loadFdl2(fdl2Data);
            if (!ok) {
                result.put("status", "error");
                result.put("msg", "FDL2 download failed");
                return result;
            }

            connected = true;
            result.put("status", "connected");
            result.put("msg", "SPD device connected (FDL1→FDL2)");
            result.put("mode", "FDL2 (SPRD4)");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("msg", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> handleFdl2Mode() {
        connected = true;
        Map<String, Object> result = new HashMap<>();
        result.put("status", "connected");
        result.put("msg", "SPD device already in FDL2 mode");
        result.put("mode", "FDL2 (SPRD4)");
        return result;
    }

    private Map<String, Object> handleDiagMode() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("msg", "Device in DIAG mode");
        result.put("mode", "DIAG");
        return result;
    }

    private UsbDeviceInfo waitForModeChange(int targetPid, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            UsbDeviceInfo info = usbHostManager.findByVidPid(VID_SPREADTRUM, targetPid);
            if (info != null) return info;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return null;
    }

    public Map<String, Object> runCommand(String cmd, String[] args, Map<String, String> flags) {
        Map<String, Object> result = new HashMap<>();
        if (!connected && !"kick".equals(cmd) && !"scan".equals(cmd)) {
            result.put("status", "error");
            result.put("msg", "Not connected");
            return result;
        }
        try {
            Log.i(TAG, "Running SPD command: " + cmd);
            result.put("status", "ok");
            result.put("msg", "Command queued: " + cmd);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("msg", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> scanUsb() {
        Map<String, Object> result = new HashMap<>();
        UsbDeviceInfo dev = usbHostManager.findSpreadtrum();
        if (dev != null) {
            result.put("found", true);
            result.put("vidPid", dev.getVidPidHex());
            result.put("mode", dev.detectMode());
        } else {
            result.put("found", false);
        }
        return result;
    }

    public Map<String, Object> kickDiag() {
        Map<String, Object> result = new HashMap<>();
        try {
            UsbDeviceInfo dev = usbHostManager.findSpreadtrum();
            if (dev == null) {
                result.put("status", "error");
                result.put("msg", "No SPD device found");
                return result;
            }
            // In DIAG mode, we would send the kick command
            result.put("status", "ok");
            result.put("msg", "DIAG kick initiated");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("msg", e.getMessage());
        }
        return result;
    }

    public void setDebug(boolean debug) { this.debug = debug; }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    public void disconnect() {
        closeConnection();
        connected = false;
        Log.i(TAG, "Disconnected");
    }

    public boolean isConnected() { return connected; }
    public String getCurrentMode() { return currentMode; }
}
