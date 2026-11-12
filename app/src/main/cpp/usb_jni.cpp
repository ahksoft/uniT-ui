#include <jni.h>
#include <cstdint>
#include <new>
#include <string>
#include <vector>
#include <cstring>
#include <arpa/inet.h>
#include <limits.h>
#include <unistd.h>

extern "C" int unitool_heimdall_run(
    int argc, char **argv, int androidUsbFileDescriptor, std::string *output);

extern "C" {
struct libusb_context;
struct libusb_device;
struct libusb_device_handle;
struct libusb_device_descriptor {
    uint8_t bLength;
    uint8_t bDescriptorType;
    uint16_t bcdUSB;
    uint8_t bDeviceClass;
    uint8_t bDeviceSubClass;
    uint8_t bDeviceProtocol;
    uint8_t bMaxPacketSize0;
    uint16_t idVendor;
    uint16_t idProduct;
    uint16_t bcdDevice;
    uint8_t iManufacturer;
    uint8_t iProduct;
    uint8_t iSerialNumber;
    uint8_t bNumConfigurations;
};

struct libusb_init_option {
    int option;
    union {
        int64_t ival;
        void (*log_cbval)(libusb_context*, int, const char*);
    } value;
};

int libusb_init_context(libusb_context** ctx,
                        const libusb_init_option options[],
                        int num_options);
int libusb_wrap_sys_device(libusb_context* ctx, intptr_t sys_dev,
                           libusb_device_handle** dev_handle);
libusb_device* libusb_get_device(libusb_device_handle* dev_handle);
int libusb_get_device_descriptor(libusb_device* dev, libusb_device_descriptor* desc);
void libusb_close(libusb_device_handle* dev_handle);
void libusb_exit(libusb_context* ctx);
const char* libusb_error_name(int error_code);
}

namespace {
struct UsbHandle {
    libusb_context* context = nullptr;
    libusb_device_handle* device = nullptr;
};

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_ahk_unitool_gsm_usb_UsbNativeBridge_nativeOpen(
    JNIEnv* env, jclass, jint fileDescriptor) {
    if (fileDescriptor < 0) {
        throwIllegalState(env, "Invalid Android USB file descriptor");
        return 0;
    }

    auto* handle = new (std::nothrow) UsbHandle();
    if (handle == nullptr) {
        throwIllegalState(env, "Unable to allocate native USB handle");
        return 0;
    }

    // Android restricts direct USB enumeration. Open only the descriptor
    // already authorized and opened through Android UsbManager.
    libusb_init_option option{};
    option.option = 2; // LIBUSB_OPTION_NO_DEVICE_DISCOVERY
    option.value.ival = 0;

    int result = libusb_init_context(&handle->context, &option, 1);
    if (result == 0) {
        result = libusb_wrap_sys_device(
            handle->context,
            static_cast<intptr_t>(fileDescriptor),
            &handle->device
        );
    }

    if (result == 0) {
        libusb_device_descriptor descriptor{};
        result = libusb_get_device_descriptor(
            libusb_get_device(handle->device), &descriptor
        );
    }

    if (result != 0) {
        const char* errorName = libusb_error_name(result);
        std::string message = "libusb USB descriptor wrapping failed: ";
        message += errorName != nullptr ? errorName : "unknown error";
        if (handle->context != nullptr) libusb_exit(handle->context);
        delete handle;
        throwIllegalState(env, message);
        return 0;
    }

    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_ahk_unitool_gsm_usb_UsbNativeBridge_nativeClose(
    JNIEnv*, jclass, jlong nativeHandle) {
    auto* handle = reinterpret_cast<UsbHandle*>(nativeHandle);
    if (handle == nullptr) return;
    if (handle->device != nullptr) libusb_close(handle->device);
    if (handle->context != nullptr) libusb_exit(handle->context);
    delete handle;
}


extern "C" JNIEXPORT jbyteArray JNICALL
Java_ahk_unitool_gsm_usb_UsbNativeBridge_nativeRun(
    JNIEnv* env, jclass, jint fileDescriptor, jobjectArray arguments,
    jstring workingDirectory) {
    std::string output;
    std::vector<std::string> argumentStorage;
    const jsize argumentCount = arguments ? env->GetArrayLength(arguments) : 0;
    argumentStorage.reserve(static_cast<size_t>(argumentCount));

    for (jsize index = 0; index < argumentCount; ++index) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(arguments, index));
        if (item == nullptr) {
            argumentStorage.emplace_back();
            continue;
        }
        const char* chars = env->GetStringUTFChars(item, nullptr);
        if (chars == nullptr) {
            env->DeleteLocalRef(item);
            return nullptr;
        }
        argumentStorage.emplace_back(chars);
        env->ReleaseStringUTFChars(item, chars);
        env->DeleteLocalRef(item);
    }

    std::vector<char*> argv;
    argv.reserve(argumentStorage.size() + 1);
    argv.push_back(const_cast<char*>("heimdall"));
    for (std::string& argument : argumentStorage) {
        argv.push_back(argument.data());
    }

    char previousDirectory[PATH_MAX] = {};
    const bool havePreviousDirectory = getcwd(previousDirectory, sizeof(previousDirectory)) != nullptr;
    bool changedDirectory = false;
    if (workingDirectory != nullptr) {
        const char* cwd = env->GetStringUTFChars(workingDirectory, nullptr);
        if (cwd != nullptr) {
            if (cwd[0] != '\0') {
                if (chdir(cwd) == 0) {
                    changedDirectory = true;
                } else {
                    output = "ERROR: Unable to enter terminal working directory.\n";
                }
            }
            env->ReleaseStringUTFChars(workingDirectory, cwd);
        }
    }

    int exitCode = output.empty()
        ? unitool_heimdall_run(static_cast<int>(argv.size()), argv.data(),
                              fileDescriptor, &output)
        : 1;

    if (changedDirectory && havePreviousDirectory) {
        chdir(previousDirectory);
    }

    const uint32_t encodedStatus = htonl(static_cast<uint32_t>(exitCode));
    std::vector<jbyte> response(sizeof(encodedStatus) + output.size());
    std::memcpy(response.data(), &encodedStatus, sizeof(encodedStatus));
    if (!output.empty()) {
        std::memcpy(response.data() + sizeof(encodedStatus), output.data(), output.size());
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(response.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(response.size()), response.data());
    return result;
}
