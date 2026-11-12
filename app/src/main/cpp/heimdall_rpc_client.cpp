#include <arpa/inet.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
constexpr const char* kSocketName = "unitool_heimdall_rpc";
constexpr uint32_t kMaxStringBytes = 1024U * 1024U;
constexpr uint32_t kMaxOutputBytes = 16U * 1024U * 1024U;

bool sendAll(int fd, const void* data, size_t size) {
    const char* cursor = static_cast<const char*>(data);
    while (size > 0) {
        const ssize_t written = send(fd, cursor, size, MSG_NOSIGNAL);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return false;
        cursor += written;
        size -= static_cast<size_t>(written);
    }
    return true;
}

bool receiveAll(int fd, void* data, size_t size) {
    char* cursor = static_cast<char*>(data);
    while (size > 0) {
        const ssize_t received = recv(fd, cursor, size, 0);
        if (received < 0 && errno == EINTR) continue;
        if (received <= 0) return false;
        cursor += received;
        size -= static_cast<size_t>(received);
    }
    return true;
}

bool sendUInt32(int fd, uint32_t value) {
    const uint32_t encoded = htonl(value);
    return sendAll(fd, &encoded, sizeof(encoded));
}

bool receiveUInt32(int fd, uint32_t* value) {
    uint32_t encoded = 0;
    if (!receiveAll(fd, &encoded, sizeof(encoded))) return false;
    *value = ntohl(encoded);
    return true;
}

bool sendString(int fd, const char* value) {
    const size_t length = value ? std::strlen(value) : 0;
    if (length > kMaxStringBytes) return false;
    return sendUInt32(fd, static_cast<uint32_t>(length)) &&
           (length == 0 || sendAll(fd, value, length));
}
}

int main(int argc, char** argv) {
    const int socketFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (socketFd < 0) {
        std::fprintf(stderr, "ERROR: Cannot create Heimdall bridge socket: %s\n", std::strerror(errno));
        return 125;
    }

    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    const size_t nameLength = std::strlen(kSocketName);
    if (nameLength + 1 > sizeof(address.sun_path)) {
        close(socketFd);
        std::fprintf(stderr, "ERROR: Heimdall bridge socket name is too long.\n");
        return 125;
    }
    address.sun_path[0] = '\0';
    std::memcpy(address.sun_path + 1, kSocketName, nameLength);
    const socklen_t addressLength = static_cast<socklen_t>(
        offsetof(sockaddr_un, sun_path) + 1 + nameLength);

    if (connect(socketFd, reinterpret_cast<sockaddr*>(&address), addressLength) != 0) {
        const int savedError = errno;
        close(socketFd);
        std::fprintf(stderr,
            "ERROR: UniTool Heimdall bridge is not running. Open UniTool and grant USB access from USB devices. (%s)\n",
            std::strerror(savedError));
        return 125;
    }

    char workingDirectory[PATH_MAX] = {};
    if (getcwd(workingDirectory, sizeof(workingDirectory)) == nullptr) {
        workingDirectory[0] = '\0';
    }

    bool requestOk = sendUInt32(socketFd, static_cast<uint32_t>(argc > 1 ? argc - 1 : 0)) &&
                     sendString(socketFd, workingDirectory);
    for (int index = 1; requestOk && index < argc; ++index) {
        requestOk = sendString(socketFd, argv[index]);
    }

    uint32_t exitCode = 125;
    uint32_t outputLength = 0;
    if (!requestOk ||
        !receiveUInt32(socketFd, &exitCode) ||
        !receiveUInt32(socketFd, &outputLength) ||
        outputLength > kMaxOutputBytes) {
        std::fprintf(stderr, "ERROR: Invalid response from UniTool Heimdall bridge.\n");
        close(socketFd);
        return 125;
    }

    std::string output(outputLength, '\0');
    if (outputLength > 0 && !receiveAll(socketFd, &output[0], outputLength)) {
        std::fprintf(stderr, "ERROR: Incomplete response from UniTool Heimdall bridge.\n");
        close(socketFd);
        return 125;
    }

    close(socketFd);
    if (!output.empty()) {
        std::fwrite(output.data(), 1, output.size(), stdout);
        std::fflush(stdout);
    }
    return static_cast<int>(exitCode & 0xffU);
}
