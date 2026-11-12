#!/usr/bin/env bash
set -euo pipefail

ABIS=(arm64-v8a)
LIBS=(
  libadb.so libbash.so libclear.so libcoreutils.so
  libdatastore_shared_counter.so libfastboot.so libfwmark.so
  libheimdall.so libmake_f2fs.so libmke2fs.so libpty.so
  libqdl.so libtest.so
)

missing=0
for abi in "${ABIS[@]}"; do
  dir="app/src/main/jniLibs/$abi"
  echo "== $abi =="
  for lib in "${LIBS[@]}"; do
    if [[ -s "$dir/$lib" ]]; then
      printf 'OK      %s (%s bytes)\n' "$lib" "$(wc -c < "$dir/$lib")"
    else
      printf 'MISSING %s\n' "$lib"
      missing=$((missing + 1))
    fi
  done
done

# libusb_bridge.so is built reproducibly from source by CMake (the `usb_bridge`
# target) and packaged into the APK by AGP's externalNativeBuild; it is not a
# prebuilt staged in the source tree.
if [[ -s app/src/main/cpp/usb_bridge.cpp ]] &&
   grep -q 'add_library(usb_bridge SHARED usb_bridge.cpp)' app/src/main/cpp/CMakeLists.txt; then
  echo "SOURCE  libusb_bridge.so (CMake target; packaged by AGP externalNativeBuild)"
else
  echo "MISSING source-built libusb_bridge.so implementation"
  missing=$((missing + 1))
fi

# libusb.so is built reproducibly from the pinned upstream sources by the
# `libusb_prebuilt` CMake target (OUTPUT_NAME "usb") and packaged by AGP. It is
# deliberately not staged in the source jniLibs folder: AGP 9 rejects a source
# copy and the CMake output as a duplicate lib/arm64-v8a/libusb.so.
if [[ -s app/src/main/cpp/unitool_linux_android_helper.c ]] &&
   grep -q 'add_library(libusb_prebuilt SHARED' app/src/main/cpp/CMakeLists.txt &&
   grep -q 'OUTPUT_NAME "usb"' app/src/main/cpp/CMakeLists.txt; then
  echo "SOURCE  libusb.so (CMake target; packaged by AGP externalNativeBuild)"
else
  echo "MISSING source-built libusb.so implementation"
  missing=$((missing + 1))
fi

# The PTY emulator JNI library is built by the :terminalview ndkBuild module.
if grep -q 'LOCAL_MODULE:= libunitool-term' terminalview/src/main/jni/Android.mk; then
  echo "SOURCE  libunitool-term.so (terminalview ndkBuild target)"
else
  echo "MISSING terminalview PTY JNI implementation"
  missing=$((missing + 1))
fi

# The USB JNI bridge is built from source by CMake for every ABI.
if grep -q 'add_library(unitool_usb_jni SHARED usb_jni.cpp)' app/src/main/cpp/CMakeLists.txt; then
  echo "SOURCE  libunitool_usb_jni.so (CMake target, per-ABI build)"
else
  echo "MISSING source-built USB JNI bridge"
  missing=$((missing + 1))
fi

# The libusb probe links the same packaged libusb backend used by ADB.
if grep -q 'add_executable(unitool_libusb_probe' app/src/main/cpp/CMakeLists.txt &&
   grep -q 'libunitool_libusb_probe.so=bin/unitool-libusb-probe' app/src/main/assets/lib_symlinks.properties; then
  echo "SOURCE  unitool-libusb-probe (libusb enumeration + descriptor-open test)"
else
  echo "MISSING libusb backend diagnostic command"
  missing=$((missing + 1))
fi

if grep -q 'add_executable(heimdall_rpc' app/src/main/cpp/CMakeLists.txt &&
   grep -q 'copy_if_different' app/src/main/cpp/CMakeLists.txt; then
  echo "SOURCE  heimdall (pinned Heimdall core + Android RPC launcher)"
else
  echo "MISSING source-built Heimdall RPC command"
  missing=$((missing + 1))
fi

# Termux package commands are staged into bootstrap assets by the build workflow.
for command in git curl wget openssl less python python3; do
  if [[ -s "app/src/main/assets/bootstrap/bin/$command" ]]; then
    echo "ASSET   $command"
  else
    echo "MISSING Termux package command: $command"
    missing=$((missing + 1))
  fi
done

echo
echo "Missing native inputs: $missing"
if [[ "${STRICT_NATIVE_AUDIT:-0}" == "1" && "$missing" -gt 0 ]]; then
  echo "Native toolchain inputs are incomplete."
  exit 2
fi
