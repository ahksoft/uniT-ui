#!/usr/bin/env bash
set -Eeuo pipefail

# Build UniTool locally on an ARM64 Termux device.
# Mirrors .github/workflows/termux-cli-runtime.yml:
#   prepare Termux CLI assets -> strict native audit -> Gradle APK build
#   -> ARM64 APK verification -> Python asset verification.

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VARIANT="debug"
AUTO_INSTALL=1
KEEP_WORK=0
WORK_DIR="$ROOT/build/termux-local"
NDK_MAJOR_PREFERENCE=("25")
ANDROID_API="24"
COMPILE_SDK="36.1"
BUILD_TOOLS="36.0.0"
CMAKE_VERSION="3.22.1"

usage() {
  cat <<'USAGE'
Usage: ./build-termux.sh [options]

Options:
  --debug             Build the debug APK (default)
  --release           Build the release APK
  --no-install        Do not install missing Termux packages
  --keep-work         Keep the downloaded/extracted Termux package workspace
  --ndk 29|25         Select NDK major version (default: prefer 29, then 25)
  -h, --help          Show this help

Environment overrides:
  ANDROID_HOME / ANDROID_SDK_ROOT
  ANDROID_NDK_ROOT (optional explicit NDK path; must be r29 or r25)
  JAVA_HOME
  TERMUX_BUILD_JOBS
USAGE
}

while (($#)); do
  case "$1" in
    --debug) VARIANT="debug" ;;
    --release) VARIANT="release" ;;
    --no-install) AUTO_INSTALL=0 ;;
    --keep-work) KEEP_WORK=1 ;;
    --ndk)
      [[ $# -ge 2 && ( "$2" == "29" || "$2" == "25" ) ]] || { echo "ERROR: --ndk accepts only 29 or 25." >&2; exit 2; }
      NDK_MAJOR_PREFERENCE=("$2")
      shift
      ;;
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
  echo "Build stopped (exit $status) at line ${BASH_LINENO[0]:-unknown}." >&2
  echo "See the first failing command above. Work files: $WORK_DIR" >&2
  exit "$status"
}
trap on_error ERR

[[ "$(uname -m)" == "aarch64" ]] || die "This script is for ARM64 Termux (aarch64). Detected: $(uname -m)"
[[ -n "${PREFIX:-}" && -d "$PREFIX" ]] || die "Termux PREFIX is not set. Run this from Termux."

# Install the host-side tools used by the repository scripts and Gradle.
TERMUX_PACKAGES=(
  bash curl wget git python dpkg patchelf binutils coreutils findutils
  grep gawk sed tar unzip zip openjdk-17
)
missing=()
for package in "${TERMUX_PACKAGES[@]}"; do
  case "$package" in
    bash) command -v bash >/dev/null 2>&1 || missing+=("$package") ;;
    curl|wget|git|python|dpkg|patchelf|readelf|sha256sum|find|grep|awk|sed|tar|unzip|zip|java)
      case "$package" in
        dpkg) command -v dpkg-deb >/dev/null 2>&1 || missing+=("$package") ;;
        python) command -v python3 >/dev/null 2>&1 || missing+=("$package") ;;
        patchelf) command -v patchelf >/dev/null 2>&1 || missing+=("$package") ;;
        binutils) command -v readelf >/dev/null 2>&1 || missing+=("$package") ;;
        coreutils) command -v sha256sum >/dev/null 2>&1 || missing+=("$package") ;;
        findutils) command -v find >/dev/null 2>&1 || missing+=("$package") ;;
        gawk) command -v awk >/dev/null 2>&1 || missing+=("$package") ;;
        openjdk-17) command -v java >/dev/null 2>&1 || missing+=("$package") ;;
        *) command -v "$package" >/dev/null 2>&1 || missing+=("$package") ;;
      esac
      ;;
  esac
done
if (("${#missing[@]}" > 0)); then
  if ((AUTO_INSTALL)) && command -v pkg >/dev/null 2>&1; then
    log "Installing missing Termux packages: ${missing[*]}"
    pkg update -y
    pkg install -y "${missing[@]}"
  else
    die "Missing Termux packages: ${missing[*]}. Install them with pkg install."
  fi
