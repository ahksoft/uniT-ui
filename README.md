# UniTool

Android USB flashing and repair toolkit with an integrated, real terminal.

UniTool combines two layers in one app:

- a Jetpack Compose UI driven by `UniToolViewModel` for device discovery,
  partition inspection, flashing and service tasks, and
- a full terminal runtime (`:terminalview` + Termux bootstrap) that runs real
  PTY shells with `adb`, `fastboot`, `heimdall`, MediaTek tooling, Git,
  curl, Python and the rest of the packaged command set.

## Android application identity

- Project name: `UniTool`
- Launcher app name: `UniTool`
- Application ID: `ahk.unitool.gsm`
- App namespace: `ahk.unitool.gsm`
- Terminal library namespace: `ahk.unitool.gsm.terminal`
- Supported ABI: `arm64-v8a` only

## Project layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/ahk/unitool/gsm/ui` | Compose screens, including `TerminalSheet` |
| `app/src/main/java/ahk/unitool/gsm/viewmodel` | `UniToolViewModel` (presentation state + actions) |
| `app/src/main/java/ahk/unitool/gsm/terminal` | Terminal service, controller, bootstrap, USB broker glue |
| `app/src/main/java/ahk/unitool/gsm/usb` | Native USB broker (`UsbDeviceManager`, Heimdall RPC) |
| `app/src/main/java/com/unitool` | Java engine: adb, cli, protocol (MTK/SPD), LK, flashing |
| `terminalview` | Terminal emulator, `TerminalView`, PTY JNI (`libunitool-term.so`) |
| `app/src/main/cpp` | CMake build of `libunitool_usb_jni.so`, `libusb.so`, `libusb_bridge.so`, Heimdall |
| `app/src/main/assets` | Termux bootstrap, Python runtime, command assets |
| `app/src/main/jniLibs/arm64-v8a` | Packaged native executables and libraries |

## Terminal runtime

- The interactive terminal is a real `TerminalView` hosted through
  `AndroidView` inside the Compose `TerminalSheet`.
- `TerminalController` binds `TerminalService`, creates the PTY session off the
  main thread, and keeps the view attached across sheet open/close.
- `NativeToolchain` installs the app-private `$PREFIX` (`bin`, `lib`, `etc`,
  `tmp`), the user home directory, shell profiles, the native-library alias map
  and the Python runtime on first launch.
- `TerminalRuntime` starts the native USB broker and the Heimdall RPC server
  at app launch so `adb`, `fastboot`, `heimdall`, `qdl` and `umbrage` can use
  Android-authorized USB descriptors from both the Compose engine and the
  interactive terminal. The Java `com.unitool.usb.UsbHostManager` remains the
  owner of permission dialogs; the native broker re-scans when one is granted.
- `ToolEnv` exports `ADB_LIBUSB`, `UNITOOL_LIBUSB_PATH`,
  `UNITOOL_LIBUSB_BRIDGE_PATH`, `PYUSB_LIBUSB_PATH`, `PREFIX`, `HOME`,
  `TMPDIR`, `LD_LIBRARY_PATH` and locale/color settings.

The native ADB/USB components are loaded from the APK's ABI-specific
`jniLibs` directory. Git, curl, Wget, OpenSSL, Python and less are staged at
build time from official Termux ARM64 `.deb` packages into
`assets/bootstrap`; their shared-library dependencies are installed under the
app-private `$PREFIX/lib`. The application does not download or extract
executable code at runtime.

## Compose tasks use the core CLI binaries

The Compose screens do not wrap a second flashing stack: every task builds the
same command line a user would type into the core terminal and runs it through
the packaged ARM64 executables.

| Task / UI action | Command | Binary |
| --- | --- | --- |
| ADB devices, shell, push, pull | `adb ...` | `libadb.so` (`ADB_LIBUSB=1`) |
| Fastboot flash / getvar / slots | `fastboot ...` | `libfastboot.so` |
| Samsung detect / PIT / flash | `heimdall ...` | `libheimdall_rpc.so` (RPC bridge) |
| Qualcomm EDL | `qdl [--debug] <prog.mbn> <rawprogram.xml> <patch.xml>` | `libqdl.so` |
| MediaTek v2 engine | `mtk info\|partitions\|read\|write\|erase\|flash-scatter\|unlock\|lock\|slot\|reboot` | `libumbrage_cli_exec.so` |
| MediaTek v1 engine | `mtkclient ...` | Java `com.unitool.cli.MtkCli` |
| Filesystem images | `make_f2fs ...`, `mke2fs ...` | `libmake_f2fs.so`, `libmke2fs.so` |
| USB diagnostics | `unitool-usb-probe`, `unitool-libusb-probe`, ... | probe executables |

`mtk`, `umbrage`, `ant` and `antumbra` all resolve to the core native MediaTek
engine. `mtkclient` is the unitool Java protocol engine and is kept for the
unitool-specific BROM/preloader features. Core `qdl` takes a firehose
programmer plus XML files and detects eMMC/UFS itself; there is no
`--storage`, `--gpt` or `--info` option. Core `heimdall` always talks to the
in-app RPC bridge.

## Build

Requires JDK 17, Android SDK Platform 36, NDK `25.2.9519653` and CMake
`3.22.1`.

```bash
./gradlew --no-daemon clean :app:assembleDebug
```

Release:

```bash
./gradlew --no-daemon :app:assembleRelease
```

The CMake build fetches pinned upstream Heimdall
(`3997d5cc607e6c603c6e7c0d07e42e9868c62af2`) and libusb
(`15a7ebb4d426c5ce196684347d2b7cafad862626`) sources, applies UniTool's
Android USB broker backend, and packages the resulting ARM64 libraries.

## Native binary provenance

Keep source provenance, license notices, ABI build recipes and dependency
information for the native components in the repository. Verify that each
binary is licensed for redistribution before publishing APK releases.

## Android USB host integration

See [USB host integration and hardware test matrix](docs/USB_HOST_INTEGRATION.md).
UniTool includes Android USB discovery, permission handling, connection
lifecycle management, and an app-local descriptor broker used by native USB
tools. The `unitool-libusb-probe` command exercises the packaged libusb API
used by ADB by enumerating and opening broker-authorized devices. The
`unitool-mtk-usb-probe` command checks read-only MediaTek USB enumeration and
descriptor access. ADB transport authentication and real USB transfers still
require physical-device verification.

## Official Termux CLI package runtime

The ARM64 build workflow downloads and verifies official Termux CLI packages
and their dependency closures before Gradle packaging. It includes APT, dpkg,
the Termux signing keyring, and APT HTTP/HTTPS transport helpers alongside
Git, curl, Wget, OpenSSL, and Python. On first bootstrap, the app creates
writable dpkg database, APT list/cache, and log directories under its private
`$PREFIX`. The app's `pkg` wrapper uses the staged APT/dpkg executables and a
prefix-specific APT configuration. The Python runtime includes `python-pip`,
the Termux-native `python-pycryptodomex` package, and verified pure-Python
wheels for PyUSB 1.3.1, PySerial 3.5, and Colorama 0.4.6.

Package paths and SHA-256 values are pinned in
`scripts/prepare-termux-cli-runtime.sh`. `NativeToolchain` installs these
assets into the private `$PREFIX`; shell startup configures
`LD_LIBRARY_PATH`, `GIT_EXEC_PATH`, and certificate locations. No package
installation or network access is required at app runtime.

The workflow `.github/workflows/termux-cli-runtime.yml` builds the ARM64 debug
APK and uploads it as `unitool-arm64-termux-cli-debug`.
