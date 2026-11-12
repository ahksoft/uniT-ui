package com.unitool.protocol.mtk;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import com.unitool.usb.BulkTransfer;
import com.unitool.usb.UsbReader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MediaTek BROM (boot rom) protocol.
 *
 * <p>Byte level behaviour follows the reference implementation bundled with this project
 * ({@code UniTool/MTK/mtkclient/Library/Port.py} and {@code mtk_preloader.py}):
 *
 * <ul>
 *   <li>handshake: {@code A0 0A 50 05}, one byte at a time, every byte echoed back
 *       inverted ({@code ~b & 0xFF})</li>
 *   <li>one byte commands are echoed by the device - the echo has to be consumed</li>
 *   <li>32 bit parameters are big endian and echoed back by the device, the echo must be
 *       read, otherwise every following read is misaligned</li>
 *   <li>status words are 2 bytes big endian, the status of the register write commands
 *       is a 4 byte little endian word</li>
 * </ul>
 */
public class BromHandler {
    private static final String TAG = "MTK-BROM";

    // commands
    private static final byte CMD_WRITE16 = (byte) 0xD2;
    private static final byte CMD_WRITE32 = (byte) 0xD4;
    private static final byte CMD_JUMP_DA = (byte) 0xD5;
    private static final byte CMD_SEND_DA = (byte) 0xD7;
    private static final byte CMD_GET_TARGET_CONFIG = (byte) 0xD8;
    private static final byte CMD_JUMP_DA64 = (byte) 0xDE;
    private static final byte CMD_GET_ME_ID = (byte) 0xE1;
    private static final byte CMD_GET_HW_SW_VER = (byte) 0xFC;
    private static final byte CMD_GET_HW_CODE = (byte) 0xFD;
    private static final byte CMD_GET_BL_VER = (byte) 0xFE;
    private static final byte CMD_GET_VERSION = (byte) 0xFF;

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epOut;
    private final UsbEndpoint epIn;
    private final UsbReader reader;
    private final boolean debug;
    private final List<String> trace = new ArrayList<>();

    private int hwCode;
    private int hwSubCode;
    private int hwVersion;
    private int swVersion;
    private int blVersion;
    private int bromVersion;
    private int targetConfig;
    private int targetStatus;
    private byte[] meId;
    private boolean bromMode;

    public BromHandler(UsbDeviceConnection conn, UsbEndpoint epOut, UsbEndpoint epIn, boolean debug) {
        this.conn = conn;
        this.epOut = epOut;
        this.epIn = epIn;
        this.reader = new UsbReader(conn, epIn, debug);
        this.debug = debug;
    }

    public BromHandler(UsbDeviceConnection conn, UsbEndpoint epOut, UsbEndpoint epIn) {
        this(conn, epOut, epIn, false);
    }

    private void log(String msg) {
        if (debug) Log.d(TAG, msg);
    }

    private void trace(String step) {
        trace.add(step);
        log(step);
    }

    /** Ordered log of everything that happened on the wire, useful for diagnostics. */
    public List<String> getTrace() {
        return Collections.unmodifiableList(trace);
    }

    // ------------------------------------------------------------------
    // low level io
    // ------------------------------------------------------------------

    private byte[] writeRead(byte[] data, int readLen) {
        int sent = BulkTransfer.bulkOut(conn, epOut, data);
        if (sent < 0) throw new RuntimeException("Bulk write failed");
        return reader.read(readLen, 5000);
    }

    /** Writes a one byte command and consumes the echoed command byte. */
    private byte sendCommand(byte cmd) {
        BulkTransfer.bulkOut(conn, epOut, new byte[]{cmd});
        int echo = reader.readByte(5000);
        if (echo < 0) throw new RuntimeException("No echo for cmd " + String.format("%02x", cmd));
        if (echo != (cmd & 0xFF)) {
            throw new RuntimeException(String.format("Echo mismatch for cmd %02x, got %02x",
                    cmd, echo));
        }
        return (byte) echo;
    }