fi

for command_name in bash curl git python3 dpkg-deb patchelf readelf sha256sum unzip java; do
  need "$command_name"
done

JAVA_MAJOR="$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')"
[[ "$JAVA_MAJOR" == "17" ]] || die "JDK 17 is required by this project; detected Java major: ${JAVA_MAJOR:-unknown}. Install openjdk-17 and set JAVA_HOME if needed."

# Find Android SDK. The SDK/NDK must be installed separately; sdkmanager
# binaries distributed for x86_64 are not executable on an ARM64 phone.
if [[ -z "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    "$PREFIX/opt/android-sdk" \
    "$PREFIX/share/android-sdk"; do
    if [[ -d "$candidate/platforms/android-$COMPILE_SDK" ]]; then
      ANDROID_HOME="$candidate"
      break
    fi
  done
fi
[[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]] || die "Android SDK not found. Set ANDROID_HOME to an installed Android SDK directory."
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"

# Select an installed ARM64-host NDK. Prefer r29, then r25, unless the
# caller explicitly supplies ANDROID_NDK_ROOT or --ndk.
NDK_ACTUAL=""
if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  [[ -f "$ANDROID_NDK_ROOT/source.properties" ]] || die "ANDROID_NDK_ROOT has no source.properties: $ANDROID_NDK_ROOT"
  NDK_ACTUAL="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_ROOT/source.properties" | head -n 1)"
  NDK_MAJOR="${NDK_ACTUAL%%.*}"
  [[ "$NDK_MAJOR" == "29" || "$NDK_MAJOR" == "25" ]] || die "Only NDK r29 and r25 are supported here; found ${NDK_ACTUAL:-unknown}."
  if [[ "${NDK_MAJOR_PREFERENCE[0]}" != "$NDK_MAJOR" && ${#NDK_MAJOR_PREFERENCE[@]} -eq 1 ]]; then
    die "ANDROID_NDK_ROOT is NDK r$NDK_MAJOR but --ndk requested r${NDK_MAJOR_PREFERENCE[0]}."
  fi
else
  for major in "${NDK_MAJOR_PREFERENCE[@]}"; do
    candidates=()
    for candidate in "$ANDROID_HOME"/ndk/"$major".* "$PREFIX"/opt/android-sdk/ndk/"$major".*; do
      [[ -f "$candidate/source.properties" ]] && candidates+=("$candidate")
    done
    for candidate in "${candidates[@]}"; do
      revision="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$candidate/source.properties" | head -n 1)"
      [[ "${revision%%.*}" == "$major" ]] || continue
      ANDROID_NDK_ROOT="$candidate"
      NDK_ACTUAL="$revision"
      break 2
    done
  done
  [[ -n "$ANDROID_NDK_ROOT" ]] || die "No supported ARM64 NDK r29 or r25 found under $ANDROID_HOME/ndk. Set ANDROID_NDK_ROOT or install one of these versions."
fi
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"

# The NDK must contain native ARM64 host tools. An x86_64-only NDK cannot
# execute directly inside Termux on an ARM64 phone.
NDK_HOST_TAG=""
for tag in linux-aarch64 linux-arm64; do
  if [[ -x "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$tag/bin/clang" ]]; then
    NDK_HOST_TAG="$tag"
    break
  fi
done
[[ -n "$NDK_HOST_TAG" ]] || die "NDK $NDK_ACTUAL has no Linux ARM64 host toolchain. This NDK cannot build natively in Termux; install an ARM64-host NDK and point ANDROID_NDK_ROOT to it."
export ANDROID_NDK_HOST_TAG="$NDK_HOST_TAG"
NDK_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
[[ -x "$NDK_BIN/aarch64-linux-android$ANDROID_API-clang" ]] || die "Missing ARM64 Android API $ANDROID_API compiler in $NDK_BIN"

# Validate the SDK components required by app/build.gradle.
[[ -s "$ANDROID_HOME/platforms/android-$COMPILE_SDK/android.jar" ]] || die "Missing Android platform android-$COMPILE_SDK under $ANDROID_HOME/platforms."
[[ -x "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt" ]] || die "Missing Android Build Tools $BUILD_TOOLS under $ANDROID_HOME/build-tools."
SDK_CMAKE="$ANDROID_HOME/cmake/$CMAKE_VERSION/bin/cmake"
[[ -x "$SDK_CMAKE" ]] || die "Missing Android SDK CMake $CMAKE_VERSION under $ANDROID_HOME/cmake."

# Google distributes the Android SDK host tools for x86_64. On ARM64 Termux,
# running one of those binaries can terminate the shell with signal 31 before
# Bash can report a normal error. Prefer Termux's native tools when present.
host_arch() {
  local description
  description="$(file -b "$1" 2>/dev/null || true)"
  case "$description" in
    *"ARM aarch64"*|*"ARM64"*) printf 'aarch64\n' ;;
    *"x86-64"*) printf 'x86_64\n' ;;
    *) printf 'unknown\n' ;;
  esac
}

