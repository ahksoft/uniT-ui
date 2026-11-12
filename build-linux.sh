#!/usr/bin/env bash
set -Eeuo pipefail

# Build UniTool's ARM64 Android APK from a Linux workstation.
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VARIANT="debug"
WORK_DIR="$ROOT/build/linux-runtime-work"
ANDROID_API=24
COMPILE_SDK=36.1
BUILD_TOOLS=36.0.0
CMAKE_VERSION=3.22.1
NDK_VERSION=25.2.9519653

usage() {
  cat <<'USAGE'
Usage: ./build-linux.sh [--debug | --release]

Builds the ARM64 Android APK on a Linux host. Termux command assets are
downloaded and staged before the APK is built.

Environment:
  ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK directory
  ANDROID_NDK_ROOT                 Optional NDK directory (defaults to the pinned NDK)
  JAVA_HOME                        Optional JDK 17 directory
USAGE
}

while (($#)); do
  case "$1" in
    --debug) VARIANT=debug ;;
    --release) VARIANT=release ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"; }
on_error() {
  local status=$?
  echo >&2
  echo "Build stopped (exit $status) at line ${BASH_LINENO[0]:-unknown}. Work files: $WORK_DIR" >&2
  exit "$status"
}
trap on_error ERR

[[ "$(uname -s)" == Linux ]] || die "This script requires Linux. Detected: $(uname -s)."
case "$(uname -m)" in
  x86_64|aarch64) HOST_ARCH="$(uname -m)" ;;
  *) die "Supported Linux hosts are x86_64 and aarch64; detected: $(uname -m)." ;;
esac

for command_name in bash curl git python3 dpkg-deb patchelf readelf sha256sum unzip zip java awk sed grep find tar; do
  need "$command_name"
done
JAVA_MAJOR="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
[[ "$JAVA_MAJOR" == 17 ]] || die "JDK 17 is required; detected Java major: ${JAVA_MAJOR:-unknown}."

if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then ANDROID_HOME="$ANDROID_SDK_ROOT"; fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "$HOME/Android/Sdk" "$HOME/android-sdk"; do
    if [[ -d "$candidate/platforms/android-$COMPILE_SDK" ]]; then ANDROID_HOME="$candidate"; break; fi
  done
fi
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || die "Android SDK not found. Set ANDROID_HOME."
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/$NDK_VERSION"
fi
[[ -f "$ANDROID_NDK_ROOT/source.properties" ]] || die "Android NDK not found at $ANDROID_NDK_ROOT. Install NDK $NDK_VERSION or set ANDROID_NDK_ROOT."
NDK_ACTUAL="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_ROOT/source.properties" | head -n 1)"
[[ "$NDK_ACTUAL" == "$NDK_VERSION" ]] || die "This project pins NDK $NDK_VERSION; found ${NDK_ACTUAL:-unknown} at $ANDROID_NDK_ROOT."
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
case "$HOST_ARCH" in
  x86_64) NDK_HOST_TAG=linux-x86_64 ;;
  aarch64) NDK_HOST_TAG=linux-aarch64 ;;
esac
NDK_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
[[ -x "$NDK_BIN/aarch64-linux-android${ANDROID_API}-clang" ]] || die "NDK has no $NDK_HOST_TAG toolchain: $NDK_BIN"
[[ -s "$ANDROID_HOME/platforms/android-$COMPILE_SDK/android.jar" ]] || die "Missing Android platform android-$COMPILE_SDK."
[[ -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt" && -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2" ]] || die "Missing Android Build Tools $BUILD_TOOLS."
[[ -x "$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake" && -x "$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/ninja" ]] || die "Missing Android SDK CMake $CMAKE_VERSION (including Ninja)."

export ANDROID_API
export PATH="$NDK_BIN:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$WORK_DIR"
export RUNNER_TEMP="$WORK_DIR"

log "Build environment"
printf 'Repository: %s\nVariant: %s\nLinux host: %s\nAndroid SDK: %s\nAndroid NDK: %s\nJava: %s\n' \
  "$ROOT" "$VARIANT" "$HOST_ARCH" "$ANDROID_HOME" "$ANDROID_NDK_ROOT" "$(java -version 2>&1 | head -n 1)"

log "Stage official Termux CLI runtime assets"
export TERMUX_PACKAGE_CACHE="$ROOT/build/linux-cli/unitool-termux-cli"
export TERMUX_SKIP_CACHED_CHECKSUM="${TERMUX_SKIP_CACHED_CHECKSUM:-1}"
TERMUX_OFFLINE="${TERMUX_OFFLINE:-0}"
if [[ -z "${TERMUX_REFRESH_PACKAGE_INDEX:-}" ]]; then
  if [[ "$TERMUX_OFFLINE" == 1 ]]; then TERMUX_REFRESH_PACKAGE_INDEX=0; else TERMUX_REFRESH_PACKAGE_INDEX=1; fi
fi
export TERMUX_OFFLINE TERMUX_REFRESH_PACKAGE_INDEX
bash scripts/prepare-termux-cli-runtime.sh
for command_name in git curl wget openssl less python python3; do
  [[ -s "app/src/main/assets/bootstrap/bin/$command_name" ]] || die "Runtime staging did not produce bootstrap/bin/$command_name"
done
[[ -s app/src/main/assets/termux-python-runtime.zip ]] || die "Termux Python standard-library archive is missing."
[[ -s app/src/main/assets/bootstrap/lib/libpython3.14.so ]] || die "Termux libpython3.14.so is missing."

log "Audit native libraries and staged commands"
STRICT_NATIVE_AUDIT=1 bash scripts/audit-native-libs.sh

log "Validate Gradle wrapper and Java runtime"
chmod +x ./gradlew
./gradlew --no-daemon --version

log "Build $VARIANT APK"
if [[ "$VARIANT" == release ]]; then
  ./gradlew --no-daemon --stacktrace clean :app:assembleRelease
  APK_DIR=app/build/outputs/apk/release
else
  ./gradlew --no-daemon --stacktrace clean :app:assembleDebug
  APK_DIR=app/build/outputs/apk/debug
fi

log "Verify APK ABI"
bash scripts/verify-arm64-apk.sh "$APK_DIR"
APK="$(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' -print -quit)"
[[ -s "$APK" ]] || die "Gradle completed but no APK was found in $APK_DIR."
unzip -Z1 "$APK" | grep -Fxq assets/termux-python-runtime.zip || die "APK is missing assets/termux-python-runtime.zip."
unzip -p "$APK" assets/termux-python-runtime.zip > "$WORK_DIR/python-runtime-from-apk.zip"
python3 - "$WORK_DIR/python-runtime-from-apk.zip" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = set(archive.namelist())
    for suffix in ("/os.py", "/encodings/__init__.py"):
        if not any(name.endswith(suffix) for name in names):
            raise SystemExit("Python runtime archive is missing " + suffix)
print("Python standard library archive verified.")
PY

log "Build complete"
sha256sum "$APK"
printf '\nAPK: %s/%s\n' "$ROOT" "$APK"
printf 'Size: %s bytes\n' "$(wc -c < "$APK")"
