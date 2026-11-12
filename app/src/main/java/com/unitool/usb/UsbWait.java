package com.unitool.usb;

/**
 * Device lookup helpers for the terminal and the usb descriptor broker.
 *
 * <p>The app does not wait for a phone. Every bundled command line tool owns its
 * device discovery loop and its own timeout ({@code adb wait-for-device}, the
 * MediaTek engine's {@code --wait}, ...). A task started from the UI therefore
 * behaves exactly like the same command typed into the terminal, and the phone
 * can be plugged in after the command has started.
 */
public final class UsbWait {

    private UsbWait() {
    }

    /** The name of a vendor, for the messages. */
    public static String vendorName(int vid) {
        switch (vid) {
            case 0x0E8D: return "MediaTek";
            case 0x05C6: return "Qualcomm";
            case 0x18D1: return "Android";
            case 0x2717: return "Xiaomi";
            default: return "usb";
        }
    }

    /** The first device of a vendor, any MediaTek device when the vendor is zero. */
    public static UsbDeviceInfo find(UsbHostManager usb, int vid) {
        if (vid == -1) {
            // adb and fastboot look for their own device, the first one that is attached
            java.util.List<UsbDeviceInfo> all = usb.listDevices();
            return all == null || all.isEmpty() ? null : all.get(0);
        }
        if (vid == 0 || vid == 0x0E8D) return usb.findMediaTek();
        java.util.List<UsbDeviceInfo> list = usb.findByVid(vid);
        return list == null || list.isEmpty() ? null : list.get(0);
    }
}