if [[ "$(host_arch "$SDK_CMAKE")" == "aarch64" ]]; then
  CMAKE_EXECUTABLE="$SDK_CMAKE"
else
  need cmake
  CMAKE_EXECUTABLE="$(command -v cmake)"
fi

export ANDROID_API
export PATH="$NDK_BIN:$ANDROID_HOME/platform-tools:$PATH"
"$NDK_BIN/clang" --version >/dev/null
"$CMAKE_EXECUTABLE" --version >/dev/null
if [[ "$(host_arch "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt")" == "aarch64" ]]; then
  "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt" version >/dev/null
else
  need aapt
  aapt version >/dev/null
fi
if [[ "$(host_arch "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2")" == "aarch64" ]]; then
  "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2" version >/dev/null
else
  need aapt2
  aapt2 version >/dev/null
fi

if (( ! KEEP_WORK )); then
  rm -rf "$WORK_DIR"
else
  rm -f "$WORK_DIR/unitool-termux-cli/downloaded-packages.txt"
fi
mkdir -p "$WORK_DIR"
export RUNNER_TEMP="$WORK_DIR"

log "Build environment"
printf 'Repository: %s\nVariant: %s\nAndroid SDK: %s\nAndroid NDK: %s (%s)\nJava: %s\n' \
  "$ROOT" "$VARIANT" "$ANDROID_HOME" "$ANDROID_NDK_ROOT (r$NDK_ACTUAL)" "$NDK_HOST_TAG" "$(java -version 2>&1 | head -n 1)"

log "Stage official Termux CLI runtime assets"
bash scripts/prepare-termux-cli-runtime.sh

for command_name in git curl wget openssl less python python3; do
  [[ -s "app/src/main/assets/bootstrap/bin/$command_name" ]] || die "Runtime staging did not produce bootstrap/bin/$command_name"
done
[[ -s app/src/main/assets/termux-python-runtime.zip ]] || die "Termux Python standard-library archive is missing."
[[ -s app/src/main/assets/bootstrap/lib/libpython3.14.so ]] || die "Termux libpython3.14.so is missing."

log "Audit native libraries and staged commands"
STRICT_NATIVE_AUDIT=1 bash scripts/audit-native-libs.sh

