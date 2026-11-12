cmake_minimum_required(VERSION 3.22.1)
project(unitool_usb_jni LANGUAGES C CXX)

include(FetchContent)

# Pin Heimdall source so both ABIs are built from the same reproducible revision.
FetchContent_Declare(
    heimdall_upstream
    GIT_REPOSITORY https://github.com/Benjamin-Dobell/Heimdall.git
    GIT_TAG 3997d5cc607e6c603c6e7c0d07e42e9868c62af2
)
FetchContent_GetProperties(heimdall_upstream)
if(NOT heimdall_upstream_POPULATED)
    FetchContent_Populate(heimdall_upstream)
endif()

set(HEIMDALL_SOURCE_DIR ${heimdall_upstream_SOURCE_DIR})
set(HEIMDALL_OVERRIDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/heimdall_override")

# Build upstream libusb 1.0.29 (includes the SuperSpeedPlus API required by libadb) and apply UniTool's Android USB broker backend.
FetchContent_Declare(
    libusb_upstream
    GIT_REPOSITORY https://github.com/libusb/libusb.git
    GIT_TAG 15a7ebb4d426c5ce196684347d2b7cafad862626
)
FetchContent_GetProperties(libusb_upstream)
if(NOT libusb_upstream_POPULATED)
    FetchContent_Populate(libusb_upstream)
endif()
set(LIBUSB_SOURCE_DIR ${libusb_upstream_SOURCE_DIR})

set(LIBUSB_LINUX_USBFS "${LIBUSB_SOURCE_DIR}/libusb/os/linux_usbfs.c")
file(READ "${LIBUSB_LINUX_USBFS}" libusb_linux_usbfs_source)
# FetchContent keeps the patched sources between configure runs, so only
# apply the broker hooks when they are not present yet.
string(FIND "${libusb_linux_usbfs_source}" "unitool_android_helper_get_usbfs_fd" libusb_source_already_patched)
if(libusb_source_already_patched EQUAL -1)
    string(FIND "${libusb_linux_usbfs_source}" "int fd;\n\n\tif (usbdev_names)\n\t\tsnprintf(path, sizeof(path), USBDEV_PATH" libusb_fd_patch_position)
    string(FIND "${libusb_linux_usbfs_source}" "#if defined(HAVE_LIBUDEV)\n\tret = linux_udev_scan_devices(ctx);" libusb_scan_patch_position)
    if(libusb_fd_patch_position EQUAL -1 OR libusb_scan_patch_position EQUAL -1)
        message(FATAL_ERROR "Pinned libusb 1.0.29 Linux backend layout changed; review UniTool patch")
    endif()
    string(REPLACE
        "int fd;\n\n\tif (usbdev_names)\n\t\tsnprintf(path, sizeof(path), USBDEV_PATH"
        "int fd;\n\n#ifdef UNITOOL_ANDROID_USB_BROKER\n\treturn unitool_android_helper_get_usbfs_fd(dev, silent);\n#endif\n\n\tif (usbdev_names)\n\t\tsnprintf(path, sizeof(path), USBDEV_PATH"
        libusb_linux_usbfs_source
        "${libusb_linux_usbfs_source}"
    )
    string(REPLACE
        "#if defined(HAVE_LIBUDEV)\n\tret = linux_udev_scan_devices(ctx);"
        "#if defined(UNITOOL_ANDROID_USB_BROKER)\n\tret = unitool_android_helper_scan_devices(ctx);\n#elif defined(HAVE_LIBUDEV)\n\tret = linux_udev_scan_devices(ctx);"
        libusb_linux_usbfs_source
        "${libusb_linux_usbfs_source}"
    )
    file(WRITE "${LIBUSB_LINUX_USBFS}" "${libusb_linux_usbfs_source}")
endif()

set(LIBUSB_LINUX_USBFS_HEADER "${LIBUSB_SOURCE_DIR}/libusb/os/linux_usbfs.h")
file(READ "${LIBUSB_LINUX_USBFS_HEADER}" libusb_linux_usbfs_header)
string(FIND "${libusb_linux_usbfs_header}" "unitool_android_helper_scan_devices" libusb_header_already_patched)
if(libusb_header_already_patched EQUAL -1)
    string(REPLACE
        "extern usbi_mutex_static_t linux_hotplug_lock;"
        "extern usbi_mutex_static_t linux_hotplug_lock;\n#ifdef UNITOOL_ANDROID_USB_BROKER\nint unitool_android_helper_get_usbfs_fd(const struct libusb_device *dev, int silent);\nint unitool_android_helper_scan_devices(struct libusb_context *ctx);\nint unitool_android_helper_start_event_monitor(void);\nint unitool_android_helper_stop_event_monitor(void);\n#endif"
        libusb_linux_usbfs_header
        "${libusb_linux_usbfs_header}"
    )
    string(REPLACE
        "static inline int linux_start_event_monitor(void)\n{\n#if defined(HAVE_LIBUDEV)"
        "static inline int linux_start_event_monitor(void)\n{\n#if defined(UNITOOL_ANDROID_USB_BROKER)\n\treturn unitool_android_helper_start_event_monitor();\n#elif defined(HAVE_LIBUDEV)"
        libusb_linux_usbfs_header
        "${libusb_linux_usbfs_header}"
    )
    string(REPLACE
        "static inline void linux_stop_event_monitor(void)\n{\n#if defined(HAVE_LIBUDEV)"
        "static inline void linux_stop_event_monitor(void)\n{\n#if defined(UNITOOL_ANDROID_USB_BROKER)\n\tunitool_android_helper_stop_event_monitor();\n#elif defined(HAVE_LIBUDEV)"
        libusb_linux_usbfs_header
        "${libusb_linux_usbfs_header}"
    )
    string(REPLACE
        "static inline void linux_hotplug_poll(void)\n{\n#if defined(HAVE_LIBUDEV)"
        "static inline void linux_hotplug_poll(void)\n{\n#if defined(UNITOOL_ANDROID_USB_BROKER)\n\t/* Broker changes are handled by helper monitor thread. */\n#elif defined(HAVE_LIBUDEV)"
        libusb_linux_usbfs_header
        "${libusb_linux_usbfs_header}"
    )
    string(FIND "${libusb_linux_usbfs_header}" "unitool_android_helper_scan_devices" libusb_header_patch_position)
    if(libusb_header_patch_position EQUAL -1)
        message(FATAL_ERROR "Could not apply UniTool event-monitor integration")
    endif()
    file(WRITE "${LIBUSB_LINUX_USBFS_HEADER}" "${libusb_linux_usbfs_header}")
endif()


# Heimdall 1.4.2 has a C++03 compatibility macro that defines nullptr as 0.
# It breaks libc++ when built with the modern C++17 mode required by the NDK.
# Retain that fallback only for pre-C++11 compilers.
foreach(legacy_header
    "${HEIMDALL_SOURCE_DIR}/heimdall/source/Heimdall.h"
    "${HEIMDALL_SOURCE_DIR}/libpit/source/libpit.h"
)
    file(READ "${legacy_header}" legacy_header_content)
    string(FIND "${legacy_header_content}" "__cplusplus < 201103L" legacy_header_already_patched)
    if(legacy_header_already_patched EQUAL -1)
        string(REPLACE
            "#if (!(defined _MSC_VER) || (_MSC_VER < 1700))"
            "#if defined(__cplusplus) && __cplusplus < 201103L && (!(defined _MSC_VER) || (_MSC_VER < 1700))"
            patched_header_content
            "${legacy_header_content}"
        )
        if(patched_header_content STREQUAL legacy_header_content)
            message(FATAL_ERROR "Could not patch legacy nullptr macro in ${legacy_header}")
        endif()
        file(WRITE "${legacy_header}" "${patched_header_content}")
    endif()
endforeach()


# Android-authorized USB descriptors are exposed through this app-local broker.
# AGP packages this CMake output into the APK (like unitool-core); it is not
# staged in the source jniLibs/ directory.
add_library(usb_bridge SHARED usb_bridge.cpp)
set_target_properties(usb_bridge PROPERTIES OUTPUT_NAME "usb_bridge")
target_compile_features(usb_bridge PRIVATE cxx_std_17)

# Standalone probe for the Fastboot-facing USB broker protocol.
add_executable(unitool_bridge_probe unitool_bridge_probe.cpp)
target_compile_features(unitool_bridge_probe PRIVATE cxx_std_17)
target_link_options(unitool_bridge_probe PRIVATE -Wl,--no-undefined)
target_link_libraries(unitool_bridge_probe PRIVATE usb_bridge)
add_custom_command(TARGET unitool_bridge_probe POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different "$<TARGET_FILE:unitool_bridge_probe>" "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libunitool_bridge_probe.so"
    VERBATIM
)

# Build the ADB/Heimdall libusb dependency from pinned upstream sources.
add_library(libusb_prebuilt SHARED
    "${LIBUSB_SOURCE_DIR}/libusb/core.c"
    "${LIBUSB_SOURCE_DIR}/libusb/descriptor.c"
    "${LIBUSB_SOURCE_DIR}/libusb/hotplug.c"
    "${LIBUSB_SOURCE_DIR}/libusb/io.c"
    "${LIBUSB_SOURCE_DIR}/libusb/strerror.c"
    "${LIBUSB_SOURCE_DIR}/libusb/sync.c"
    "${LIBUSB_SOURCE_DIR}/libusb/os/events_posix.c"
    "${LIBUSB_SOURCE_DIR}/libusb/os/threads_posix.c"
    "${LIBUSB_SOURCE_DIR}/libusb/os/linux_usbfs.c"
    unitool_linux_android_helper.c
)
set_target_properties(libusb_prebuilt PROPERTIES
    OUTPUT_NAME "usb"
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    C_EXTENSIONS ON
)
target_include_directories(libusb_prebuilt
    PUBLIC "${LIBUSB_SOURCE_DIR}/libusb"
    PRIVATE "${LIBUSB_SOURCE_DIR}/android" "${LIBUSB_SOURCE_DIR}/libusb/os"
)
target_compile_definitions(libusb_prebuilt PRIVATE UNITOOL_ANDROID_USB_BROKER=1)
target_compile_options(libusb_prebuilt PRIVATE -fvisibility=hidden -pthread)
target_link_options(libusb_prebuilt PRIVATE -pthread)
target_link_libraries(libusb_prebuilt PRIVATE usb_bridge log)
# Like usb_bridge, AGP packages the CMake-built libusb.so directly. Copying it
# into jniLibs as well would make the source folder a second input and AGP 9
# rejects the duplicate lib/arm64-v8a/libusb.so path.


# Small diagnostic executable used to validate LIST + SCM_RIGHTS FD transfer
# independently of ADB's own libusb discovery implementation.
add_executable(unitool_usb_probe unitool_usb_probe.cpp)
target_compile_features(unitool_usb_probe PRIVATE cxx_std_17)
target_link_libraries(unitool_usb_probe PRIVATE usb_bridge)
add_custom_command(TARGET unitool_usb_probe POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:unitool_usb_probe>"
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libunitool_usb_probe.so"
    VERBATIM
)

# Exercise the packaged libusb API used by ADB, including device enumeration
# and opening the broker-transferred descriptor. This is intentionally
# read-only: it does not claim interfaces or send USB control/bulk transfers.
add_executable(unitool_libusb_probe unitool_libusb_probe.cpp)
target_compile_features(unitool_libusb_probe PRIVATE cxx_std_17)
target_include_directories(unitool_libusb_probe PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(unitool_libusb_probe PRIVATE libusb_prebuilt usb_bridge)
add_custom_command(TARGET unitool_libusb_probe POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:unitool_libusb_probe>"
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libunitool_libusb_probe.so"
    VERBATIM
)

# Validate Android's documented no-discovery + wrap_sys_device flow using
# the exact broker-transferred UsbDeviceConnection descriptor.
add_executable(unitool_libusb_wrap_probe unitool_libusb_wrap_probe.cpp)
target_compile_features(unitool_libusb_wrap_probe PRIVATE cxx_std_17)
target_include_directories(unitool_libusb_wrap_probe PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(unitool_libusb_wrap_probe PRIVATE libusb_prebuilt usb_bridge)
add_custom_command(TARGET unitool_libusb_wrap_probe POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:unitool_libusb_wrap_probe>"
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libunitool_libusb_wrap_probe.so"
    VERBATIM
)

# Read-only MediaTek USB probe using the same broker-backed libusb instance.
# It verifies that an authorized UsbDeviceConnection can be enumerated and
# opened without claiming interfaces or issuing device commands.
add_executable(unitool_mtk_usb_probe unitool_mtk_usb_probe.cpp)
target_compile_features(unitool_mtk_usb_probe PRIVATE cxx_std_17)
target_include_directories(unitool_mtk_usb_probe PRIVATE
    "${LIBUSB_SOURCE_DIR}/libusb"
)
target_link_libraries(unitool_mtk_usb_probe PRIVATE libusb_prebuilt)
add_custom_command(TARGET unitool_mtk_usb_probe POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:unitool_mtk_usb_probe>"
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libunitool_mtk_usb_probe.so"
    VERBATIM
)

file(GLOB HEIMDALL_CPP CONFIGURE_DEPENDS
    "${HEIMDALL_SOURCE_DIR}/heimdall/source/*.cpp"
)
list(REMOVE_ITEM HEIMDALL_CPP
    "${HEIMDALL_SOURCE_DIR}/heimdall/source/BridgeManager.cpp"
    "${HEIMDALL_SOURCE_DIR}/heimdall/source/Interface.cpp"
    "${HEIMDALL_SOURCE_DIR}/heimdall/source/main.cpp"
)

add_library(heimdall_core STATIC
    ${HEIMDALL_CPP}
    "${HEIMDALL_OVERRIDE_DIR}/BridgeManager.cpp"
    "${HEIMDALL_OVERRIDE_DIR}/Interface.cpp"
    "${HEIMDALL_OVERRIDE_DIR}/main.cpp"
    "${HEIMDALL_SOURCE_DIR}/libpit/source/libpit.cpp"
)
set_target_properties(heimdall_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_features(heimdall_core PUBLIC cxx_std_17)
target_compile_definitions(heimdall_core PRIVATE _FILE_OFFSET_BITS=64)
target_include_directories(heimdall_core PRIVATE
    "${HEIMDALL_OVERRIDE_DIR}"
    "${HEIMDALL_SOURCE_DIR}/heimdall/source"
    "${HEIMDALL_SOURCE_DIR}/libpit/source"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(heimdall_core PUBLIC libusb_prebuilt usb_bridge)

add_library(unitool_usb_jni SHARED usb_jni.cpp)
target_compile_features(unitool_usb_jni PRIVATE cxx_std_17)
target_include_directories(unitool_usb_jni PRIVATE
    "${HEIMDALL_OVERRIDE_DIR}"
    "${HEIMDALL_SOURCE_DIR}/heimdall/source"
    "${HEIMDALL_SOURCE_DIR}/libpit/source"
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)
target_link_libraries(unitool_usb_jni PRIVATE heimdall_core libusb_prebuilt usb_bridge log)

# The terminal command is a small RPC client. Heimdall itself runs in the
# Android app process so it can use the UsbDeviceConnection file descriptor.
add_executable(heimdall_rpc heimdall_rpc_client.cpp)
target_compile_features(heimdall_rpc PRIVATE cxx_std_17)
set_target_properties(heimdall_rpc PROPERTIES POSITION_INDEPENDENT_CODE ON)
add_custom_command(TARGET heimdall_rpc POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "$<TARGET_FILE:heimdall_rpc>"
        "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libheimdall_rpc.so"
    VERBATIM
)
