/*
 * Read-only MediaTek USB connectivity probe for UniTool.
 *
 * Uses the existing libusb Android broker backend, which can only obtain
 * UsbDeviceConnection descriptors authorized by UsbManager. This probe
 * enumerates MediaTek VID 0x0E8D devices and opens/closes the libusb handle.
 * It does not claim interfaces or send device commands.
 */
#include <libusb.h>

#include <cstdio>

namespace {
constexpr unsigned short kMediaTekVendorId = 0x0E8D;
}

int main() {
    libusb_context* context = nullptr;
    int result = libusb_init(&context);
    if (result != LIBUSB_SUCCESS) {
        std::fprintf(stderr, "libusb_init failed: %s\n", libusb_error_name(result));
        return 1;
    }

    libusb_device** devices = nullptr;
    const ssize_t count = libusb_get_device_list(context, &devices);
    if (count < 0) {
        std::fprintf(stderr, "USB enumeration failed: %s\n",
                     libusb_error_name(static_cast<int>(count)));
        libusb_exit(context);
        return 1;
    }

    unsigned int found = 0;
    for (ssize_t index = 0; index < count; ++index) {
        libusb_device* device = devices[index];
        libusb_device_descriptor descriptor{};
        result = libusb_get_device_descriptor(device, &descriptor);
        if (result != LIBUSB_SUCCESS || descriptor.idVendor != kMediaTekVendorId) {
            continue;
        }

        ++found;
        libusb_device_handle* handle = nullptr;
        const int open_result = libusb_open(device, &handle);

        std::printf(
            "MediaTek USB device: VID=%04X PID=%04X bus=%03u address=%03u "
            "USB=%04X device=%04X class=%02X subclass=%02X protocol=%02X "
            "configurations=%u open=%s",
            descriptor.idVendor,
            descriptor.idProduct,
            libusb_get_bus_number(device),
            libusb_get_device_address(device),
            descriptor.bcdUSB,
            descriptor.bcdDevice,
            descriptor.bDeviceClass,
            descriptor.bDeviceSubClass,
            descriptor.bDeviceProtocol,
            descriptor.bNumConfigurations,
            open_result == LIBUSB_SUCCESS ? "ok" : "failed");

        if (open_result != LIBUSB_SUCCESS) {
            std::printf(" error=%s", libusb_error_name(open_result));
        }
        std::printf("\n");

        if (handle != nullptr) {
            libusb_close(handle);
        }
    }

    if (found == 0) {
        std::puts("No authorized MediaTek USB device found (VID 0x0E8D).");
    }

    libusb_free_device_list(devices, 1);
    libusb_exit(context);
    return 0;
}
