# MOFLASH 5.8.0 — second-pass call-path investigation

**Source:** `MOFLASH_5.8.0-release-20261017.apk_Decompiler.com.zip` supplied in this conversation.  
**Branch:** `feature/moflash-research-safe`  
**Method:** Full-archive Java-source searches followed by inspection of the matching decompiled classes and call sites. This remains static analysis; no MOFLASH runtime or physical-device test was performed.

## 1. USB permission → connection → FD → native client

This pass closes an important gap from the first report: the FD bridge is not merely an unused wrapper.

### Permission and open path

- `com.Android.MoFlash.data.UsbHostManager` owns the Android `UsbDeviceConnection`, opens it through `UsbManager.openDevice()`, and exposes `currentFd()`.
- `defpackage.k6` has a callback branch that calls `UsbHostManager.open(context, usbDevice)`, obtains the returned FD (or falls back to `currentFd()`), stores it in UI state, and launches a coroutine with that FD.
- `defpackage.vs` and `defpackage.zs` also call `requestPermission()` and `open()`; these are additional permission/open orchestration call sites.
- `defpackage.rr2` reads `currentFd()` when composing USB connection state.

### Confirmed FD handoff call sites

The full Java scan found direct use of both `UsbHostManager.currentFd()` and `FdPass.INSTANCE.sendFd()`:

- `defpackage.ib2`: the QDL task/controller reads the current USB FD. In the non-root path it creates a per-task abstract-socket token beginning `@momiui_qdl_`, launches the QDL coroutine, and calls `FdPass.sendFd(fd, token)`. It reports a negative FD-send result to the task log.
- `defpackage.is2`: another QDL execution path reads the current FD, creates the same token family when an FD is present, starts its coroutine task and calls `FdPass.sendFd(fd, token)`.
- `defpackage.mt0`: a Fastboot-related task reads `currentFd()` and passes the FD into a coroutine/helper object while requesting the `getvar current-slot` operation. The source inspected here establishes the FD as an input to that helper, but not the native-side receive implementation.

This establishes a real Java-side FD handoff from MOFLASH's USB manager into QDL task paths, and an FD-aware Fastboot helper path. It still does not establish the exact native receiver implementation or prove that every engine uses the same socket protocol.

## 2. QDL task execution and output handling

Two source locations show a concrete process/output pattern:

- `defpackage.qs2.I0(...)` constructs a `ProcessBuilder` from an argument array, copies the current environment, applies requested environment overrides, optionally sets the working directory, calls `redirectErrorStream(true)`, starts the process, and consumes the merged process output through `r20.d0(...)` with separate callback objects for output/error handling. It then waits for process completion and records the exit code.
- A second process-launch path in `defpackage.qs2` builds a shell command, optionally sets the working directory, merges stderr into stdout, starts the process, streams output through the same reader utility, and waits for completion.
- `defpackage.ib2` and `defpackage.is2` wrap QDL work in coroutine/task state and report FD-transfer failures to the task log.

The inspected process runner merges stderr into stdout; it does not preserve two independent live streams in this path. The exact semantics of each callback and cancellation behavior need the corresponding `r20.d0` and coroutine helper bodies before claiming a complete terminal contract.

## 3. Generic process runner used by other tools

`defpackage.d01` contains another `ProcessBuilder` runner. It:
- creates a process from an argument list;
- copies the current environment;
- applies environment entries, including special `LD_LIBRARY_PATH` composition;
- enables merged stderr/stdout;
- starts the process and reads its output;
- waits for its exit status.

This is evidence of a shared process-execution utility used by at least one tool path. The source pass does not prove that every ADB, Fastboot, MTK and QDL action funnels through this one helper.

## 4. ADB command runner and file-transfer callbacks

The ADB UI/controller `defpackage.ve` contains the command allowlist and delegates push/pull work to coroutine class `defpackage.uc`:
- `ve.o(...)` checks that ADB is ready and a device is connected, then launches a `uc(..., mode=0)` coroutine for pull.
- `ve.p(...)` performs the equivalent checks and launches `uc(..., mode=1)` for push.

This establishes the UI-to-coroutine boundary for push/pull, but this pass did not yet inspect the full `uc` implementation far enough to prove its exact argument quoting, progress parsing, or error mapping.

The model `defpackage.t5` is directly confirmed as:
- name;
- directory flag;
- size;
- permissions;
- modification time;
- symbolic-link flag.

No Java source reference to the `t5` model was found outside its own declaration in the decompiled Java tree. Consequently, the remote directory-listing implementation and the mapping into this model remain unresolved. Do not infer an `adb shell ls` protocol from the model fields alone.