# Gradle launches CMake and Ninja from the SDK package named by app/build.gradle.
# The Google package is x86_64-only, so provide an SDK overlay with Termux's
# native ARM64 executables while symlinking the rest of the installed SDK.
GRADLE_ANDROID_HOME="$ANDROID_HOME"
if [[ "$(host_arch "$SDK_CMAKE")" != "aarch64" ]]; then
  need ninja
  NATIVE_SDK="$PREFIX/tmp/unitool-termux-sdk"
  mkdir -p "$NATIVE_SDK/cmake/$CMAKE_VERSION/bin"
  for entry in "$ANDROID_HOME"/*; do
    name="${entry##*/}"
    [[ "$name" == "cmake" ]] || ln -sfn "$entry" "$NATIVE_SDK/$name"
  done
  for entry in "$ANDROID_HOME/cmake/$CMAKE_VERSION"/*; do
    name="${entry##*/}"
    [[ "$name" == "bin" ]] || ln -sfn "$entry" "$NATIVE_SDK/cmake/$CMAKE_VERSION/$name"
  done
  for entry in "$ANDROID_HOME/cmake/$CMAKE_VERSION/bin"/*; do
    name="${entry##*/}"
    [[ "$name" == "cmake" || "$name" == "ninja" ]] || ln -sfn "$entry" "$NATIVE_SDK/cmake/$CMAKE_VERSION/bin/$name"
  done
  ln -sfn "$CMAKE_EXECUTABLE" "$NATIVE_SDK/cmake/$CMAKE_VERSION/bin/cmake"
  ln -sfn "$(command -v ninja)" "$NATIVE_SDK/cmake/$CMAKE_VERSION/bin/ninja"

  LOCAL_PROPERTIES="$ROOT/local.properties"
  LOCAL_PROPERTIES_BACKUP="$NATIVE_SDK/local.properties.backup"
  if [[ -f "$LOCAL_PROPERTIES" ]]; then
    cp "$LOCAL_PROPERTIES" "$LOCAL_PROPERTIES_BACKUP"
  else
    : > "$LOCAL_PROPERTIES_BACKUP"
  fi
  restore_local_properties() {
    if [[ -s "$LOCAL_PROPERTIES_BACKUP" ]]; then
      cp "$LOCAL_PROPERTIES_BACKUP" "$LOCAL_PROPERTIES"
    else
      rm -f "$LOCAL_PROPERTIES"
    fi
  }
  trap restore_local_properties EXIT
  { [[ ! -f "$LOCAL_PROPERTIES" ]] || sed '/^sdk\.dir=/d' "$LOCAL_PROPERTIES"; } > "$NATIVE_SDK/local.properties.new"
  printf 'sdk.dir=%s\n' "$NATIVE_SDK" >> "$NATIVE_SDK/local.properties.new"
  cp "$NATIVE_SDK/local.properties.new" "$LOCAL_PROPERTIES"
  GRADLE_ANDROID_HOME="$NATIVE_SDK"
fi

log "Validate Gradle wrapper and Java runtime"
chmod +x ./gradlew
ANDROID_HOME="$GRADLE_ANDROID_HOME" ANDROID_SDK_ROOT="$GRADLE_ANDROID_HOME" ./gradlew --no-daemon --version

log "Build $VARIANT APK"
if [[ "$VARIANT" == "release" ]]; then
  ANDROID_HOME="$GRADLE_ANDROID_HOME" ANDROID_SDK_ROOT="$GRADLE_ANDROID_HOME" ./gradlew --no-daemon --stacktrace -PandroidNdkVersion="$NDK_ACTUAL" clean :app:assembleRelease
  APK_DIR="app/build/outputs/apk/release"
else
  ANDROID_HOME="$GRADLE_ANDROID_HOME" ANDROID_SDK_ROOT="$GRADLE_ANDROID_HOME" ./gradlew --no-daemon --stacktrace -PandroidNdkVersion="$NDK_ACTUAL" clean :app:assembleDebug
  APK_DIR="app/build/outputs/apk/debug"
fi

log "Verify APK ABI"
bash scripts/verify-arm64-apk.sh "$APK_DIR"
APK="$(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' -print -quit)"
[[ -s "$APK" ]] || die "Gradle completed but no APK was found in $APK_DIR."

log "Verify packaged Termux Python runtime"
unzip -Z1 "$APK" | grep -Fxq 'assets/termux-python-runtime.zip' || die "APK is missing assets/termux-python-runtime.zip."
unzip -p "$APK" assets/termux-python-runtime.zip > "$WORK_DIR/python-runtime-from-apk.zip"
python3 - "$WORK_DIR/python-runtime-from-apk.zip" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = set(archive.namelist())
    required_suffixes = ("/os.py", "/encodings/__init__.py")
    for suffix in required_suffixes:
        if not any(name.endswith(suffix) for name in names):
            raise SystemExit("Python runtime archive is missing " + suffix)
print("Python standard library archive verified.")
PY

log "Build complete"
sha256sum "$APK"
printf '\nAPK: %s\n' "$ROOT/$APK"
printf 'Size: %s bytes\n' "$(wc -c < "$APK")"
