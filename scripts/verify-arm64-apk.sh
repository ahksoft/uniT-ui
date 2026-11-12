#!/usr/bin/env bash
set -euo pipefail

APK_DIR="${1:?usage: verify-arm64-apk.sh <apk-directory>}"
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' -print | sort)

if [[ "${#APKS[@]}" -ne 1 ]]; then
  echo "Expected exactly one APK, found ${#APKS[@]}" >&2
  printf '%s\n' "${APKS[@]}" >&2
  exit 1
fi

APK="${APKS[0]}"
case "$(basename "$APK")" in
  *universal*|*armeabi*|*x86*) echo "Unexpected APK variant name: $APK" >&2; exit 1 ;;
esac

mapfile -t ABIS < <(unzip -Z1 "$APK" | awk -F/ '$1=="lib" && NF>=3 {print $2}' | sort -u)
if [[ "${#ABIS[@]}" -ne 1 || "${ABIS[0]}" != "arm64-v8a" ]]; then
  echo "APK must contain only lib/arm64-v8a; found: ${ABIS[*]:-(no native libraries)}" >&2
  exit 1
fi

if unzip -Z1 "$APK" | grep -Eq '^lib/(armeabi-v7a|armeabi|x86|x86_64|riscv64)/'; then
  echo "Forbidden ABI payload detected" >&2
  exit 1
fi

echo "Verified ARM64-only APK: $APK"
unzip -Z1 "$APK" | grep '^lib/arm64-v8a/' | head -20
