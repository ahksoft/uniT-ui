package com.unitool.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

/**
 * Byte oriented reader for a USB bulk endpoint.
 *
 * <p>A bulk transfer is packet based: when the host asks for less bytes than the device
 * has sent, the rest of the packet is dropped by the host controller. The MediaTek
 * devices answer a command with the echoed command byte and the payload in one single
 * packet, so the data has to be read with a buffer that can hold a whole packet. The
 * bytes that are not needed yet are kept here, the same way the reference tool does it
 * ({@code UniTool/MTK/mtkclient/Library/Connection/usblib.py}).
 */
public class UsbReader {
    private static final String TAG = "MTK-BROM";

    private final UsbDeviceConnection conn;
    private final UsbEndpoint endpoint;
    /** Scratch buffer for a single transfer, one packet is the maximum. */
    private final byte[] packet;
    /** The bytes that were received but not used yet. */
    private byte[] data = new byte[1024];
    private int size;
    private final boolean debug;

    public UsbReader(UsbDeviceConnection conn, UsbEndpoint endpoint) {
        this(conn, endpoint, false);
    }

    public UsbReader(UsbDeviceConnection conn, UsbEndpoint endpoint, boolean debug) {
        this.conn = conn;
        this.endpoint = endpoint;
        this.debug = debug;
        int max = endpoint.getMaxPacketSize();
        if (max <= 0) max = 64;
        this.packet = new byte[max];
    }

    /** Number of bytes that are already buffered. */
    public int available() {
        return size;
    }

    /**
     * Reads exactly {@code count} bytes.
     *
     * @param count  number of bytes that are needed
     * @param timeout time in milliseconds to wait for the data
     * @return the bytes that were read, less than {@code count} when the timeout expired
     */
    public byte[] read(int count, int timeout) {
        if (count <= 0) return new byte[0];
        long deadline = System.currentTimeMillis() + timeout;
        while (size < count) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            int wait = (int) Math.min(remaining, 1000);
            int received = conn.bulkTransfer(endpoint, packet, packet.length, wait);
            if (received > 0) {
                if (debug) Log.d(TAG, "rx " + received + " bytes: " + hex(packet, received));
                append(packet, received);
            } else {
                // a timeout or a transient error, the data may still show up
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int take = Math.min(count, size);
        byte[] out = new byte[take];
        System.arraycopy(data, 0, out, 0, take);
        System.arraycopy(data, take, data, 0, size - take);
        size -= take;
        return out;
    }

    /** Reads a single byte, -1 when nothing arrives in time. */
    public int readByte(int timeout) {
        byte[] data = read(1, timeout);
        if (data.length == 0) return -1;
        return data[0] & 0xFF;
    }

    /** Throws the buffered bytes away. */
    public void flush() {
        size = 0;
    }

    /** Puts the bytes of a transfer behind the bytes that are buffered already. */
    private void append(byte[] src, int len) {
        if (size + len > data.length) {
            int capacity = Math.max(data.length * 2, size + len);
            byte[] bigger = new byte[capacity];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }
        System.arraycopy(src, 0, data, size, len);
        size += len;
    }

    private static String hex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < bytes.length; i++) {
            sb.append(String.format("%02x ", bytes[i] & 0xFF));
        }
        return sb.toString().trim();
    }
}
