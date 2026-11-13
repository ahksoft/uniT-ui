package com.unitool.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

public class UsbDeviceInfo {
    public final UsbDevice device;
    public final int vid;
    public final int pid;
    public final String manufacturer;
    public final String product;
    public final int deviceClass;
    public final int interfaceCount;
    public UsbInterface usbInterface;
    public UsbEndpoint endpointOut;
    public UsbEndpoint endpointIn;

    public UsbDeviceInfo(UsbDevice device) {
        this.device = device;
        this.vid = device.getVendorId();
        this.pid = device.getProductId();
        this.manufacturer = device.getManufacturerName();
        this.product = device.getProductName();
        this.deviceClass = device.getDeviceClass();
        this.interfaceCount = device.getInterfaceCount();
    }

    public String getVidPidHex() {
        return String.format("%04x:%04x", vid, pid);
    }

    public String getDisplayName() {
        String name = "";
        if (manufacturer != null) name += manufacturer + " ";
        if (product != null) name += product;
        if (name.trim().isEmpty()) name = getVidPidHex();
        return name.trim();
    }

    public boolean isSpreadtrum() {
        return vid == 0x1782;
    }

    public boolean isMediaTek() {
        return vid == 0x0E8D || vid == 0x1004 || vid == 0x22D9 || vid == 0x0FCE;
    }

    /** Boot rom product ids, per mtkclient/config/usb_ids.py. */
    public static final int[] MTK_BROM_PRODUCT_IDS = {0x0003, 0xF200, 0xD1E9, 0xD1E2, 0xD1EC, 0xD1DD};

    /** Preloader product ids, per mtkclient/config/usb_ids.py. */
    public static final int[] MTK_PRELOADER_PRODUCT_IDS = {0x6000, 0x2000, 0x2001, 0x20FF, 0x3000, 0x0006};

    /**
     * Mode of a MediaTek device.
     *
     * @return "brom", "preloader" or "unknown"
     */
    public String getMtkMode() {
        if (!isMediaTek()) return "unknown";
        for (int id : MTK_BROM_PRODUCT_IDS) if (pid == id) return "brom";
        for (int id : MTK_PRELOADER_PRODUCT_IDS) if (pid == id) return "preloader";
        return "unknown";
    }

    public String detectMode() {
        if (isMediaTek()) return "mtk";
        if (isSpreadtrum()) {
            switch (pid) {
                case 0x4d00: return "brom";
                case 0x4d01: return "fdl1";
                case 0x4d02: return "fdl2";
                case 0x4d11: return "diag";
                default: return "sprd";
            }
        }
        return "unknown";
    }

    /**
     * Human-readable USB connection mode for the UniTool status bar.
     * Android ADB/fastboot use interface class 0xff, subclass 0x42,
     * protocol 0x01 (ADB) and 0x03 (fastboot).
     */
    public String getConnectionModeLabel() {
        if (isMediaTek()) {
            String mode = getMtkMode();
            if ("brom".equals(mode)) return "MTK · BROM";
            if ("preloader".equals(mode)) return "MTK · PRELOADER";
            return "MTK";
        }
        if (isSpreadtrum()) {
            switch (pid) {
                case 0x4d00: return "UNISOC · BROM";
                case 0x4d01: return "UNISOC · FDL1";
                case 0x4d02: return "UNISOC · FDL2";
                case 0x4d11: return "UNISOC · DIAG";
                default: return "UNISOC";
            }
        }
        if (vid == 0x05C6 && pid == 0x9008) return "QUALCOMM · EDL";

        boolean adb = false;
        boolean fastboot = false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 0xFF && intf.getInterfaceSubclass() == 0x42) {
                if (intf.getInterfaceProtocol() == 0x01) adb = true;
                if (intf.getInterfaceProtocol() == 0x03) fastboot = true;
            }
        }
        if (adb && fastboot) return "ADB / FASTBOOT";
        if (adb) return "ADB";
        if (fastboot) return "FASTBOOT";
        return "USB DEVICE";
    }

    /**
     * Locates the endpoints used for the protocol.
     *
     * <p>Prefers an interface that provides both a bulk IN and a bulk OUT endpoint, so
     * that both directions stay on the same interface (the CDC control interface of a
     * preloader has no bulk endpoints at all).
     *
     * <p>Some preloaders do not show up as CDC ACM but as a HID device (product id
     * 0x20ff for example). They use interrupt endpoints, so those are used as a
     * fallback.
     */
    public void findEndpoints() {
        endpointOut = null;
        endpointIn = null;
        usbInterface = null;

        UsbInterface fallback = null;
        UsbEndpoint fallbackOut = null;
        UsbEndpoint fallbackIn = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint intfOut = null;
            UsbEndpoint intfIn = null;
            UsbEndpoint intIntfOut = null;
            UsbEndpoint intIntfIn = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                int dir = ep.getDirection();
                int type = ep.getType();
                if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (dir == UsbConstants.USB_DIR_OUT && intfOut == null) intfOut = ep;
                    if (dir == UsbConstants.USB_DIR_IN && intfIn == null) intfIn = ep;
                } else if (type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    if (dir == UsbConstants.USB_DIR_OUT && intIntfOut == null) intIntfOut = ep;
                    if (dir == UsbConstants.USB_DIR_IN && intIntfIn == null) intIntfIn = ep;
                }
            }
            if (intfOut != null && intfIn != null) {
                usbInterface = intf;
                endpointOut = intfOut;
                endpointIn = intfIn;
                return;
            }
            if (intIntfOut != null && intIntfIn != null
                    && fallbackOut == null && fallbackIn == null) {
                // HID style preloader, keep it as the fallback
                fallback = intf;
                fallbackOut = intIntfOut;
                fallbackIn = intIntfIn;
            }
            if (intfOut != null && fallbackOut == null) {
                fallback = intf;
                fallbackOut = intfOut;
                fallbackIn = intfIn;
            }
        }

        if (fallbackOut != null) {
            usbInterface = fallback;
            endpointOut = fallbackOut;
            endpointIn = fallbackIn;
        }
    }

    /** True when the endpoints of the device are bulk endpoints. */
    public boolean hasBulkEndpoints() {
        return endpointOut != null && endpointIn != null;
    }

    /** Name of the endpoint type that is used, for the log output. */
    public String getEndpointType() {
        if (endpointOut == null) return "none";
        return endpointOut.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk" : "interrupt";
    }
}
