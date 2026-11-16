# MOFLASH 5.8.5 APK — static evidence audit

**Target:** `MOFLASH_5.8.5-release-20261025.apk`  
**Scope:** Static archive, DEX string and ARM64 ELF symbol/string inspection.  
**Status:** Preliminary static audit; REA/JADX decompilation and runtime tracing were not completed.

## Tooling status and reproducibility

The official REA repository was reviewed. Its current Android-analysis guide requires Node.js 22.23.4 or newer, a full JDK 17+, and the pinned `jadx-headless-mcp 0.7.1` JAR with SHA-256:

`6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`

The available environment has Node.js 22.16.0 and JDK 21. The required newer Node runtime and JADX bridge could not be obtained in this execution environment. Therefore, no REA command, JADX class decompilation, call graph, or runtime trace is claimed.

## APK inventory

- APK size: approximately 71.46 MiB.
- ZIP entries: 284.
- DEX: `classes.dex` (approximately 7.04 MB).
- Native ABI: `arm64-v8a`.
- Native libraries include `libadb.so`, `libfastboot.so`, `libantumbra.so`, `libnativecmd.so`, `libfdpass.so`, `libmtkcmd.so`, `libqdl.so`, `libqdlargs.so`, `libcmdgen.so`, `liblpmake.so`, `libsimg2img.so`, `libpayload-dumper-go.so`, `lib7zz.so`, and `libtermux.so`.

## Confirmed native evidence

### ADB

The `libadb.so` string table contains `libusb_init_context`, `libusb_hotplug`, `usb_libusb.cpp`, and ADB transport/device-tracker strings. This supports that the packaged ADB binary includes a libusb transport implementation. It does not establish which Java/Kotlin screen starts it or how its output is delivered to the UI.

### Fastboot

The `libfastboot.so` string table contains `libusb_init_context`, libusb transfer/transport strings, and Android Fastboot logical-partition metadata strings. This supports a separate native Fastboot binary with libusb support.

### JNI bridge

`libnativecmd.so` exports JNI methods under `Java_com_Android_MoFlash_data_NativeCmd_*`. Confirmed exported method names include:

- `nDevices`
- `nGetvarAll`
- `nGetvarCurrentSlot`
- `nGetvarIsUserspace`
- `nGetvarPartitionSize`
- `nGetvarPartitionType`
- `nGetvarSuperSize`
- `nSlots`
- `nSlotSuffixes`
- `nSetActive`
- `nRebootMode`
- `nRebootFastboot`
- `nRebootBootloader`
- `nSnapshotCancel`
- `nHelp`

The same library also exports partition-modification related JNI entry points. This audit records their existence only; it does not document invocation or execution steps.

### File-descriptor bridge

`libfdpass.so` exports:

- `Java_com_Android_MoFlash_data_FdPass_sendFd`
- `Java_com_Android_MoFlash_data_FdPass_serveFdLoop`
- `Java_com_Android_MoFlash_data_FdPass_stopServeLoop`

The DEX contains a local abstract socket name, `localabstract:com.android.moflash.diox.proc`. Together these are evidence of a native FD-passing component and a local IPC endpoint. The exact process ownership and descriptor lifecycle require Java/JADX tracing.

### MTK and Qualcomm components

- `libantumbra.so` contains Rust-related strings, libusb symbols, MediaTek platform identifiers, DA/BROM/GPT vocabulary, and progress-report strings.
- `libmtkcmd.so` exports a JNI argument-construction method named `MtkArgs_nWithDaPrefix`.
- `libqdl.so` contains Firehose/Sahara and libusb-related strings.
- `libqdlargs.so` exports JNI argument-builder methods.

These observations identify bundled engines and protocol-related dependencies. They do not prove the exact GUI call path or permission handoff.

## Confirmed DEX/UI evidence

DEX strings include the following class descriptors:

- `com.Android.MoFlash.MainActivity`
- `com.Android.MoFlash.MoFlashApplication`
- `com.Android.MoFlash.data.NativeCmd`
- `com.Android.MoFlash.data.FdPass`
- `com.Android.MoFlash.data.UsbHostManager`
- `com.Android.MoFlash.data.MtkArgs`
- `com.Android.MoFlash.data.QdlArgs`
- `com.Android.MoFlash.data.CmdGen`
- `com.Android.MoFlash.data.SuperMergeManager`

DEX UI and model strings include:

- `ADB Tool`, `Fastboot Tool`, `ADB Shell (Interactive)`, `ADB Sideload`
- `File Manager`, `AdbFileEntry(name=`, `adb_file_push`
- `Partition Preview`, `Preview Partition Table`, `Listing partitions...`
- `AntumbraPartition(name=`, `DynamicPartitionGroup`, `DynamicPartitionMetadata`
- `Batch Read`, `Batch read finished`, `Flash stats`, `Failed partitions:`
- `ACTION_SET_PROGRESS`, `InProgress(latestEvent=`, `Copy log`
- `MOMIUI/ADB> ready`, `MOMIUI/FASTBOOT> ready`, `MOMIUI/MTK> ready`, `MOMIUI/QDL> ready`

These are evidence of named screens, models, operation status and progress/log concepts. Static strings do not prove how callbacks, process stdout/stderr, Kotlin flows or IPC deliver events.

## What is not established

This static pass cannot establish:

1. Which Compose/ViewModel event invokes each native method.
2. Whether ADB/Fastboot are launched with ProcessBuilder, JNI, a shell session, or more than one path.
3. How stdout, stderr, exit codes, progress and cancellation reach the UI.
4. How Android USB permission grants become file descriptors and which component owns them.
5. The exact partition enumeration source, partition model mapping, file format handling, or read/write lifecycle.
6. Runtime behavior on a physical device.

## UniTool integration constraints

For UniTool, use the evidence as a feature inventory only. Do not transplant MOFLASH's USB manager or FD server. UniTool should continue to use its own single USB permission/session owner, existing FD broker/libusb bridge, and shared terminal/task-output path.

The safe implementation scope for this branch is read-only device discovery, metadata inspection, file browsing/transfer where the user has ordinary authorized access, partition inventory/metadata viewing, and consistent progress/error presentation. This report does not implement destructive device changes, protection bypasses, or privileged access.

## Next analysis pass

Once the REA prerequisites are available, use its Android package/class/method/reference/JNI inspection workflow to produce evidence-backed traces for:

- `MainActivity` and application initialization.
- `UsbHostManager` and `FdPass`.
- ADB/Fastboot launch and output handling.
- File manager model and transfer UI.
- Partition inventory models and read-only partition metadata presentation.
- Antumbra/MTK process and progress events.

Do not mark any GUI-to-native relationship as confirmed until a decompiled caller/reference trace supports it.
