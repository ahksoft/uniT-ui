#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include "usb_bridge_api.h"

namespace {
bool valid_fd(int fd) {
    if (fd < 0) return false;
    struct stat st {};
    return ::fcntl(fd, F_GETFD) >= 0 && ::fstat(fd, &st) == 0;
}
void result(const char* key, bool ok) {
    std::printf("%s=%s\n", key, ok ? "PASS" : "FAIL");
}
}

int main(int argc, char** argv) {
    std::vector<std::string> devices;
    const bool listed = usb_bridge::get_devices_checked(devices);
    result("BRIDGE_LIST", listed);
    std::printf("BRIDGE_DEVICE_COUNT=%zu\n", devices.size());
    if (!listed) {
        std::puts("ERROR=usb_bridge::get_devices_checked failed");
        return 2;
    }
    for (size_t i = 0; i < devices.size(); ++i)
        std::printf("BRIDGE_DEVICE[%zu]=%s\n", i, devices[i].c_str());
    if (devices.empty()) {
        std::puts("BRIDGE_FD=SKIP");
        std::puts("BRIDGE_SERIAL=SKIP");
        return 0;
    }
    const std::string selected =
        argc > 2 && std::strcmp(argv[1], "--device") == 0 ? argv[2] : devices.front();
    std::printf("BRIDGE_SELECTED_DEVICE=%s\n", selected.c_str());
    const std::string serial = usb_bridge::get_serial(selected);
    std::printf("BRIDGE_SERIAL=%s\n", serial.empty() ? "<empty>" : serial.c_str());
    const int fd = usb_bridge::get_fd(selected);
    const bool opened = valid_fd(fd);
    result("BRIDGE_FD", opened);
    if (!opened) {
        std::puts("ERROR=usb_bridge::get_fd failed or returned invalid descriptor");
        return 3;
    }
    struct stat st {};
    ::fstat(fd, &st);
    std::printf("BRIDGE_FD_MODE=%#o\n", st.st_mode);
    std::printf("BRIDGE_FD_DEVICE=%llu\n", static_cast<unsigned long long>(st.st_rdev));
    ::close(fd);
    return 0;
}
