# MOFLASH 5.8.0 — ADB transfer and Fastboot helper follow-up

**Source:** Decompiled Java in `MOFLASH_5.8.0-release-20261017.apk_Decompiler.com.zip`.  
**Branch:** `feature/moflash-research-safe`  
**Scope:** Follow-up inspection of `uc` and `ss0`, identified in the second-pass report.

## ADB push/pull path — now traced to the process-task boundary

The decompiled coroutine `defpackage.uc` confirms both transfer directions:

### Pull

- The pull branch logs the remote source path.
- It creates a local `File` under the supplied destination directory, using the remote path's final path component as the local filename.
- It constructs the argument list `pull, remotePath, localAbsolutePath`.
- It passes that argument list into `defpackage.a6` with context, ADB executable/path data, mode flags, device selection value and two callbacks.
- It awaits the returned integer exit code.
- A nonzero exit code is reported as a pull failure; exit code zero reports the saved local path.

### Push

- The push branch logs the local source path.
- It derives the local file name and appends it to the requested remote destination directory, adding a slash when needed.
- It constructs the argument list `push, localPath, remoteDestination`.
- It passes that argument list into the same `defpackage.a6` task helper with callbacks and awaits its exit code.
- A nonzero exit code is reported as a push failure; exit code zero reports the remote destination.

This confirms the ADB GUI-to-coroutine-to-transfer-task boundary and the argument ordering. The source inspected here does not establish a separate byte-level progress parser; callback behavior and process output handling are delegated to `a6` and its lower-level helpers. It also does not establish remote directory listing.

## Fastboot read-only helper — boundary confirmed, implementation still opaque

`defpackage.mt0` creates the argument list `getvar current-slot`, obtains `UsbHostManager.currentFd()` unless using its alternate mode, and passes the FD into `defpackage.ss0`.

The decompiled body of `ss0.n(...)` is not available in this archive: JADX emitted an unsupported/decompilation placeholder for a large coroutine method (738 instruction units). Therefore, the source confirms the Fastboot query request and FD handoff into the helper, but not whether `ss0` uses JNI, a subprocess, a local socket or another transport. No native method or receiver should be inferred from this call site.

## Updated integration implications

- UniTool's ADB transfer UI can model push/pull as structured requests containing direction, local path, remote path, target device/session and callbacks.
- Keep command arguments as an argument vector; do not concatenate user paths into a shell command.
- Route task output and exit status through UniTool's existing shared terminal/task event channel.
- Treat transfer progress as unknown until `a6` and its reader callbacks are fully traced.
- For Fastboot, implement only read-only getvar/device-information queries in the initial compatibility layer. The MOFLASH helper's transport remains unresolved.
- Do not reproduce privileged or destructive operations from MOFLASH.

## Remaining source gaps

1. Inspect `defpackage.a6` and its reader callbacks for ADB process invocation, output, progress and cancellation.
2. Find the remote directory-listing caller and determine how it constructs `AdbFileEntry` (`defpackage.t5`).
3. Obtain the original 5.8.0 APK/native libraries to inspect matching ELF exports; the supplied decompiler ZIP contains Java/smali/resources only.
4. Do not claim runtime parity without authorized-device testing.
