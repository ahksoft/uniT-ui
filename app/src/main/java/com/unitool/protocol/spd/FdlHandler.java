package com.unitool.protocol.spd;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.util.Log;

import com.unitool.usb.BulkTransfer;

public class FdlHandler {
    private static final String TAG = "SPD-FDL";

    private final UsbDeviceConnection conn;
    private final UsbEndpoint epOut;
    private final UsbEndpoint epIn;
    private final boolean debug;

    public FdlHandler(UsbDeviceConnection conn, UsbEndpoint epOut, UsbEndpoint epIn, boolean debug) {
        this.conn = conn;
        this.epOut = epOut;
        this.epIn = epIn;
        this.debug = debug;
    }

    private void log(String msg) {
        if (debug) Log.d(TAG, msg);
    }

    public boolean loadFdl2(byte[] fdl2Data) {
        log("Loading FDL2 (" + fdl2Data.length + " bytes)...");
        try {
            int sent = BulkTransfer.bulkOut(conn, epOut, fdl2Data);
            log("FDL2 sent: " + sent + " bytes");

            byte[] response = BulkTransfer.bulkIn(conn, epIn, 64, 10000);
            if (response.length > 0) {
                log("FDL2 response: " + BulkTransfer.bytesToHexString(response));
            }

            Thread.sleep(500);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "FDL2 load failed: " + e.getMessage());
            return false;
        }
    }
}
