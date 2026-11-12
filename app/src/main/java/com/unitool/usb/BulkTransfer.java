package com.unitool.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BulkTransfer {
    public static final int DEFAULT_TIMEOUT = 5000;
    public static final int MAX_BULK_SIZE = 16384;

    public static int bulkOut(UsbDeviceConnection conn, UsbEndpoint ep, byte[] data) {
        return bulkOut(conn, ep, data, 0, data.length, DEFAULT_TIMEOUT);
    }

    public static int bulkOut(UsbDeviceConnection conn, UsbEndpoint ep, byte[] data, int timeout) {
        return bulkOut(conn, ep, data, 0, data.length, timeout);
    }

    public static int bulkOut(UsbDeviceConnection conn, UsbEndpoint ep, byte[] data, int offset, int length, int timeout) {
        int total = 0;
        int max = ep.getMaxPacketSize();
        while (total < length) {
            int chunk = Math.min(length - total, Math.min(max, MAX_BULK_SIZE));
            int sent = conn.bulkTransfer(ep, data, offset + total, chunk, timeout);
            if (sent < 0) return sent;
            total += sent;
        }
        return total;
    }

    public static byte[] bulkIn(UsbDeviceConnection conn, UsbEndpoint ep, int length) {
        return bulkIn(conn, ep, length, DEFAULT_TIMEOUT);
    }

    public static byte[] bulkIn(UsbDeviceConnection conn, UsbEndpoint ep, int length, int timeout) {
        byte[] buffer = new byte[Math.min(length, Math.max(ep.getMaxPacketSize(), MAX_BULK_SIZE))];
        int received = conn.bulkTransfer(ep, buffer, buffer.length, timeout);
        if (received < 0) return new byte[0];
        byte[] result = new byte[received];
        System.arraycopy(buffer, 0, result, 0, received);
        return result;
    }

    public static byte[] bulkInExact(UsbDeviceConnection conn, UsbEndpoint ep, int length, int timeout) {
        ByteBuffer result = ByteBuffer.allocate(length);
        while (result.position() < length) {
            int remaining = length - result.position();
            int readSize = Math.min(remaining, Math.max(ep.getMaxPacketSize(), MAX_BULK_SIZE));
            byte[] buf = new byte[readSize];
            int received = conn.bulkTransfer(ep, buf, readSize, timeout);
            if (received < 0) break;
            result.put(buf, 0, received);
            if (received < ep.getMaxPacketSize()) break;
        }
        byte[] out = new byte[result.position()];
        System.arraycopy(result.array(), 0, out, 0, out.length);
        return out;
    }

    public static int controlTransfer(UsbDeviceConnection conn, int requestType, int request,
                                       int value, int index, byte[] buffer, int length) {
        return conn.controlTransfer(requestType, request, value, index, buffer, length, DEFAULT_TIMEOUT);
    }

    public static int controlTransfer(UsbDeviceConnection conn, int requestType, int request,
                                       int value, int index, byte[] buffer, int length, int timeout) {
        return conn.controlTransfer(requestType, request, value, index, buffer, length, timeout);
    }

    public static byte[] intToLeBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    public static byte[] intToBeBytes(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array();
    }

    public static int leBytesToInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public static int beBytesToInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public static byte[] hexStringToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
