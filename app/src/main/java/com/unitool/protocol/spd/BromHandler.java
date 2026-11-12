package com.unitool.protocol.spd;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import com.unitool.usb.BulkTransfer;

public class BromHandler {
    private static final String TAG = "SPD-BROM";

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epOut;
    private final UsbEndpoint epIn;
    private final boolean debug;

    public BromHandler(UsbDeviceConnection conn, UsbEndpoint epOut, UsbEndpoint epIn, boolean debug) {
        this.conn = conn;
        this.epOut = epOut;
        this.epIn = epIn;
        this.debug = debug;
    }

    private void log(String msg) {
        if (debug) Log.d(TAG, msg);
    }

    public boolean loadFdl1(byte[] fdl1Data) {
        log("Loading FDL1 (" + fdl1Data.length + " bytes)...");
        try {
            int sent = BulkTransfer.bulkOut(conn, epOut, fdl1Data);
            log("FDL1 sent: " + sent + " bytes");

            byte[] response = BulkTransfer.bulkIn(conn, epIn, 64, 10000);
            if (response.length > 0) {
                log("FDL1 response: " + BulkTransfer.bytesToHexString(response));
            }

            Thread.sleep(500);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "FDL1 load failed: " + e.getMessage());
            return false;
        }
    }
}
