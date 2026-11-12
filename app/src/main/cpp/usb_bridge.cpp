#include <string>
#include <vector>
#include <cstring>
#include <cstddef>
#include <cstdlib>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <unistd.h>
#include <fcntl.h>

namespace {
constexpr char kSocketName[] = "unitool_usb_fd_broker";

int connect_broker() {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    timeval timeout{};
    timeout.tv_sec = 3;
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, kSocketName, sizeof(kSocketName) - 1);
    const socklen_t length = static_cast<socklen_t>(
        offsetof(sockaddr_un, sun_path) + 1 + sizeof(kSocketName) - 1);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&address), length) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

bool send_all(int fd, const std::string& value) {
    size_t offset = 0;
    while (offset < value.size()) {
        const ssize_t count = ::send(fd, value.data() + offset,
            value.size() - offset, MSG_NOSIGNAL);
        if (count <= 0) return false;
        offset += static_cast<size_t>(count);
    }
    return true;
}

bool read_line(int fd, std::string& value) {
    value.clear();
    char ch = 0;
    while (value.size() < 4096) {
        if (::recv(fd, &ch, 1, 0) != 1) return false;
        if (ch == '\n') return true;
        if (ch != '\r') value.push_back(ch);
    }
    return false;
}
} // namespace

// Android USB backend compatibility API. Only UsbManager-authorized file
// descriptors are made visible to native tools through the app-local broker.
namespace usb_bridge {
bool get_devices_checked(std::vector<std::string>& result) {
    result.clear();
    const int fd = connect_broker();
    if (fd < 0 || !send_all(fd, "LIST\n")) {
        if (fd >= 0) ::close(fd);
        return false;
    }
    std::string line;
    if (!read_line(fd, line)) {
        ::close(fd);
        return false;
    }
    char* end = nullptr;
    const long count = std::strtol(line.c_str(), &end, 10);
    if (end == line.c_str() || *end != '\0' || count < 0 || count > 256) {
        ::close(fd);
        return false;
    }
    for (long i = 0; i < count; ++i) {
        if (!read_line(fd, line) || line.empty()) {
            ::close(fd);
            result.clear();
            return false;
        }
        result.push_back(line);
    }
    ::close(fd);
    return true;
}

std::vector<std::string> get_devices() {
    std::vector<std::string> result;
    (void)get_devices_checked(result);
    return result;
}

int get_fd(const std::string& device_name) {
    if (device_name.empty() || device_name.find('\n') != std::string::npos) return -1;
    const int socket_fd = connect_broker();
    if (socket_fd < 0 || !send_all(socket_fd, "FD " + device_name + "\n")) {
        if (socket_fd >= 0) ::close(socket_fd);
        return -1;
    }
    char marker = 0;
    iovec io{};
    io.iov_base = &marker;
    io.iov_len = 1;
    char control[CMSG_SPACE(sizeof(int))]{};
    msghdr message{};
    message.msg_iov = &io;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
    const ssize_t received = ::recvmsg(socket_fd, &message, MSG_CMSG_CLOEXEC);
    ::close(socket_fd);
    if (received != 1 || marker != '1' || (message.msg_flags & MSG_CTRUNC)) return -1;
    for (cmsghdr* header = CMSG_FIRSTHDR(&message); header;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(sizeof(int))) {
            int result = -1;
            std::memcpy(&result, CMSG_DATA(header), sizeof(result));
            if (result >= 0) {
                const int flags = ::fcntl(result, F_GETFD);
                if (flags >= 0) ::fcntl(result, F_SETFD, flags & ~FD_CLOEXEC);

                // SCM_RIGHTS duplicates the same open file description, so
                // the USBFS file offset is shared with UsbDeviceConnection
                // and every other duplicate. Fastboot reads descriptors from
                // this FD; rewind it for every new consumer.
                if (::lseek(result, 0, SEEK_SET) < 0) {
                    const int saved_errno = errno;
                    ::close(result);
                    errno = saved_errno;
                    return -1;
                }
            }
            return result;
        }
    }
    return -1;
}

std::string get_serial(const std::string& device_name) {
    if (device_name.empty() || device_name.find('\n') != std::string::npos ||
        device_name.find('\r') != std::string::npos)
        return {};
    const int socket_fd = connect_broker();
    if (socket_fd < 0 ||
        !send_all(socket_fd, "SERIAL " + device_name + "\n")) {
        if (socket_fd >= 0) ::close(socket_fd);
        return {};
    }
    std::string serial;
    const bool received = read_line(socket_fd, serial);
    ::close(socket_fd);
    return received ? serial : std::string{};
}
} // namespace usb_bridge

extern "C" int unitool_usb_list_devices(char* buffer, size_t capacity) {
    if (!buffer || capacity == 0) return -1;
    try {
        std::vector<std::string> devices;
        if (!usb_bridge::get_devices_checked(devices)) return -1;
        size_t used = 0;
        for (const std::string& name : devices) {
            if (name.empty() || name.find('\n') != std::string::npos ||
                name.size() + 2 > capacity - used)
                return -1;
            std::memcpy(buffer + used, name.data(), name.size());
            used += name.size();
            buffer[used++] = '\n';
        }
        buffer[used] = '\0';
        return static_cast<int>(used);
    } catch (...) {
        return -1;
    }
}

extern "C" int unitool_usb_get_device_fd(const char* device_name) {
    if (!device_name) return -1;
    try {
        return usb_bridge::get_fd(std::string(device_name));
    } catch (...) {
        return -1;
    }
}
