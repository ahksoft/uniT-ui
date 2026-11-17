# MOFLASH 5.8.0 decompiler-source investigation

**Source:** `MOFLASH_5.8.0-release-20261017.apk_Decompiler.com.zip` supplied in this conversation.  
**Inspection:** Decompiled Java sources, package classes, and targeted source searches inside the archive.  
**Branch:** `feature/moflash-research-safe`  
**Scope:** GUI architecture, ADB/Fastboot/MTK component boundaries, USB permission/FD handling, file-manager evidence, partition metadata and task/output evidence. This is static source inspection, not a runtime trace.

## 1. Why this archive is more useful

The archive contains 9,588 entries:
- 4,613 decompiled Java source files under `sources/`.
- 4,614 smali files under `smali/`.
- 361 resource entries.

Unlike the earlier APK-only static string pass, this archive exposes readable decompiled classes and methods. Many app implementation classes are obfuscated into `defpackage`, so feature-to-screen mapping is possible but needs careful reference tracing.

## 2. Main application and GUI framework

Confirmed:
- Main activity: `com.Android.MoFlash.MainActivity`.
- Application class: `com.Android.MoFlash.MoFlashApplication`.
- The MainActivity imports Compose runtime/UI classes and renders Compose content.
- App settings are stored under SharedPreferences name `momiuix_settings`, including language, blur, page scale and welcome-notification preference.

The main activity is an application shell, not the implementation of the ADB/Fastboot/partition engines. Feature code is distributed across obfuscated `defpackage` classes and named data classes.

## 3. USB discovery, classification, permission and FD ownership

### Confirmed Java class: `com.Android.MoFlash.data.UsbHostManager`

The decompiled source exposes the following public operations:
- `listDevices(Context)`
- `classifyDevice(vid, pid, UsbDevice)`
- `hasPermission(Context, UsbDevice)`
- `requestPermission(Context, UsbDevice)`
- `registerPermissionReceiver(Context, callback)`
- `open(Context, UsbDevice)`
- `currentFd()`
- `currentVidPid()`
- `listUsbBrief()`
- `pickTarget(devices, fallback)`
- `setPreferred(Context, UsbDevice)`
- `close(preservePreferred)`

Observed implementation details:
- Enumerates devices using Android `UsbManager.getDeviceList()`.
- Attempts to read USB serial numbers and interface descriptors.
- Builds a device-node path in the form `/dev/bus/usb/<bus>/<device>`.
- Classifies devices using VID/PID and USB interface class/subclass/protocol.
- Includes explicit labels for Qualcomm EDL-like product IDs, Fastboot, ADB and MTK BROM/Preloader candidates.
- Requests permission through an app-scoped broadcast action `com.Android.MoFlash.USB_PERMISSION`.
- Opens the selected device through `UsbManager.openDevice()`.
- Retains a static `UsbDeviceConnection` and returns its file descriptor through `currentFd()`.
- Tracks preferred device identity using device ID, serial, VID and PID; attempts to reselect the preferred device after enumeration changes.
- `close()` closes the connection and clears active-device state.

This is direct evidence that this Java manager itself owns an Android USB connection and exposes its FD. It is not yet proof of which native library receives that FD.

### Confirmed Java class: `com.Android.MoFlash.data.FdPass`

- Static initializer loads native library `fdpass`.
- Exposes native method `sendFd(int, String)`.

The earlier DEX evidence also showed native exports for `sendFd`, `serveFdLoop` and `stopServeLoop`; in this decompiler archive the visible Java wrapper exposes only `sendFd`. Do not assume the additional native methods have Java wrappers in this version.

### Architectural implication for UniTool

MOFLASH has a distinct Java USB connection manager and an FD-passing native component. UniTool should not copy this ownership model wholesale. Keep UniTool's existing shared USB session owner and broker, and adapt feature engines to consume the existing authorized FD/session interface.

## 4. ADB GUI and command runner evidence

The decompiled class `defpackage.ve` contains an ADB command allowlist, including:
- device listing and state queries;
- connect/disconnect/pair;
- push/pull/sync;
- shell and logcat;
- install/uninstall;
- reboot and sideload;
- root/remount and server lifecycle actions.

The same class contains coroutine-backed state updates and an execution entry point that accepts a command type, context, strings, output builder and multiple UI state holders. It sets a running state, updates a task state and launches a coroutine.

This is evidence of a GUI-facing ADB task/controller layer. It does not, by itself, prove the final process-launch mechanism or exact output-stream callback path. The full method body should be followed through its coroutine lambda and process helper before implementing a compatible dispatcher.

## 5. Fastboot and native command interface evidence

The earlier 5.8.5 APK inspection found `libnativecmd.so` JNI exports for device detection, getvar, slot information and device-mode queries. This 5.8.0 decompiler archive does not expose a readable class literally named `NativeCmd` in the named package paths inspected so far. The native binding may be represented by an obfuscated wrapper or may differ between versions.

