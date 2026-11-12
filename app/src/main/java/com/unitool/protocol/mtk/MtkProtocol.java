package com.unitool.protocol.mtk;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

import com.unitool.usb.UsbDeviceInfo;
import com.unitool.usb.UsbHostManager;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MediaTalk (MediaTek) protocol front end.
 *
 * <p>Handles the device detection, the BROM handshake and the optional upload of a
 * download agent (DA). The individual protocol steps live in {@link BromHandler}.
 */
public class MtkProtocol {
    private static final String TAG = "MTK-Protocol";

    /** Default load address of the download agent. */
    public static final int DEFAULT_DA_ADDRESS = 0x40001000;

    private final UsbHostManager usbHostManager;
    private UsbDeviceInfo deviceInfo;
    private UsbDeviceConnection connection;
    private BromHandler bromHandler;
    private DaHandler daHandler;
    private boolean connected = false;
    private boolean debug = false;
    private final Map<String, Object> info = new LinkedHashMap<>();

    public MtkProtocol(UsbHostManager usbHostManager) {
        this.usbHostManager = usbHostManager;
    }

    /**
     * Detects a MediaTek device, opens it and runs the BROM handshake.
     *
     * <p>Supported flags:
     * <ul>
     *   <li>{@code debug} - verbose protocol logging</li>
     *   <li>{@code skipwdt} - do not touch the watchdog timer</li>
     *   <li>{@code device} - vid:pid of the device to use</li>
     *   <li>{@code loader} - path of a download agent that is uploaded after the handshake</li>
     *   <li>{@code da_address} - load address of the download agent</li>
     * </ul>
     */
    public Map<String, Object> connect(Map<String, String> flags) {
        return connect(flags, 0, null);
    }

