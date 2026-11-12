# Android Heimdall USB bridge

## Root cause

The previous `libheimdall.so` was a standalone Heimdall executable. Its device-detection path calls `libusb_get_device_list()`, searches for Samsung VID/PID values, and opens a matching `libusb_device` with `libusb_open()`.

Android's USB host API does not grant a normal app unrestricted access to the global USB device list or raw device nodes. It grants access to a specific device after the user approves a `UsbManager.requestPermission()` request. Heimdall's standalone discovery path therefore cannot see/use the device just because `lsusb` can print a VID/PID.

The VID/PID in the supplied test, `04E8:685D`, is already present in upstream Heimdall's supported-device table as `kPidGalaxyS2`. Adding another copy of that ID would not fix the Android transport problem.

## Rebuilt layout

- `heimdall_core` is built from upstream Heimdall source, pinned to commit `3997d5cc607e6c603c6e7c0d07e42e9868c62af2`.
- `BridgeManager` has an Android transport path. It creates a libusb context with `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`, wraps the descriptor supplied by Android with `libusb_wrap_sys_device()`, then uses the normal Heimdall interface and protocol code.
- `UsbDeviceManager` owns the `UsbDeviceConnection`. Its session lock keeps that connection alive while Heimdall runs.
- `HeimdallCommandServer` listens on an app-local abstract Unix socket. It accepts requests only from the app UID.
- The terminal's `heimdall` command is a small client executable. It forwards arguments and the terminal working directory to the app process, then prints the returned output and exit status.
- The JNI method runs the Heimdall core inside the app process, where the authorized file descriptor is valid.
- The packaged `libusb.so` has a runtime dependency on `libusb_bridge.so`. UniTool builds a small source-based compatibility shim for that dependency. Its global enumeration methods return no devices and no descriptor; the supported Heimdall Android path uses only the descriptor supplied by `UsbManager`.

No raw `/dev/bus/usb` permission bypass is attempted. The user must approve Android's USB permission dialog. The standalone legacy binary remains packaged as a library alias only; the `heimdall` command now resolves to the RPC client built by CMake.

## Expected test behavior

1. Open UniTool and connect the Samsung device through USB OTG.
2. Open **USB devices**, select the device, and tap **Grant USB access**.
3. Approve Android's USB permission dialog. UniTool opens and retains the corresponding `UsbDeviceConnection`.
4. Run `heimdall detect` in UniTool's terminal.

A successful detection should print `Device detected`. A permission-related message means no authorized session is available. A different VID/PID or interface error is a separate device-mode/descriptor issue; it is not a reason to alter the Samsung VID/PID whitelist blindly.

## References

- Heimdall upstream: https://github.com/Benjamin-Dobell/Heimdall
- libusb Android guidance: https://github.com/libusb/libusb/wiki/Android
- Android's libusb notes for `libusb_wrap_sys_device()`: https://android.googlesource.com/platform/external/libusb/+/2c32efa20e30c1fc93a37b5c7cf1858ce7fa0fb8/android/README
- libusb license: LGPL-2.1-or-later
- Heimdall license: MIT (see `heimdall_override/LICENSE`)
