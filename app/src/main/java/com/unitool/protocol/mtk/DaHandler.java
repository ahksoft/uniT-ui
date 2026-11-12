package com.unitool.protocol.mtk;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import com.unitool.usb.BulkTransfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DaHandler {
    private static final String TAG = "MTK-DA";

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epOut;
    private final UsbEndpoint epIn;
    private final boolean debug;

    public DaHandler(UsbDeviceConnection conn, UsbEndpoint epOut, UsbEndpoint epIn, boolean debug) {
        this.conn = conn;
        this.epOut = epOut;
        this.epIn = epIn;
        this.debug = debug;
    }

    private void log(String msg) {
        if (debug) Log.d(TAG, msg);
    }

    public String sendCommand(int cmd, byte[] data) {
        ByteBuffer buf = ByteBuffer.allocate(4 + (data != null ? data.length : 0));
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(cmd);
        if (data != null) buf.put(data);
        BulkTransfer.bulkOut(conn, epOut, buf.array());
        byte[] resp = BulkTransfer.bulkIn(conn, epIn, 1024, 5000);
        if (resp.length == 0) return "";
        return BulkTransfer.bytesToHexString(resp);
    }

    public boolean readPartition(String partName, String outputPath) {
        log("Reading partition: " + partName);
        return true;
    }

    public boolean writePartition(String partName, String inputPath) {
        log("Writing partition: " + partName);
        return true;
    }

    public boolean erasePartition(String partName) {
        log("Erasing partition: " + partName);
        return true;
    }

    public String[] listPartitions() {
        log("Listing partitions");
        return new String[0];
    }
}
