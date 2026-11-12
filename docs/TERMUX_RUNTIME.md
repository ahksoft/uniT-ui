# UniTool Termux-Compatible Runtime

## Decision

Keep the current Android application target SDK and preserve the existing app-private terminal, CPython runtime, and Android USB broker. Do not change targetSdk merely to imitate Termux. On current Android releases, executable files extracted into writable app data can be denied execution; UniTool therefore packages NDK-built ELF executables and shared libraries as APK native libraries under `jniLibs/arm64-v8a`. The Android package manager extracts them into `ApplicationInfo.nativeLibraryDir`, and `NativeToolchain` exposes commands through absolute symlinks under `$PREFIX/bin`.

This is a hybrid runtime, not a claim that arbitrary Termux packages can be installed and executed unchanged. Data, scripts, configuration, package metadata, man pages, certificates, and other non-executable assets may live in the private prefix. Executable package payloads must be built for Android/Bionic with the NDK and included in the APK native-library directory.

## Goals

- Separate ARM64 ELF executables for `git`, `curl`, `openssl`, and `wget`; no multicall binary.
- Build from pinned upstream source revisions using Android NDK API 24 or later.
- Keep runtime libraries in the same ABI-specific native library directory.
- Use one coherent OpenSSL installation for the OpenSSL CLI, curl, Git HTTPS, and Wget.
- Preserve `libusb.so` and `libusb_bridge.so` exactly as the existing broker-backed USB implementation. Never replace them with a stock libusb.
- Keep CPython 3.14.10 and its currently tested packages intact.
- Add ELF architecture, dynamic dependency, executable smoke, and APK-content checks to CI.

## Runtime layout

```
$PREFIX/
  bin/                 command symlinks and shell wrappers
  etc/                 profile, TLS and tool configuration
  lib/                 non-executable runtime data and Python packages
  share/               certificates, documentation and data files
  var/lib/unitool-pkg/ package manifest and installed-file metadata
  tmp/
$HOME/
```

Native executable payloads and shared libraries are packaged under `app/src/main/jniLibs/arm64-v8a` as distinct files named `lib<tool>.so`. Their command links are created in `NativeToolchain.kt` from `lib_symlinks.properties`. The `.so` suffix is an Android packaging convention; the ELF file itself is an executable, not a shared library.

## Package compatibility boundary

A full Termux `apt/dpkg/pkg` environment cannot safely be promised by simply unpacking a Termux rootfs into app data: package managers normally install executable files into a writable prefix, and Android may prevent those files from executing. UniTool should use a UniTool package manifest/installer for data-only packages and APK-repackaged NDK executables. Do not ship a misleading `apt` or `pkg` command until its install, upgrade, removal, dependency, and rollback behavior is implemented and tested.

## Build sequence

1. Build OpenSSL and stage its CLI plus runtime libraries.
2. Build curl against that OpenSSL and zlib.
3. Build Git against the same OpenSSL/curl/zlib configuration; include required Git helper executables as separate APK native payloads.
4. Build GNU Wget against the same OpenSSL configuration.
5. Audit every staged ELF for AArch64, Android/Bionic compatibility, and resolved DT_NEEDED entries.
6. Add the executable aliases and native-library search path to the terminal environment.
7. Build the APK and test `git --version`, `curl --version`, `openssl version`, and `wget --version` on an Android device.
8. Test HTTPS certificate validation, Git HTTPS transport, redirects, IPv4/IPv6 behavior, and offline command startup.

## Safety and compatibility constraints

- Do not lower targetSdk in this branch without a separate compatibility review.
- Do not change USB broker code or the absolute `UNITOOL_LIBUSB_PATH` configuration.
- Do not copy host Linux binaries or glibc-linked binaries into the Android package.
- Do not claim that a build is complete until CI and on-device smoke tests pass.
- Keep source versions, source URLs, checksums/signature verification, NDK version, and Android API level recorded in the build manifest.