## 5. Fastboot screen and operation evidence

`defpackage.nq1` contains Compose UI labels for Fastboot Tool, QDL Flash Tool, MTK Tool and ADB Tool. This confirms the top-level tool selection UI and labels, but not the Fastboot action callback chain.

`defpackage.mt0` is a Fastboot-related coroutine/task class. Its inspected code builds the argument list `getvar current-slot`, obtains the current USB FD unless using its alternate mode, and passes that FD to helper `ss0`. This is a concrete read-only Fastboot query path. The helper `ss0` must be inspected to identify whether the operation uses JNI, a subprocess or another transport.

No Java file, smali path, or class name matching `NativeCmd` / `nGetvarAll` exists in this 5.8.0 decompiler archive. Do not transplant the 5.8.5 JNI symbol names into a 5.8.0 Java implementation without the matching 5.8.0 APK native binaries and their symbol table.

## 6. Local abstract socket evidence

`defpackage.c6.e()` connects to the abstract local socket `com.android.moflash.diox.proc`, sets a short socket timeout, writes a one-byte request and reads a one-byte response. This is a daemon-health/protocol probe.

A helper branch in `defpackage.y5` performs a similar one-byte request/response exchange and, on a successful response, reads structured fields including integer and long values plus a sequence of entries. The snippets establish a small binary local-socket protocol for a daemon-backed service, but do not establish that this socket is the same channel as the per-task `@momiui_qdl_...` FD handoff tokens.

## 7. Partition/super metadata process runner

The earlier report's `SuperMergeManager` findings are complemented by `defpackage.d01`, which also contains a ProcessBuilder-based tool runner with environment and library-path setup. No GUI-to-partition-controller call chain was established in this pass. Keep UniTool's first implementation to parsing, validation and read-only preview of metadata.

## 8. Evidence matrix after second pass

| Area | Updated evidence status |
|---|---|
| Android USB permission and connection owner | Confirmed: `UsbHostManager` |
| GUI callback opens USB and stores FD | Confirmed: `k6` |
| FD passed to QDL task | Confirmed: `ib2`, `is2` call `FdPass.sendFd` |
| FD-aware Fastboot helper path | Confirmed: `mt0` passes FD to `ss0`; receiver/transport unresolved |
| QDL ProcessBuilder/output path | Confirmed: `qs2.I0` and related runner |
| Generic ProcessBuilder runner | Confirmed: `d01` |
| ADB push/pull UI-to-coroutine boundary | Confirmed: `ve.o`, `ve.p` → `uc` |
| ADB transfer implementation details | Partially traced; `uc` needs deeper inspection |
| ADB remote directory listing | Unresolved |
| Fastboot Compose screen labels | Confirmed: `nq1` |
| Fastboot JNI/native binding in 5.8.0 | Unresolved; no matching Java wrapper found |
| Daemon local-socket health protocol | Confirmed: `c6.e` |
| Local-socket structured response helper | Partially confirmed: `y5` |
| Exact stdout/stderr separation | Not established; inspected runners merge stderr into stdout |
| Physical-device runtime behavior | Not tested |

## 9. UniTool integration notes

For UniTool, the transferable architecture is the boundary design, not MOFLASH's implementation:

1. Keep UniTool's existing single Android USB permission/session owner and FD broker.
2. Pass an already-authorized session/FD into an engine task through a typed interface; do not add a second Android USB connection manager.
3. Have one task dispatcher own lifecycle, cancellation, exit status and log events.
4. Preserve stdout/stderr separately in UniTool if its current terminal supports it; MOFLASH's inspected runners often merge them.
5. Keep read-only Fastboot queries such as device variables, ADB device state and authorized file browsing as the initial compatibility scope.
6. Treat file-transfer and partition mutation routines as out of scope for this integration pass; only inspect/preview metadata and ordinary authorized file operations.
7. Do not reproduce privileged modes, destructive device changes, bootloader modification or protection-bypass paths.

## 10. Remaining source work

- Inspect `defpackage.uc` and its helpers to document ADB push/pull process invocation, progress and failure states.
- Inspect `defpackage.ss0` to understand the read-only Fastboot getvar helper and its FD transport boundary.
- Inspect the QDL coroutine/helper classes and `r20.d0` callbacks to map progress, cancellation and output semantics.
- Identify the remote file-list command and map its result into `t5`.
- Obtain the matching 5.8.0 APK/native libraries before correlating JNI exports. The decompiler ZIP contains no APK payload or native ELF files.
- Do not claim runtime parity until tested on a controlled device with ordinary authorized access.