Do not assume 5.8.0 and 5.8.5 have identical JNI signatures or native implementations. The next step is to search all Java sources for `System.loadLibrary`, native declarations and references to the native wrapper, then correlate with the 5.8.0 APK's own ELF exports.

## 6. Partition manager and Super image metadata

### Confirmed named class: `com.Android.MoFlash.data.SuperMergeManager`

The source includes:
- A `runBin` helper that constructs a `ProcessBuilder`.
- Inheritance of the current process environment.
- Special handling for `LD_LIBRARY_PATH`.
- A `findSuperDefJson` helper that searches extracted firmware metadata directories for `super_def.*.json`.
- A `parseSuperConfig` helper that parses JSON fields:
  - `super_meta.size`
  - `block_devices` with name, size, block size and alignment
  - `groups` with name and maximum size
  - `partitions` with name, path, size and group name
- A `buildLpmakeArgs` helper that constructs argument lists for the bundled logical-partition image tool, including output and partition/image associations.

This proves the application has a structured super-image metadata parser and a process-launch helper for its super-image tooling. It is not a proof of the GUI-to-manager call path, nor does it establish all partition read/write/erase behavior.

For UniTool, the reusable architectural idea is to represent super metadata as typed models (block devices, groups, partitions) and expose validation/preview in the UI. Do not port destructive execution routines.

## 7. ADB file manager evidence

The archive contains the model-like source `defpackage.t5` with the string `AdbFileEntry(name=`. This confirms a decompiled ADB file-entry representation exists.

The archive also contains file-manager and ADB transfer UI strings from DEX resources. This source pass has not yet established:
- the class that lists remote directories;
- how paths are encoded/quoted;
- whether listing uses `adb shell`, `exec-out`, or another API;
- how push/pull progress is collected;
- how errors and permissions are represented in the file browser.

Those must be traced before implementing equivalent behavior.

## 8. Progress, task status and logs

The archive contains task/progress strings and source references such as `ACTION_SET_PROGRESS`, `InProgress(latestEvent=...)`, and log-copy UI strings. The ADB controller class `defpackage.ve` also updates several state holders and starts coroutine work.

The exact stdout/stderr-to-GUI flow remains unproven. Trace the command helper and coroutine class to identify:
- process creation;
- separate stdout/stderr readers;
- line/event aggregation;
- exit-code collection;
- cancellation/timeout;
- UI state completion/error transitions.

## 9. MTK and other engines

The archive contains app-specific classes `MtkArgs` and `QdlArgs` (observed in the earlier APK inventory) and native libraries identified in the 5.8.5 package. The 5.8.0 Java archive should be searched further for the exact engine launchers and their process helpers. This pass has not yet established the full MTK GUI-to-native chain.

## 10. Confirmed versus unresolved

| Area | Evidence status |
|---|---|
| Compose application shell | Confirmed |
| Android USB enumeration and classification | Confirmed in Java source |
| Android USB permission request | Confirmed in Java source |
| USB open and FD retrieval | Confirmed in Java source |
| Native FD passing wrapper | Confirmed |
| ADB command allowlist/controller | Confirmed in decompiled Java |
| ADB process/output transport | Not fully traced |
| Fastboot Java caller to native bridge | Not yet traced in 5.8.0 source |
| Super image JSON model parsing | Confirmed |
| Super-image process helper | Confirmed |
| ADB file-entry model | Confirmed |
| File-manager listing/transfer implementation | Not yet traced |
| Partition UI-to-task call graph | Not yet traced |
| stdout/stderr/progress delivery | Not yet traced |
| Physical-device runtime behavior | Not tested |

## 11. UniTool implementation guidance

Recommended safe integration boundaries:
1. Reuse UniTool's existing USB manager, permission flow, FD broker and libusb bridge.
2. Keep ADB/Fastboot/MTK as engines behind one shared task dispatcher.
3. Model operations as typed requests and typed task states rather than building command strings inside Compose callbacks.
4. Stream stdout/stderr and progress events into the existing shared terminal/log output.
5. Implement read-only device information, directory browsing, authorized file transfer, partition inventory and metadata preview first.
6. Do not copy MOFLASH's second USB manager or introduce another FD owner.
7. Do not port privileged, destructive, protection-bypass or bootloader-modification behavior.

## 12. Remaining reverse-engineering tasks

- Find all source references to `System.loadLibrary` and native declarations.
- Trace `defpackage.ve` ADB command coroutine through its process-launch helper and output reader.
- Identify Fastboot UI composables and their ViewModel/controller callbacks.
- Identify file manager listing and transfer implementation behind `AdbFileEntry`.
- Trace partition inventory and metadata model construction.
- Trace progress and cancellation event producers/consumers.
- Compare 5.8.0 native ELF exports with the earlier 5.8.5 inventory before assuming cross-version compatibility.
