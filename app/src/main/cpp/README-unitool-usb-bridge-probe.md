# Android USB bridge probe

This diagnostic executable links directly to UniTool's production
`libusb_bridge.so` and calls the exact C++ functions imported by Fastboot:
`usb_bridge::get_devices_checked()`, `get_fd()`, and `get_serial()`.

## Checks

- `BRIDGE_LIST`: broker connectivity and device-list parsing through the
  production bridge implementation.
- `BRIDGE_DEVICE_COUNT` and `BRIDGE_DEVICE[n]`: devices returned by the
  production bridge.
- `BRIDGE_FD`: descriptor transfer through production `get_fd()`, validated
  with `fcntl(F_GETFD)` and `fstat()`.
- `BRIDGE_SERIAL`: serial returned by production `get_serial()`. The current
  implementation returns an empty string; the probe reports this honestly.

This is a read-only diagnostic. It does not claim USB interfaces or send USB
control/bulk transfers.

## Run

The CMake target packages `libunitool_bridge_probe.so` under
`app/src/main/jniLibs/<ABI>/`. Run it through UniTool's native executable
dispatch environment. It tests the first listed device by default; to select
a broker device node:

```sh
libunitool_bridge_probe.so --device /dev/bus/usb/001/002
```
