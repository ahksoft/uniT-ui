#include <libusb.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

namespace usb_bridge {
std::vector<std::string> get_devices();
int get_fd(const std::string& device_name);
}

static void print_error(const char* operation, int result) {
    const char* name = libusb_error_name(result);
    std::printf("%s=FAILED LIBUSB_ERROR=%s (%d)\n",
                operation, name ? name : "UNKNOWN", result);
}

static void print_fd_path(int fd) {
    char proc_path[64];
    char target[256];
    std::snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    const ssize_t length = readlink(proc_path, target, sizeof(target) - 1);
    if (length >= 0) {
        target[length] = '\0';
        std::printf("FD_PATH=%s\n", target);
    } else {
        std::printf("FD_PATH=UNAVAILABLE errno=%d (%s)\n", errno, std::strerror(errno));
    }
}

int main() {
    const auto devices = usb_bridge::get_devices();
    std::printf("UNITOOL_USB_BROKER_DEVICES=%zu\n", devices.size());
    if (devices.empty()) {
        std::puts("RESULT=NO_AUTHORIZED_SESSIONS");
        return 2;
    }

    libusb_context* context = nullptr;
    libusb_init_option option{};
    option.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    option.value.ival = 0;

    int result = libusb_init_context(&context, &option, 1);
    if (result != LIBUSB_SUCCESS) {
        print_error("LIBUSB_INIT", result);
        return 10;
    }

    bool allPassed = true;
    for (const auto& deviceName : devices) {
        std::printf("BROKER_DEVICE=%s\n", deviceName.c_str());
        const int fd = usb_bridge::get_fd(deviceName);
        if (fd < 0) {
            std::printf("FD_TRANSFER=FAILED errno=%d (%s)\n", errno, std::strerror(errno));
            allPassed = false;
            continue;
        }

        std::printf("BROKER_FD=%d\n", fd);
        print_fd_path(fd);

        // Android's supported libusb flow: disable automatic discovery and
        // wrap the UsbDeviceConnection FD already authorized by UsbManager.
        // libusb_wrap_sys_device() borrows this FD; this probe closes it after
        // libusb_close(), while the Android connection remains owned by Kotlin.
        libusb_device_handle* handle = nullptr;
        result = libusb_wrap_sys_device(context, static_cast<intptr_t>(fd), &handle);
        if (result != LIBUSB_SUCCESS || handle == nullptr) {
            print_error("LIBUSB_WRAP_SYS_DEVICE", result);
            ::close(fd);
            allPassed = false;
            continue;
        }

        libusb_device* device = libusb_get_device(handle);
        libusb_device_descriptor descriptor{};
        result = libusb_get_device_descriptor(device, &descriptor);
        if (result != LIBUSB_SUCCESS) {
            print_error("DEVICE_DESCRIPTOR", result);
            allPassed = false;
        } else {
            std::printf("WRAPPED_DEVICE=PASS VID=%04X PID=%04X BUS=%u ADDRESS=%u\n",
                        descriptor.idVendor, descriptor.idProduct,
                        static_cast<unsigned int>(libusb_get_bus_number(device)),
                        static_cast<unsigned int>(libusb_get_device_address(device)));
        }

        libusb_close(handle);
        ::close(fd);
    }

    libusb_exit(context);
    std::puts(allPassed ? "RESULT=PASS" : "RESULT=WRAP_FAILURE");
    return allPassed ? 0 : 11;
}
