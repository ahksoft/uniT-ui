#include <libusb.h>

#include <cstdio>
#include <string>
#include <vector>

namespace usb_bridge {
std::vector<std::string> get_devices();
}

namespace {
void printLibusbError(const char* operation, int error) {
    const char* name = libusb_error_name(error);
    std::printf("%s=FAILED LIBUSB_ERROR=%s (%d)\n",
                operation, name != nullptr ? name : "UNKNOWN", error);
}
}

int main() {
    // Query the same app-local broker API used by UniTool's patched libusb.
    const std::vector<std::string> brokerDevices = usb_bridge::get_devices();
    std::printf("UNITOOL_USB_BROKER_DEVICES=%zu\n", brokerDevices.size());
    for (const std::string& name : brokerDevices) {
        std::printf("BROKER_DEVICE=%s\n", name.c_str());
    }

    libusb_context* context = nullptr;
    int result = libusb_init(&context);
    if (result != LIBUSB_SUCCESS) {
        printLibusbError("LIBUSB_INIT", result);
        return 10;
    }

    libusb_device** devices = nullptr;
    const ssize_t count = libusb_get_device_list(context, &devices);
    if (count < 0) {
        printLibusbError("LIBUSB_ENUMERATION", static_cast<int>(count));
        libusb_exit(context);
        return 11;
    }

    std::printf("LIBUSB_DEVICES=%zd\n", count);
    bool allOpened = true;
    for (ssize_t index = 0; index < count; ++index) {
        libusb_device* device = devices[index];
        libusb_device_descriptor descriptor{};
        result = libusb_get_device_descriptor(device, &descriptor);
        if (result != LIBUSB_SUCCESS) {
            printLibusbError("DEVICE_DESCRIPTOR", result);
            allOpened = false;
            continue;
        }

        std::printf(
            "DEVICE=%03u:%03u VID=%04X PID=%04X",
            static_cast<unsigned int>(libusb_get_bus_number(device)),
            static_cast<unsigned int>(libusb_get_device_address(device)),
            descriptor.idVendor,
            descriptor.idProduct
        );

        // Opening exercises the backend's broker FD lookup and SCM_RIGHTS
        // receive path. Do not claim an interface or send device requests.
        libusb_device_handle* handle = nullptr;
        result = libusb_open(device, &handle);
        if (result == LIBUSB_SUCCESS && handle != nullptr) {
            std::puts(" OPEN=YES");
            libusb_close(handle);
        } else {
            std::puts(" OPEN=NO");
            printLibusbError("LIBUSB_OPEN", result);
            allOpened = false;
        }
    }

    libusb_free_device_list(devices, 1);
    libusb_exit(context);

    if (brokerDevices.empty() && count == 0) {
        std::puts("RESULT=NO_AUTHORIZED_SESSIONS");
        return 2;
    }

    if (static_cast<size_t>(count) != brokerDevices.size()) {
        std::printf(
            "RESULT=DEVICE_COUNT_MISMATCH BROKER=%zu LIBUSB=%zd\n",
            brokerDevices.size(), count
        );
        return 12;
    }

    if (!allOpened) {
        std::puts("RESULT=OPEN_FAILURE");
        return 13;
    }

    std::puts("RESULT=PASS");
    return 0;
}
