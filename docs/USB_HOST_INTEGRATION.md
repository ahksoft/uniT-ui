# Android USB host integration

UniTool uses Android `UsbManager` to request USB permission and opens the
authorized device with `UsbManager.openDevice()`. The open connection remains
owned by the Android USB session and is exposed to native code by the
app-local `unitool_usb_fd_broker` abstract Unix socket.

## Source-built libusb

The packaged `libusb.so` is rebuilt from upstream libusb 1.0.27, pinned to
`d52e355daa09f17ce64819122cb067b8a2ee0d4b`. A local Linux/Android backend
patch replaces direct `/dev/bus/usb` opens with broker FD retrieval.

The broker bridge exports `unitool_usb_list_devices()` and
`unitool_usb_get_device_fd()` to the C backend. The backend enumerates
authorized devices using libusb's normal Linux device lifecycle, obtains a
fresh FD from the broker for descriptor initialization and device open, and
rewinds each transferred FD to offset zero before reading descriptors.
A background monitor compares broker snapshots and reports attach/detach
changes to libusb.

This follows the FD-rewind behavior in the non-root Android helper backend
from [green-green-avk/libusb](https://github.com/green-green-avk/libusb/tree/v1.0.26-android-libusbmanager)
and Android's documented libusb integration guidance:
[libusb Android wiki](https://github.com/libusb/libusb/wiki/Android).

The upstream libusb sources are LGPL-2.1-or-later; see the fetched source's
`COPYING` file. Heimdall continues using its existing direct
`libusb_wrap_sys_device()` path.

## Read-only MediaTek discovery

The `unitool-mtk-usb-probe` command uses the same broker-backed libusb
instance. It filters for MediaTek vendor ID `0x0E8D`, prints USB descriptor
information, and opens/closes the matching libusb handle. It does not claim
interfaces or issue device commands. This confirms authorized USB
enumeration/open access only; it is not a MediaTek protocol handshake test.

## Compose task to CLI mapping

The Compose UI does not use a separate flashing backend. `UniToolViewModel` and
`NativeFlashingBridge` build the same command line that the core terminal
accepts, and `com.unitool.terminal.TerminalSession` starts the packaged binary
with the broker environment (`ADB_LIBUSB=1`, `UNITOOL_LIBUSB_PATH`,
`UNITOOL_LIBUSB_BRIDGE_PATH`, `LD_LIBRARY_PATH`).

| UI action | Command | Notes |
| --- | --- | --- |
| ADB | `adb devices -l`, `adb shell ...` | `libadb.so` with the libusb backend |
| Fastboot | `fastboot flash ...`, `fastboot getvar ...` | `libfastboot.so` |
| Samsung | `heimdall detect`, `heimdall print-pit`, `heimdall flash ...` | `libheimdall_rpc.so` forwards to the app-process RPC server |
| Qualcomm | `qdl [--debug] <prog.mbn> <rawprogram.xml> <patch.xml>` | `libqdl.so`; storage type is auto-detected, no `--storage` |
| MediaTek v2 | `mtk info`, `mtk partitions`, `mtk read`, `mtk write`, `mtk erase`, `mtk flash-scatter`, `mtk unlock`, `mtk lock`, `mtk slot set`, `mtk reboot` | `libumbrage_cli_exec.so` |
| MediaTek v1 | `mtkclient ...` | Java `MtkCli`; unitool-specific BROM/preloader engine |
| Filesystem | `make_f2fs`, `mke2fs` | `libmake_f2fs.so`, `libmke2fs.so` |

`mtk`, `umbrage`, `ant` and `antumbra` are aliases for the core native engine.
There is no separate `antumbra` binary.

## Validation

With a USB device connected and UniTool permission granted, run:

```sh
unitool-usb-probe
unitool-libusb-probe
unitool-libusb-wrap-probe
unitool-mtk-usb-probe
adb kill-server
ADB_TRACE=usb,transport,auth adb start-server
adb devices -l
```

A successful CI build only verifies compilation and packaging. Physical-device
testing is still required to verify descriptor access, USB transport creation,
and target-side ADB authorization.


## PyUSB integration

The terminal exports `UNITOOL_LIBUSB_PATH` and `PYUSB_LIBUSB_PATH` as the
absolute path to `libusb.so` in Android's `ApplicationInfo.nativeLibraryDir`.
That directory is also included in `LD_LIBRARY_PATH`, so the broker library
dependency `libusb_bridge.so` resolves from the same packaged native-library
directory.

The bootstrap's `sitecustomize.py` configures PyUSB's default
`usb.backend.libusb1.get_backend()` lookup to load that exact absolute path.
It does not fall back to a system libusb when UniTool has supplied the path.
The companion `unitool_usb.py` module provides an explicit backend and
enumeration API:

```python
import usb.core
import unitool_usb

backend = unitool_usb.get_backend()
devices = usb.core.find(find_all=True, backend=backend)

for device in devices:
    print(f"{device.idVendor:04X}:{device.idProduct:04X}")
```

After PyUSB is installed, its normal default discovery is also configured:

```python
import usb.core

for device in usb.core.find(find_all=True):
    print(f"{device.idVendor:04X}:{device.idProduct:04X}")
```

Install PyUSB with `python -m pip install pyusb`. The Python binding does not
grant USB permission itself: it can see only sessions already opened by
`UsbDeviceManager` after Android's permission flow. Interface claims and
transfers remain subject to the Android USB connection's lifetime and device
availability. Do not bundle a second stock libusb library.

The Python runtime and PyUSB package must be present before these examples can
run; this configuration change wires the runtime to UniTool's existing
broker-backed library but does not itself add CPython or install PyUSB.