    /**
     * Detects a MediaTek device and connects to it.
     *
     * @param waitMs time to wait for a device, a value of zero looks for a device only
     *               once
     * @param onWait called with the remaining time while the command waits for a device
     */
    public Map<String, Object> connect(Map<String, String> flags, long waitMs,
                                       DeviceWaitListener onWait) {
        Map<String, Object> result = new LinkedHashMap<>();
        info.clear();
        // a deadline of zero means that the command does not wait at all
        long deadline = waitMs > 0 ? System.currentTimeMillis() + waitMs : 0;
        String message = "No MediaTek device found";
        Object trace = null;
        try {
            while (true) {
                usbHostManager.rescan();
                UsbDeviceInfo found = findDevice(flags);
                if (found == null) {
                    message = "No MediaTek device found";
                } else if (!usbHostManager.hasPermission(found.device)) {
                    message = "USB permission not granted";
                } else {
                    deviceInfo = found;
                    Map<String, Object> attempt = tryConnect(flags, deadline);
                    if ("connected".equals(attempt.get("status"))) {
                        return attempt;
                    }
                    message = String.valueOf(attempt.get("msg"));
                    trace = attempt.get("trace");
                }

                if (deadline <= 0) break;
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                if (onWait != null) onWait.onWait(remaining);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            message = e.getMessage();
        }
        result.put("status", "error");
        result.put("msg", message);
        if (trace != null) result.put("trace", trace);
        return result;
    }

    /** Callback for the connect command while it waits for a device. */
    public interface DeviceWaitListener {
        /** @param remainingMs time that is left before the command gives up */
        void onWait(long remainingMs);
    }

    /** Looks for the device that the flags ask for. */
    private UsbDeviceInfo findDevice(Map<String, String> flags) {
        String wanted = flags != null ? flags.get("device") : null;
        if (wanted != null && !wanted.isEmpty()) {
            return usbHostManager.findByVidPidString(wanted);
        }
        return usbHostManager.findMediaTek();
    }

    /**
     * Opens the device, runs the handshake and collects the device information.
     *
     * @param deadline time until which the attempt may work, zero means no limit
     */
    private Map<String, Object> tryConnect(Map<String, String> flags, long deadline) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (flags != null && flags.containsKey("debug")) debug = true;

            // a second connect has to release the previous one first
            if (connection != null) disconnect();

            connection = usbHostManager.openDevice(deviceInfo);
            if (connection == null) {
                result.put("status", "error");
                String reason = usbHostManager.getLastError();
                result.put("msg", "Failed to open USB device"
                        + (reason != null ? " (" + reason + ")" : ""));
                return result;
            }

            bromHandler = new BromHandler(connection,
                    deviceInfo.endpointOut, deviceInfo.endpointIn, debug);

            // the handshake may use the rest of the waiting time, the device can show up
            // at any moment
            long handshakeTimeout = deadline > 0
                    ? Math.max(1000, deadline - System.currentTimeMillis()) : 0;
            if (!bromHandler.handshake(handshakeTimeout)) {
                closeConnection();
                result.put("status", "error");
                result.put("msg", "BROM handshake failed");
                result.put("trace", bromHandler.getTrace());
                return result;
            }

            // The order of the commands matters, every answer has to be read before the
            // next command is sent (the reference does it the same way).
            bromHandler.getHwCode();

            boolean skipWdt = flags != null && flags.containsKey("skipwdt");
            if (!skipWdt) {
                bromHandler.disableWatchdog();
            }

            bromHandler.getTargetConfig();
            bromHandler.getBlVer();
            bromHandler.getVersion();
            bromHandler.getHwSwVer();

            connected = true;
            collectInfo("brom");

            // optional: upload a download agent
            String loader = flags != null ? flags.get("loader") : null;
            if (loader != null && !loader.isEmpty()) {
                int daAddress = DEFAULT_DA_ADDRESS;
                String addr = flags.get("da_address");
                if (addr != null && !addr.isEmpty()) {
                    try {
                        daAddress = (int) parseNumber(addr);
                    } catch (Exception ignored) {
                    }
                }
                boolean ok = uploadLoader(new File(loader), daAddress);
                info.put("da", ok);
                if (ok) info.put("mode", "da");
            }

            result.put("status", "connected");
            result.put("msg", "MTK device connected");
            result.putAll(info);
            result.put("trace", bromHandler.getTrace());
            Log.i(TAG, "MTK device connected: " + info.get("hwCodeHex"));

        } catch (Exception e) {
            Log.e(TAG, "Connect failed: " + e.getMessage());
            result.put("status", "error");
            result.put("msg", e.getMessage());
            if (bromHandler != null) result.put("trace", bromHandler.getTrace());
            closeConnection();
        }
        return result;
    }

    /**
     * Uploads the stage 1 of a download agent and starts it.
     *
     * <p>The address, the length and the length of the signature come from the loader
     * itself ({@link DaFile}), they are different for every chip and every version.
     */
    private boolean uploadLoader(File file, int ignoredAddress) {
        try {
            if (!file.exists()) {
                info.put("daError", "loader not found: " + file.getAbsolutePath());
                return false;
            }
            byte[] data = readFile(file);
            List<DaFile> files;
            try {
                files = DaFile.parse(data);
            } catch (IllegalArgumentException e) {
                info.put("daError", "this is not a mtk loader: " + e.getMessage());
                return false;
            }
            DaFile loader = DaFile.select(files, bromHandler.readHwCode(),
                    bromHandler.readHwSubCode(), bromHandler.readHwVersion(),
                    bromHandler.readSwVersion());
            if (loader == null) {
                loader = DaFile.select(files, bromHandler.readHwCode(), 0, 0, 0);
            }
            if (loader == null) {
                StringBuilder codes = new StringBuilder();
                for (DaFile candidate : files) {
                    if (codes.length() > 0) codes.append(", ");
                    codes.append("0x").append(Integer.toHexString(candidate.hwCode));
                }
                info.put("daError", "the loader has no entry for 0x"
                        + Integer.toHexString(bromHandler.readHwCode()) + " (it has " + codes + ")");
                return false;
            }
            info.put("daLoader", loader.toString());
            Log.i(TAG, "loader entry: " + loader);

            DaFile.Region stage1 = loader.stage1();
            if (stage1 == null) {
                info.put("daError", "the loader has no stage 1");
                return false;
            }
            byte[] stage1Data = loader.data(loader.regions.indexOf(stage1));
            Log.i(TAG, "sending stage 1: " + stage1);
            if (!bromHandler.uploadDa(stage1Data, stage1.address, stage1.signatureLength)) {
                info.put("daError", "the stage 1 of the loader was not accepted");
                return false;
            }
            Log.i(TAG, "stage 1 sent, jumping");
            if (!bromHandler.jumpDa(stage1.address)) {
                info.put("daError", "the device did not jump to the loader");
                return false;
            }
            // the agent is running and speaks the xml protocol now
            DaXml agent = new DaXml(bromHandler::write, bromHandler.getReader(),
                    message -> info.put("daLog", message), debug);
            String start = agent.receiveStart();
            if (start == null) {
                info.put("daError", "the loader did not answer after the start");
                return false;
            }
            Log.i(TAG, "agent says: " + start);
            if (!start.contains("CMD:START")) {
                info.put("daError", "the loader did not start: " + trim(start));
                return false;
            }
            info.put("daStarted", true);

            String runtime = agent.setRuntimeParameter("INFO", "UART", "LINUX", true);
            Log.i(TAG, "runtime parameters: " + runtime);
            String commands = agent.hostSupportedCommands();
            Log.i(TAG, "host capabilities: " + commands);
            String initHw = agent.notifyInitHw();
            Log.i(TAG, "init hardware: " + initHw);
            String hostInfo = agent.setHostInfo("");
            Log.i(TAG, "host info: " + hostInfo);
            info.put("da", true);
            info.put("mode", "da");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "loader upload failed", e);
            info.put("daError", e.getMessage());
            return false;
        }
    }

    private static String trim(String text) {
        if (text == null) return "";
        String one = text.replace("\n", " ").replace("\r", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }

    private static byte[] readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return data;
    }

    /** Fills the info map with everything that is known about the device. */
    private void collectInfo(String mode) {
        info.clear();
        if (deviceInfo != null) {
            info.put("device", deviceInfo.getVidPidHex());
            info.put("name", deviceInfo.getDisplayName());
            info.put("mtkMode", deviceInfo.getMtkMode());
            info.put("interfaces", deviceInfo.interfaceCount);
        }
        if (bromHandler != null) {
            info.put("hwCode", bromHandler.readHwCode());
            info.put("hwCodeHex", String.format("0x%04x", bromHandler.readHwCode()));
            info.put("hwSubCode", bromHandler.readHwSubCode());
            info.put("hwVersion", bromHandler.readHwVersion());
            info.put("swVersion", bromHandler.readSwVersion());
            info.put("blVersion", bromHandler.readBlVersion());
            info.put("bromVersion", bromHandler.readBromVersion());
            info.put("targetConfig", bromHandler.readTargetConfig());
            info.put("targetConfigHex", String.format("0x%08x", bromHandler.readTargetConfig()));
            info.put("daa", bromHandler.isDaaRequired());
            info.put("sla", bromHandler.isSlaRequired());
            info.put("bromMode", bromHandler.isBromMode());
        }
        info.put("mode", mode);
    }

    /** Everything that is known about the current connection. */
    public Map<String, Object> getInfo() {
        return new LinkedHashMap<>(info);
    }

    /** Parses decimal or hexadecimal (0x prefixed) numbers. */
    public static long parseNumber(String value) {
        String v = value.trim();
        if (v.startsWith("0x") || v.startsWith("0X")) {
            return Long.parseLong(v.substring(2), 16);
        }
        return Long.parseLong(v);
    }

    public Map<String, Object> runCommand(String cmd, String[] args, Map<String, String> flags) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!connected) {
            result.put("status", "error");
            result.put("msg", "Not connected");
            return result;
        }
        try {
            Log.i(TAG, "Running MTK command: " + cmd);
            result.put("status", "ok");
            result.put("msg", "Command queued: " + cmd);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("msg", e.getMessage());
        }
        return result;
    }

    public Map<String, Object> loadPartitions() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parts", new String[0]);
        return result;
    }

    public void disconnect() {
        closeConnection();
        connected = false;
        bromHandler = null;
        daHandler = null;
        info.clear();
        Log.i(TAG, "Disconnected");
    }

    private void closeConnection() {
        if (deviceInfo != null) {
            usbHostManager.closeDevice(deviceInfo.device);
        } else if (connection != null) {
            connection.close();
        }
        connection = null;
    }

    public boolean isConnected() { return connected; }
    public BromHandler getBromHandler() { return bromHandler; }
    public UsbDeviceInfo getDeviceInfo() { return deviceInfo; }
    public DaHandler getDaHandler() { return daHandler; }
}