    /**
     * Writes a 32 bit big endian parameter and consumes the 4 byte echo the device
     * sends back. Skipping that echo desynchronizes the whole command stream.
     */
    private void sendWord32(int value) {
        byte[] data = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
        BulkTransfer.bulkOut(conn, epOut, data);
        byte[] echo = reader.read(4, 5000);
        if (echo.length < 4) throw new RuntimeException("Short echo for parameter");
        int echoed = ByteBuffer.wrap(echo).order(ByteOrder.BIG_ENDIAN).getInt();
        if (echoed != value) {
            throw new RuntimeException(String.format("Parameter echo mismatch: sent %08x got %08x",
                    value, echoed));
        }
    }

    private int readWord32() {
        byte[] data = reader.read(4, 5000);
        if (data.length < 4) throw new RuntimeException("Short word32 read");
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private int readWord16() {
        byte[] data = reader.read(2, 5000);
        if (data.length < 2) throw new RuntimeException("Short word16 read");
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
    }

    /** 2 byte big endian status word. */
    private int readStatus() {
        byte[] data = reader.read(2, 5000);
        if (data.length < 2) throw new RuntimeException("No status byte");
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
    }

    /**
     * 4 byte little endian status word, used by the register write commands.
     *
     * <p>The device does not always send the status of a register write. The reference
     * implementation reads it without a timeout and takes a missing answer as zero, this
     * does the same so a write that the device accepted is not reported as an error.
     */
    private int readStatusWord(int timeoutMs) {
        byte[] data = reader.read(4, timeoutMs);
        if (data.length == 0) {
            trace("no status word from the device, assuming ok");
            return 0;
        }
        byte[] word = new byte[4];
        System.arraycopy(data, 0, word, 0, Math.min(4, data.length));
        return ByteBuffer.wrap(word).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    // ------------------------------------------------------------------
    // handshake
    // ------------------------------------------------------------------

    public boolean handshake() {
        return handshake(0);
    }

    /**
     * Runs the handshake.
     *
     * @param timeoutMs time the handshake may take, a value of zero means no limit
     */
    public boolean handshake(long timeoutMs) {
        log("Starting BROM handshake...");
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : 0;
        byte[] handshakeBytes = new byte[]{(byte) 0xa0, (byte) 0x0a, 0x50, 0x05};
        for (int attempt = 0; attempt < 100; attempt++) {
            if (deadline > 0 && System.currentTimeMillis() >= deadline) break;
            try {
                reader.flush();
                for (byte b : handshakeBytes) {
                    int sent = conn.bulkTransfer(epOut, new byte[]{b}, 1, 200);
                    if (sent < 0) throw new RuntimeException("HS write fail");
                    byte expected = (byte) (~b & 0xFF);
                    // a preloader greets with "READY" and the first attempt sees those
                    // bytes, bytes that are not the answer of the device are skipped
                    int resp = -1;
                    for (int skip = 0; skip < 12; skip++) {
                        resp = reader.readByte(500);
                        if (resp < 0) throw new RuntimeException("HS no response");
                        if (resp == (expected & 0xFF)) break;
                        trace(String.format("handshake: skipped 0x%02x", resp));
                    }
                    if (resp != (expected & 0xFF)) {
                        throw new RuntimeException("HS echo mismatch: sent=" +
                                String.format("%02x", b) + " got=" + String.format("%02x", resp));
                    }
                }
                trace("handshake ok (attempt " + (attempt + 1) + ")");
                log("Handshake succeeded on attempt " + (attempt + 1));
                return true;
            } catch (Exception e) {
                if (attempt == 0 || attempt % 10 == 0) {
                    Log.w(TAG, "Handshake attempt " + (attempt + 1) + " failed: " + e.getMessage());
                }
                flushInput();
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            }
        }
        Log.e(TAG, "Handshake failed");
        trace("handshake failed");
        return false;
    }

    /** Drops stale bytes so a failed attempt cannot desynchronize the next one. */
    private void flushInput() {
        try {
            // a preloader sends "READY" in a loop until the handshake starts, the bytes
            // are read until the line stays quiet for a moment
            for (int i = 0; i < 20; i++) {
                if (reader.read(64, 100).length == 0) break;
            }
        } catch (Exception ignored) {
        }
        reader.flush();
    }

    // ------------------------------------------------------------------
    // information commands
    // ------------------------------------------------------------------

    /**
     * GET_HW_CODE (0xFD): the device echoes the command and answers with a 32 bit value
     * that holds the hw code in the upper and the hw version in the lower 16 bits.
     */
    public boolean getHwCode() {
        try {
            sendCommand(CMD_GET_HW_CODE);
            int value = readWord32();
            hwCode = (value >>> 16) & 0xFFFF;
            hwVersion = value & 0xFFFF;
            trace(String.format("hw code 0x%04x, hw version 0x%04x", hwCode, hwVersion));
            log("HW_CODE: 0x" + Integer.toHexString(hwCode));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getHwCode failed: " + e.getMessage());
            trace("getHwCode failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * GET_HW_SW_VER (0xFC): 8 bytes, four big endian half words. The device echoes the
     * command byte before the answer, the echo is consumed here.
     */
    public boolean getHwSwVer() {
        try {
            BulkTransfer.bulkOut(conn, epOut, new byte[]{CMD_GET_HW_SW_VER});
            byte[] data = new byte[8];
            int first = reader.readByte(5000);
            if (first < 0) throw new RuntimeException("No hw/sw version response");
            int offset;
            if (first == (CMD_GET_HW_SW_VER & 0xFF)) {
                byte[] rest = reader.read(8, 5000);
                if (rest.length < 8) throw new RuntimeException("Short hw/sw version read");
                System.arraycopy(rest, 0, data, 0, 8);
            } else {
                data[0] = (byte) first;
                byte[] rest = reader.read(7, 5000);
                if (rest.length < 7) throw new RuntimeException("Short hw/sw version read");
                System.arraycopy(rest, 0, data, 1, 7);
            }
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            hwSubCode = buf.getShort() & 0xFFFF;
            hwVersion = buf.getShort() & 0xFFFF;
            swVersion = buf.getShort() & 0xFFFF;
            trace(String.format("hw sub code 0x%04x, hw ver 0x%04x, sw ver 0x%04x",
                    hwSubCode, hwVersion, swVersion));
            log("HW_SUBCODE: 0x" + Integer.toHexString(hwSubCode)
                    + " HW_VER: 0x" + Integer.toHexString(hwVersion)
                    + " SW_VER: " + swVersion);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getHwSwVer failed: " + e.getMessage());
            trace("getHwSwVer failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * GET_BL_VER (0xFE): a single byte. In BROM mode the device answers with the echoed
     * command byte, in preloader mode with the version of the bootloader.
     */
    public boolean getBlVer() {
        try {
            BulkTransfer.bulkOut(conn, epOut, new byte[]{CMD_GET_BL_VER});
            int res = reader.readByte(5000);
            if (res < 0) throw new RuntimeException("No bl version response");
            if (res == (CMD_GET_BL_VER & 0xFF)) {
                bromMode = true;
                trace("bl version: BROM mode detected");
            }
            blVersion = res;
            trace(String.format("bl version 0x%02x", blVersion));
            log("BL_VER: 0x" + Integer.toHexString(blVersion));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getBlVer failed: " + e.getMessage());
            trace("getBlVer failed: " + e.getMessage());
            return false;
        }
    }

    /** GET_VERSION (0xFF): a single byte, the version of the boot rom. */
    public boolean getVersion() {
        try {
            BulkTransfer.bulkOut(conn, epOut, new byte[]{CMD_GET_VERSION});
            int res = reader.readByte(5000);
            if (res < 0) throw new RuntimeException("No version response");
            // in brom mode the device only echoes the command, so the value equal to the
            // command says nothing about the mode, see getBlVer
            bromVersion = res;
            trace(String.format("brom version 0x%02x", bromVersion));
            log("BROM_VER: 0x" + Integer.toHexString(bromVersion));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getVersion failed: " + e.getMessage());
            trace("getVersion failed: " + e.getMessage());
            return false;
        }
    }

    /** GET_TARGET_CONFIG (0xD8): echoed command, 4 byte config and a 2 byte status. */
    public boolean getTargetConfig() {
        try {
            sendCommand(CMD_GET_TARGET_CONFIG);
            byte[] config = reader.read(6, 5000);
            if (config.length < 6) throw new RuntimeException("Short target config");
            targetConfig = ByteBuffer.wrap(config, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            targetStatus = ByteBuffer.wrap(config, 4, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
            trace(String.format("target config 0x%08x (status 0x%04x)", targetConfig, targetStatus));
            log("TARGET_CONFIG: 0x" + Integer.toHexString(targetConfig));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "getTargetConfig failed: " + e.getMessage());
            trace("getTargetConfig failed: " + e.getMessage());
            return false;
        }
    }

    /** GET_ME_ID (0xE1): echoed command, 4 byte length, the MEID itself and a 2 byte status. */
    public boolean getMeId() {
        try {
            sendCommand(CMD_GET_ME_ID);
            int length = readWord32();
            if (length < 0 || length > 64) throw new RuntimeException("Invalid MEID length " + length);
            byte[] data = reader.read(length, 5000);
            if (data.length < length) throw new RuntimeException("Short MEID read");
            meId = data;
            int status = readStatus();
            trace("meid: " + BulkTransfer.bytesToHexString(meId)
                    + " (status 0x" + Integer.toHexString(status) + ")");
            log("MEID: " + BulkTransfer.bytesToHexString(meId));
            return status == 0;
        } catch (Exception e) {
            Log.e(TAG, "getMeId failed: " + e.getMessage());
            trace("getMeId failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // download agent
    // ------------------------------------------------------------------

    /**
     * SEND_DA (0xD7): sends the download agent to the given address.
     *
     * @param daData the agent including its signature
     * @param address load address
     * @param sigLen  length of the signature at the end of {@code daData}
     */
    public boolean uploadDa(byte[] daData, int address, int sigLen) {
        try {
            if (daData == null || daData.length == 0) throw new RuntimeException("No DA data");
            // the loader is sent as half words, an odd byte at the end is padded
            byte[] payload = daData;
            if (payload.length % 2 != 0) {
                payload = new byte[daData.length + 1];
                System.arraycopy(daData, 0, payload, 0, daData.length);
            }
            int size = payload.length;

            log("Uploading DA: addr=0x" + Integer.toHexString(address) + " size=" + size
                    + " sig=" + sigLen);

            sendCommand(CMD_SEND_DA);
            sendWord32(address);
            sendWord32(size);
            sendWord32(sigLen);
            int status = readStatus();
            if (status == 0x1D0D) {
                trace("SLA required");
                throw new RuntimeException("SLA authentication required (not supported)");
            }
            if (status > 0xFF) {
                throw new RuntimeException(String.format("SEND_DA status 0x%04x", status));
            }

            int checksum = checksum16(payload);

            int maxPacket = epOut.getMaxPacketSize() > 0 ? epOut.getMaxPacketSize() : 0x400;
            int pos = 0;
            while (pos < payload.length) {
                int chunk = Math.min(payload.length - pos, maxPacket);
                BulkTransfer.bulkOut(conn, epOut, payload, pos, chunk, 10000);
                pos += chunk;
                if (pos % 0x2000 == 0) BulkTransfer.bulkOut(conn, epOut, new byte[0]);
            }
            BulkTransfer.bulkOut(conn, epOut, new byte[0]);
            try { Thread.sleep(120); } catch (InterruptedException ignored) { }

            byte[] trailer = reader.read(4, 5000);
            if (trailer.length < 4) throw new RuntimeException("No reply from DA loader");
            ByteBuffer buf = ByteBuffer.wrap(trailer).order(ByteOrder.BIG_ENDIAN);
            int deviceChecksum = buf.getShort() & 0xFFFF;
            int uploadStatus = buf.getShort() & 0xFFFF;
            if (deviceChecksum != 0 && deviceChecksum != checksum) {
                trace(String.format("checksum mismatch (device 0x%04x, expected 0x%04x)",
                        deviceChecksum, checksum));
            }
            trace(String.format("DA upload status 0x%04x", uploadStatus));
            return uploadStatus <= 0xFF;
        } catch (Exception e) {
            Log.e(TAG, "uploadDa failed: " + e.getMessage());
            trace("uploadDa failed: " + e.getMessage());
            return false;
        }
    }

    /** XOR checksum over little endian half words, as used by the reference loader. */
    private static int checksum16(byte[] data) {
        int sum = 0;
        for (int i = 0; i + 1 < data.length; i += 2) {
            sum ^= (data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8);
        }
        if ((data.length & 1) != 0) sum ^= (data[data.length - 1] & 0xFF);
        return sum & 0xFFFF;
    }

    /** JUMP_DA (0xD5): the device echoes the address back and answers with a status. */
    public boolean jumpDa(int address) {
        try {
            log("Jumping to DA at 0x" + Integer.toHexString(address));
            sendCommand(CMD_JUMP_DA);
            sendWord32(address);
            int status = readStatus();
            trace(String.format("jump DA status 0x%04x", status));
            return status == 0;
        } catch (Exception e) {
            Log.e(TAG, "jumpDa failed: " + e.getMessage());
            trace("jumpDa failed: " + e.getMessage());
            return false;
        }
    }

    /** JUMP_DA64 (0xDE): like JUMP_DA, but the mode byte selects a 64 bit jump. */
    public boolean jumpDa64(int address) {
        try {
            log("Jumping to DA64 at 0x" + Integer.toHexString(address));
            sendCommand(CMD_JUMP_DA64);
            sendWord32(address);
            BulkTransfer.bulkOut(conn, epOut, new byte[]{0x01}); // 64 bit marker
            int status = readStatus();
            trace(String.format("jump DA64 status 0x%04x", status));
            return status == 0;
        } catch (Exception e) {
            Log.e(TAG, "jumpDa64 failed: " + e.getMessage());
            trace("jumpDa64 failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // register access
    // ------------------------------------------------------------------

    /**
     * Disables the watchdog timer.
     *
     * <p>Uses the BROM register write commands (0xD2 for 16 bit, 0xD4 for 32 bit access).
     * Every parameter is echoed by the device, the status words are 2 bytes big endian.
     */
    public boolean disableWatchdog() {
        try {
            int addr = getWatchdogAddr();
            boolean use16 = (hwCode == 0x6575 || hwCode == 0x6577);
            int value = getWatchdogValue();
            trace(String.format("disabling watchdog at 0x%08x value 0x%08x (%d bit write)",
                    addr, value, use16 ? 16 : 32));
            boolean ok = use16 ? writeRegister16(addr, value) : writeRegister32(addr, value);
            if (ok) trace("watchdog disabled");
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "disableWatchdog failed: " + e.getMessage());
            trace("disableWatchdog failed: " + e.getMessage());
            return false;
        }
    }

    /** The value that stops the watchdog, see the reference {@code get_watchdog_addr()}. */
    public int getWatchdogValue() {
        switch (getWatchdogAddr()) {
            case 0x10007000: return 0x22000064;
            case 0x10212000: return 0x22000000;
            case 0x10211000: return 0x22000064;
            case 0x10007400: return 0x22000000;
            case 0xC0000000: return 0x2264;
            case 0xA0030000: return 0x2200;
            case 0x1C00A000: return 0x22000064;
            case 0x2200:
                switch (hwCode) {
                    case 0x6276:
                    case 0x8163: return 0x610C0000;
                    case 0x6251:
                    case 0x6516: return 0x80030000;
                    case 0x6255: return 0x701E0000;
                    default: return 0x70025000;
                }
            default: return 0x22000000;
        }
    }

    /** WRITE32 (0xD4): 32 bit register write. */
    public boolean writeRegister32(int addr, int value) {
        try {
            sendCommand(CMD_WRITE32);
            sendWord32(addr);
            sendWord32(1); // one value follows
            int status = readStatusWord(1500);
            if (status > 0xFF) throw new RuntimeException(String.format("write32 status 0x%04x", status));
            // the value is only accepted when the status allows it, sending it anyway
            // would desynchronize the command stream
            if (status > 3) throw new RuntimeException(String.format("write32 status 0x%04x", status));
            byte[] payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
            BulkTransfer.bulkOut(conn, epOut, payload);
            byte[] echo = reader.read(4, 5000);
            int echoed = echo.length == 4
                    ? ByteBuffer.wrap(echo).order(ByteOrder.BIG_ENDIAN).getInt() : value;
            if (echo.length == 4 && echoed != value) {
                throw new RuntimeException(String.format("value echo mismatch: %08x != %08x",
                        echoed, value));
            }
            int status2 = readStatusWord(1500);
            if (status2 > 0xFF) throw new RuntimeException(String.format("write32 status 0x%04x", status2));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "writeRegister32 failed: " + e.getMessage());
            trace("writeRegister32 failed: " + e.getMessage());
            return false;
        }
    }

    /** WRITE16 (0xD2): 16 bit register write. */
    public boolean writeRegister16(int addr, int value) {
        try {
            sendCommand(CMD_WRITE16);
            sendWord32(addr);
            sendWord32(1); // one value follows
            int status = readStatusWord(1500);
            if (status > 0xFF) throw new RuntimeException(String.format("write16 status 0x%04x", status));
            if (status > 3) throw new RuntimeException(String.format("write16 status 0x%04x", status));
            byte[] payload = new byte[]{(byte) (value >>> 8), (byte) value};
            BulkTransfer.bulkOut(conn, epOut, payload);
            byte[] echo = reader.read(2, 5000);
            if (echo.length == 2) {
                int echoed = ByteBuffer.wrap(echo).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                if (echoed != (value & 0xFFFF)) {
                    throw new RuntimeException(String.format("value echo mismatch: %04x != %04x",
                            echoed, value & 0xFFFF));
                }
            }
            int status2 = readStatusWord(1500);
            if (status2 > 0xFF) throw new RuntimeException(String.format("write16 status 0x%04x", status2));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "writeRegister16 failed: " + e.getMessage());
            trace("writeRegister16 failed: " + e.getMessage());
            return false;
        }
    }

    /** The reader of the connection, the download agent protocol uses it too. */
    public UsbReader getReader() {
        return reader;
    }

    /** Sends raw bytes to the device. */
    public int write(byte[] data) {
        return BulkTransfer.bulkOut(conn, epOut, data);
    }

    public int getWatchdogAddr() {
        switch (hwCode) {
            case 0x6577: return 0x10007000;
            case 0x6578: return 0x10007000;
            case 0x6580: return 0x10007000;
            case 0x6583: return 0x10007000;
            case 0x6735: return 0x10007000;
            case 0x6737: return 0x10007000;
            case 0x6739: return 0x10007000;
            case 0x6750: return 0x10007000;
            case 0x6752: return 0x10007000;
            case 0x6753: return 0x10007000;
            case 0x6755: return 0x10007000;
            case 0x6757: return 0x10007000;
            case 0x6761: return 0x10007000;
            case 0x6763: return 0x10007000;
            case 0x6765: return 0x10007000;
            case 0x6768: return 0x10007000;
            case 0x6771: return 0x10007000;
            case 0x6779: return 0x10007000;
            case 0x6780: return 0x10007000;
            case 0x6785: return 0x10007000;
            case 0x6797: return 0x10007000;
            case 0x6799: return 0x10007000;
            case 0x6853: return 0x10007000;
            case 0x6877: return 0x10007000;
            case 0x6879: return 0x10007000;
            case 0x6880: return 0x10007000;
            case 0x6883: return 0x10007000;
            case 0x6885: return 0x10007000;
            case 0x6886: return 0x10007000;
            case 0x6889: return 0x10007000;
            case 0x6891: return 0x10007000;
            case 0x6893: return 0x10007000;
            case 0x6895: return 0x10007000;
            case 0x6897: return 0x10007000;
            default: return 0x10007000;
        }
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    public boolean isDaaRequired() {
        return (targetConfig & 0x40) != 0;
    }

    public boolean isSlaRequired() {
        return (targetConfig & 0x20) != 0;
    }

    /** True when the device answered GET_BL_VER with the command echo, i.e. it is a BROM. */
    public boolean isBromMode() {
        return bromMode;
    }

    public int readHwCode() { return hwCode; }
    public int readHwSubCode() { return hwSubCode; }
    public int readHwVersion() { return hwVersion; }
    public int readSwVersion() { return swVersion; }
    public int readBlVersion() { return blVersion; }
    public int readBromVersion() { return bromVersion; }
    public int readTargetConfig() { return targetConfig; }
    public int readTargetStatus() { return targetStatus; }
    public byte[] readMeId() { return meId; }
}
