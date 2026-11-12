#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace usb_bridge {
std::vector<std::string> get_devices();
int get_fd(const std::string& device_name);
}

int main() {
    const auto devices = usb_bridge::get_devices();
    std::printf("UNITOOL_USB_BROKER_DEVICES=%zu\n", devices.size());

    if (devices.empty()) {
        std::puts("RESULT=NO_AUTHORIZED_SESSIONS");
        return 2;
    }

    bool allValid = true;
    for (const auto& name : devices) {
        const int fd = usb_bridge::get_fd(name);
        if (fd < 0) {
            std::printf("DEVICE=%s FD_TRANSFER=FAILED errno=%d (%s)\n",
                        name.c_str(), errno, std::strerror(errno));
            allValid = false;
            continue;
        }

        struct stat info {};
        const int descriptorFlags = ::fcntl(fd, F_GETFD);
        const int statResult = ::fstat(fd, &info);
        if (descriptorFlags < 0 || statResult != 0) {
            std::printf("DEVICE=%s FD=%d VALIDATION=FAILED errno=%d (%s)\n",
                        name.c_str(), fd, errno, std::strerror(errno));
            allValid = false;
        } else {
            std::printf("DEVICE=%s FD=%d VALID=YES MODE=%#o DEV=%llu\n",
                        name.c_str(), fd, static_cast<unsigned int>(info.st_mode),
                        static_cast<unsigned long long>(info.st_rdev));
        }
        ::close(fd);
    }

    std::puts(allValid ? "RESULT=PASS" : "RESULT=PARTIAL_FAILURE");
    return allValid ? 0 : 3;
}
