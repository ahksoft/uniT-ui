#!/usr/bin/env bash
set -euo pipefail

# Stage official Termux ARM64 command-line packages, including Python and
# its dependency closure, into UniTool's private bootstrap assets.
# Package metadata and SHA-256 hashes are resolved from the official index.

BASE_URL="https://packages.termux.dev/apt/termux-main"
WORK_DIR="${RUNNER_TEMP:-$(mktemp -d)}/unitool-termux-cli"
PACKAGE_CACHE="${TERMUX_PACKAGE_CACHE:-}"
OFFLINE="${TERMUX_OFFLINE:-0}"
DOWNLOAD_DIR="$WORK_DIR/debs"
EXTRACT_ROOT="$WORK_DIR/root"
ASSET_PARENT="app/src/main/assets"
ASSET_ROOT="$ASSET_PARENT/bootstrap"
USR="$EXTRACT_ROOT/data/data/com.termux/files/usr"

deb_field() {
  local deb="$1" field="$2"
  dpkg-deb --ctrl-tarfile "$deb" |
    tar --no-same-owner --no-same-permissions -xOf - ./control |
    awk -v key="$field: " 'index($0, key) == 1 { print substr($0, length(key) + 1); exit }'
}

mkdir -p "$DOWNLOAD_DIR" "$EXTRACT_ROOT" "$ASSET_ROOT"
if [[ "${TERMUX_REFRESH_PACKAGE_INDEX:-0}" == 1 ]]; then
  curl --fail --location --silent --show-error --retry 4 --retry-delay 2 \
    "$BASE_URL/dists/stable/main/binary-aarch64/Packages" --output "$WORK_DIR/Packages"
elif [[ -n "$PACKAGE_CACHE" && ! -s "$WORK_DIR/Packages" ]]; then
  if [[ -s "$PACKAGE_CACHE/Packages" ]]; then
    cp "$PACKAGE_CACHE/Packages" "$WORK_DIR/Packages"
  elif [[ -s "$PACKAGE_CACHE/Packages.gz" ]]; then
    cp "$PACKAGE_CACHE/Packages.gz" "$WORK_DIR/Packages"
  fi
fi

download_package() {
  local name="$1" filename="$2" sha256="$3"
  local destination="$DOWNLOAD_DIR/$name.deb"
  if [[ -f "$WORK_DIR/downloaded-packages.txt" ]] && grep -Fxq "$name" "$WORK_DIR/downloaded-packages.txt"; then
    echo "Already staged package: $name"
    return 0
  fi
  echo "::group::Stage Termux package $name"
  if [[ -z "$sha256" || -z "$filename" ]]; then
    local package_index="$WORK_DIR/Packages"
    if [[ ! -s "$package_index" ]]; then
      [[ "$OFFLINE" != 1 ]] || { echo "Offline mode: missing cached Packages index for $name" >&2; exit 1; }
      curl --fail --location --silent --show-error --retry 4 --retry-delay 2 \
        "$BASE_URL/dists/stable/main/binary-aarch64/Packages" --output "$package_index"
    fi
    local resolved
    resolved="$(python3 - "$package_index" "$name" "$filename" <<'PY'
import gzip, sys
index_path, wanted_package, wanted_filename = sys.argv[1:]
with open(index_path, "rb") as raw:
    data = raw.read()
if data.startswith(b"\x1f\x8b"):
    data = gzip.decompress(data)
for stanza in data.decode("utf-8").split("\n\n"):
    fields = {}
    for line in stanza.splitlines():
        if line and not line[0].isspace() and ": " in line:
            key, value = line.split(": ", 1)
            fields[key] = value
    if fields.get("Package") != wanted_package:
        continue
    if fields.get("Architecture") not in ("aarch64", "all"):
        continue
    if wanted_filename and fields.get("Filename") != wanted_filename:
        continue
    print(fields.get("Filename", "") + "\t" + fields.get("SHA256", ""))
PY
)"
    IFS=$'\t' read -r resolved_filename resolved_sha256 <<< "$resolved"
    [[ -n "$filename" ]] || filename="$resolved_filename"
    [[ -n "$sha256" ]] || sha256="$resolved_sha256"
    [[ "$sha256" =~ ^[0-9a-f]{64}$ && -n "$filename" ]] || {
      echo "Could not resolve repository filename/SHA-256 for $name ($filename)" >&2
      exit 1
    }
  fi
  if [[ -n "$PACKAGE_CACHE" && -s "$PACKAGE_CACHE/debs/$name.deb" ]]; then
    cp "$PACKAGE_CACHE/debs/$name.deb" "$destination"
    if echo "$sha256  $destination" | sha256sum --check --status; then
      printf 'Package: %s\nVersion: %s\nArchitecture: %s\n' \
        "$(deb_field "$destination" Package)" "$(deb_field "$destination" Version)" "$(deb_field "$destination" Architecture)"
      printf '%s\n' "$name" >> "$WORK_DIR/downloaded-packages.txt"
      echo "Using cached package: $PACKAGE_CACHE/debs/$name.deb"
      echo "::endgroup::"
      return 0
    fi
    if [[ "${TERMUX_SKIP_CACHED_CHECKSUM:-0}" == 1 ]]; then
      local cached_package cached_arch
      cached_package="$(deb_field "$destination" Package)"
      cached_arch="$(deb_field "$destination" Architecture)"
      if [[ "$cached_package" == "$name" && ( "$cached_arch" == aarch64 || "$cached_arch" == all ) ]]; then
        echo "WARNING: accepting cached $name with SHA-256 mismatch by request." >&2
        printf 'Package: %s\nVersion: %s\nArchitecture: %s\n' \
          "$cached_package" "$(deb_field "$destination" Version)" "$cached_arch"
        printf '%s\n' "$name" >> "$WORK_DIR/downloaded-packages.txt"
        echo "Using cached package: $PACKAGE_CACHE/debs/$name.deb"
        echo "::endgroup::"
        return 0
      fi
      echo "Cached package identity/architecture mismatch for $name ($cached_package, $cached_arch)" >&2
      exit 1
    fi
    rm -f "$destination"
    echo "Cached package checksum failed for $name" >&2
    exit 1
  fi
  [[ "$OFFLINE" != 1 ]] || { echo "Offline mode: cached package missing: $PACKAGE_CACHE/debs/$name.deb" >&2; exit 1; }
  local downloaded=0
  local repository
  for repository in "$BASE_URL" "https://packages-cf.termux.dev/apt/termux-main"; do
    if curl --fail --location --silent --show-error --retry 2 --retry-delay 2 \
      "$repository/$filename" --output "$destination"; then
      if echo "$sha256  $destination" | sha256sum --check --status; then
        downloaded=1
        break
      fi
      echo "SHA-256 verification failed from $repository for $name; trying next mirror" >&2
      rm -f "$destination"
    fi
  done
  [[ "$downloaded" -eq 1 ]] || {
    echo "Could not download verified Termux package $name from configured repositories: $filename" >&2
    exit 1
  }
  printf 'Package: %s\nVersion: %s\nArchitecture: %s\n' \
    "$(deb_field "$destination" Package)" "$(deb_field "$destination" Version)" "$(deb_field "$destination" Architecture)"
  printf '%s\n' "$name" >> "$WORK_DIR/downloaded-packages.txt"
  echo "::endgroup::"
}

# Direct runtime packages plus their complete dependency closure, based on
# Depends fields in the supplied Termux Packages index.
download_package git "pool/main/g/git/git_2.56.0_aarch64.deb" "dcdd22c5fdf7bdb5b825a03a17441d43858ed8515fea57fbb37eb42e733ca82f"
download_package wget "pool/main/w/wget/wget_1.25.0-1_aarch64.deb" "f2631519e0b9add766a82b6fa9b294476bddb6cad9033c214c7575b5822a8bc4"
download_package curl "pool/main/c/curl/curl_8.23.0_aarch64.deb" "e57a9975a828eb48790bec0da950795af165aa714f6a33d266c45a4e2898aa85"
download_package libcurl "pool/main/libc/libcurl/libcurl_8.23.0_aarch64.deb" "2011bb9709261bb3d5ecaa6672faa333be14cf48ad4af37031e3e365309557ac"
download_package openssl "pool/main/o/openssl/openssl_1%3A3.6.5_aarch64.deb" "a2fb7856875e63ef6b90442752e05974813372c3ca86d2513a78ea43c67a0f8b"
download_package openssl-tool "" ""
download_package libcrypt "" ""
download_package pcre2 "pool/main/p/pcre2/pcre2_10.49_aarch64.deb" "c27995e5f52b8ecc9b8b3e662eb08e49f82a18bfd3f34343936acc7b23964724"
download_package zlib "pool/main/z/zlib/zlib_1.4_aarch64.deb" "e3027565ae50bcd8ba5113c988b589754ae192c9cc3e6afbe3e4ed7c06b2598e"
download_package libiconv "pool/main/libi/libiconv/libiconv_1.19.1_aarch64.deb" "a1c6323eff4f660e16ae6f5832356444b3269c19a4f61146355a49e7c4be5ed1"
download_package libidn2 "pool/main/libi/libidn2/libidn2_2.3.8-1_aarch64.deb" "a450a1ba25759ebf78738484a3efee316c51a1fe7bafb0b01a68e2c058a91020"
download_package libunistring "pool/main/libu/libunistring/libunistring_1.5_aarch64.deb" "8853ca1421d6472046a0683d53084b26db1caa50a3aa2bf6422a1276c7dba69b"
download_package libuuid "pool/main/libu/libuuid/libuuid_2.42.4_aarch64.deb" "ecbaab0afd7f4c2c84d687c8e4ba46794d71001866efc261a7015f663eec4e85"
download_package libandroid-support "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb" "f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"
download_package libexpat "pool/main/libe/libexpat/libexpat_2.10.0_aarch64.deb" "0e4411f01f6fb664f3f04f9420289472f9afc30db01ea1c5d1e57fd9fa2354be"
download_package less "pool/main/l/less/less_710_aarch64.deb" "bff261b48553bcde3a93e0d7c2a51dbb1b7e5025767a1ee9a1cb4459a3789499"
download_package libnghttp2 "pool/main/libn/libnghttp2/libnghttp2_1.71.0_aarch64.deb" "ae33e7b21e0636be8686b9b13a20e31267f047f69cb26b4befd70c9a9bae221e"
download_package libnghttp3 "pool/main/libn/libnghttp3/libnghttp3_1.19.0_aarch64.deb" "8bb6fa31f9281cd48047ec9a571cadf153538f5b38f0203ad7a4e221344b14a6"
download_package libngtcp2 "pool/main/libn/libngtcp2/libngtcp2_1.26.0_aarch64.deb" "8225ecde58104ef5aa234fa54a1bea5051a8a9ef2937e5ba996b3e8dda68c8eb"
download_package libssh2 "pool/main/libs/libssh2/libssh2_1.11.1-2_aarch64.deb" "1add4e0a926b848814e7e2f1817ea28b123c54cabd5bbc4e5cfd65291cbad84e"
# Resolve the current architecture-independent package filename and hash from
# the live signed-repository index instead of pinning a date-stamped URL.
download_package ca-certificates "" ""
download_package ncurses "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb" "f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99"
download_package libandroid-posix-semaphore "pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb" "0efa8677a0166315ba4e685863712eba0ca0a1732827492f38226e2723730c7a"

# Install the official Termux Python package and recursively resolve its
# runtime dependency closure from the same repository Packages index.
download_package python "" ""
python3 - "$WORK_DIR/Packages" <<'PY' > "$WORK_DIR/python-dependencies.txt"
import gzip
import re
import sys

index_path = sys.argv[1]
packages = {}
with open(index_path, "rb") as raw:
    data = raw.read()
if data.startswith(b"\x1f\x8b"):
    data = gzip.decompress(data)
for stanza in re.split(r"\n\s*\n", data.decode("utf-8")):
    fields = {}
    current = None
    for line in stanza.splitlines():
        if line[:1].isspace() and current:
            fields[current] += " " + line.strip()
        elif ": " in line:
            current, value = line.split(": ", 1)
            fields[current] = value
    name = fields.get("Package")
    arch = fields.get("Architecture")
    if name and arch in ("aarch64", "all"):
        old = packages.get(name)
        if old is None or (arch == "aarch64" and old.get("Architecture") == "all"):
            packages[name] = fields

visited = set()
ordered = []

def visit(name):
    if name in visited:
        return
    if name not in packages:
        raise SystemExit(f"Required Termux package is absent from Packages index: {name}")
    visited.add(name)
    fields = packages[name]
    for field in ("Pre-Depends", "Depends"):
        value = fields.get(field, "")
        for group in value.split(","):
            selected = None
            for alternative in group.split("|"):
                match = re.match(r"\s*([a-z0-9][a-z0-9+.-]*)", alternative.strip())
                if match and match.group(1) in packages:
                    selected = match.group(1)
                    break
            if selected:
                visit(selected)
            elif group.strip():
                print(f"Warning: unresolved optional/virtual dependency group: {group.strip()}", file=sys.stderr)
    ordered.append(name)

for root in ("python", "python-pip", "python-pycryptodomex"):
    visit(root)
for name in ordered:
    if name != "python":
        print(name)
PY
while IFS= read -r dependency; do
  [[ -n "$dependency" ]] && download_package "$dependency" "" ""
done < "$WORK_DIR/python-dependencies.txt"

# Stage APT, dpkg, the Termux signing keyring, and their complete dependency closure.
download_package apt "" ""
download_package libandroid-glob "" ""
download_package dpkg "" ""
download_package termux-keyring "" ""
python3 - "$WORK_DIR/Packages" <<'PY' > "$WORK_DIR/package-manager-dependencies.txt"
import gzip, re, sys
packages = {}
with open(sys.argv[1], "rb") as raw:
    data = raw.read()
if data.startswith(b"\x1f\x8b"):
    data = gzip.decompress(data)
for stanza in re.split(r"\n\s*\n", data.decode("utf-8")):
    fields, current = {}, None
    for line in stanza.splitlines():
        if line[:1].isspace() and current: fields[current] += " " + line.strip()
        elif ": " in line:
            current, value = line.split(": ", 1); fields[current] = value
    name, arch = fields.get("Package"), fields.get("Architecture")
    if name and arch in ("aarch64", "all"):
        old = packages.get(name)
        if old is None or (arch == "aarch64" and old.get("Architecture") == "all"): packages[name] = fields
visited, ordered = set(), []
def visit(name):
    if name in visited: return
    if name not in packages: raise SystemExit("Missing package-manager dependency: " + name)
    visited.add(name); fields = packages[name]
    for field in ("Pre-Depends", "Depends"):
        for group in fields.get(field, "").split(","):
            selected = None
            for alternative in group.split("|"):
                match = re.match(r"\s*([a-z0-9][a-z0-9+.-]*)", alternative.strip())
                if match and match.group(1) in packages: selected = match.group(1); break
            if selected: visit(selected)
            elif group.strip(): print("Unresolved dependency group: " + group.strip(), file=sys.stderr)
    ordered.append(name)
for root in ("apt", "dpkg", "termux-keyring"): visit(root)
for name in ordered:
    if name not in ("apt", "dpkg", "termux-keyring"): print(name)
PY
while IFS= read -r dependency; do
  [[ -n "$dependency" ]] && download_package "$dependency" "" ""
done < "$WORK_DIR/package-manager-dependencies.txt"

echo "Extracting verified Termux packages..."
for deb in "$DOWNLOAD_DIR"/*.deb; do
  package_name="$(basename "$deb")"
  echo "::group::Extract $package_name"
  # Extract package payloads without restoring Android builder ownership or
  # restrictive package modes, which Linux build sandboxes cannot apply.
  if ! dpkg-deb --fsys-tarfile "$deb" | tar --no-same-owner --no-same-permissions -xf - -C "$EXTRACT_ROOT"; then
    echo "Failed to extract package: $package_name" >&2
    echo "Package metadata:" >&2
    dpkg-deb --info "$deb" >&2 || true
    echo "Archive data listing (first 80 entries):" >&2
    dpkg-deb --contents "$deb" 2>&1 | head -n 80 >&2 || true
    exit 1
  fi
  echo "Extracted $package_name"
  echo "::endgroup::"
done

echo "Extract root: $EXTRACT_ROOT"
echo "Expected Termux prefix: $USR"
echo "Extracted directory tree:"
find "$EXTRACT_ROOT" -maxdepth 8 -type d -print | sort | head -n 120
echo "Enabling shell trace for staging diagnostics."
set -x

if [[ ! -d "$USR/bin" || ! -d "$USR/lib" ]]; then
  echo "Unexpected Termux package layout under $USR" >&2
  exit 1
fi

mkdir -p "$ASSET_ROOT/bin" "$ASSET_ROOT/lib" "$ASSET_ROOT/libexec/git-core" \
  "$ASSET_ROOT/share/git-core" "$ASSET_ROOT/etc"

# APK assets cannot represent Unix symlinks. Copy each real Git executable
# once and record package symlinks in a manifest; NativeToolchain recreates
# those links inside the app-private PREFIX after extracting the assets.
python3 - "$USR" "$ASSET_ROOT" <<'PY'
import os
import shutil
import sys

usr = os.path.realpath(sys.argv[1])
assets = sys.argv[2]
links = []

TERMUX_PREFIX = "/data/data/com.termux/files/usr"
def resolve_staged_link(source):
    current = source
    for _ in range(32):
        if not os.path.islink(current):
            break
        raw = os.readlink(current)
        if os.path.isabs(raw):
            if raw == TERMUX_PREFIX or raw.startswith(TERMUX_PREFIX + os.sep):
                suffix = os.path.relpath(raw, TERMUX_PREFIX)
                current = os.path.normpath(os.path.join(usr, suffix))
            else:
                return None
        else:
            current = os.path.normpath(os.path.join(os.path.dirname(current), raw))
        if os.path.commonpath((usr, current)) != usr:
            return None
    if os.path.commonpath((usr, current)) != usr or not os.path.isfile(current):
        return None
    return current

def stage_entry(source, relative):
    destination = os.path.join(assets, relative)
    if os.path.islink(source):
        resolved = resolve_staged_link(source)
        if resolved is None:
            print(f"Skipping broken/out-of-prefix Termux symlink: {relative} -> {os.readlink(source)}", file=sys.stderr)
            return
        target_relative = os.path.relpath(resolved, usr)
        links.append((relative, target_relative))
        # A command alias may point outside bin (for example into libexec).
        # Stage its resolved target too, so the installed symlink never dangles.
        if target_relative != relative:
            stage_entry(resolved, target_relative)
        return
    if os.path.isdir(source):
        os.makedirs(destination, exist_ok=True)
        for child in sorted(os.scandir(source), key=lambda item: item.name):
            stage_entry(child.path, os.path.join(relative, child.name))
        return
    if os.path.isfile(source):
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        shutil.copy2(source, destination)

# Stage the complete command set from the verified package dependency closure.
# This includes APT helpers plus coreutils/findutils/grep/sed required by apt-key.
bin_dir = os.path.join(usr, "bin")
for entry in sorted(os.scandir(bin_dir), key=lambda item: item.name):
    stage_entry(entry.path, os.path.relpath(entry.path, usr))

git_core = os.path.join(usr, "libexec", "git-core")
if os.path.isdir(git_core):
    stage_entry(git_core, "libexec/git-core")

manifest = os.path.join(assets, "package_symlinks.tsv")
with open(manifest, "w", encoding="utf-8", newline="\n") as output:
    for link, target in sorted(set(links)):
        output.write(f"{link}\t{target}\n")
print(f"Staged Git and package-manager payload with {len(links)} symlinks preserved in {manifest}")
if not links:
    raise SystemExit("Expected Termux Git symlinks, but none were found")
PY

for name in curl wget openssl less; do
  source="$USR/bin/$name"
  [[ -e "$source" ]] && cp -L "$source" "$ASSET_ROOT/bin/$name"
done

# Stage the actual Termux Python executable(s) and its package-provided
# standard library. Python's stdlib is zipped as one asset because Android's
# asset merger can omit underscore-prefixed package directories.
for name in python python3; do
  source="$USR/bin/$name"
  if [[ -e "$source" ]]; then
    cp -L "$source" "$ASSET_ROOT/bin/$name"
  fi
done
for source in "$USR"/bin/python3.[0-9]*; do
  [[ -e "$source" ]] || continue
  name="$(basename "$source")"
  [[ "$name" == *-config ]] && continue
  cp -L "$source" "$ASSET_ROOT/bin/$name"
done
if [[ ! -s "$ASSET_ROOT/bin/python" ]]; then
  fallback="$ASSET_ROOT/bin/python3"
  [[ -s "$fallback" ]] || fallback="$(find "$ASSET_ROOT/bin" -maxdepth 1 -type f -name 'python3.*' | sort -V | tail -n 1)"
  [[ -n "$fallback" && -s "$fallback" ]] || { echo "Termux Python executable was not staged" >&2; exit 1; }
  cp -f "$fallback" "$ASSET_ROOT/bin/python"
fi
if [[ ! -s "$ASSET_ROOT/bin/python3" ]]; then
  cp -f "$ASSET_ROOT/bin/python" "$ASSET_ROOT/bin/python3"
fi

PYTHON_STDLIB="$(find "$USR/lib" -mindepth 1 -maxdepth 1 -type d -name 'python3.*' | sort -V | tail -n 1)"
[[ -n "$PYTHON_STDLIB" && -f "$PYTHON_STDLIB/os.py" ]] || {
  echo "Termux Python standard library was not found under $USR/lib" >&2
  exit 1
}
# Bundle pure-Python wheels and the Termux-native PyCryptodome implementation.
# Wheel hashes are read from PyPI JSON and verified before extraction.
PYTHON_SITE_PACKAGES="$PYTHON_STDLIB/site-packages"
mkdir -p "$PYTHON_SITE_PACKAGES"
python3 - "$WORK_DIR" "$PYTHON_SITE_PACKAGES" <<'PY'
import hashlib, json, os, pathlib, shutil, sys, urllib.request, zipfile
work = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
cache = pathlib.Path(os.environ.get("TERMUX_PACKAGE_CACHE", ""))
packages = {
    "pyusb": ("1.3.1", "pyusb-1.3.1-py3-none-any.whl"),
    "pyserial": ("3.5", "pyserial-3.5-py2.py3-none-any.whl"),
    "colorama": ("0.4.6", "colorama-0.4.6-py2.py3-none-any.whl"),
}
for name, (version, filename) in packages.items():
    archive = work / filename
    cached_wheel = cache / filename
    expected_digest = None
    if cached_wheel.is_file():
        shutil.copy2(cached_wheel, archive)
        if os.environ.get("TERMUX_SKIP_CACHED_CHECKSUM") != "1":
            metadata_url = f"https://pypi.org/pypi/{name}/{version}/json"
            request = urllib.request.Request(metadata_url, headers={"User-Agent": "UniTool-Termux-Runtime/1.0"})
            with urllib.request.urlopen(request, timeout=45) as response:
                metadata = json.load(response)
            match = next((item for item in metadata["urls"] if item["filename"] == filename), None)
            if match is None:
                raise SystemExit(f"Wheel not found on PyPI: {filename}")
            expected_digest = match["digests"]["sha256"]
        else:
            print(f"WARNING: accepting cached wheel without checksum match by request: {filename}", file=sys.stderr)
    else:
        metadata_url = f"https://pypi.org/pypi/{name}/{version}/json"
        request = urllib.request.Request(metadata_url, headers={"User-Agent": "UniTool-Termux-Runtime/1.0"})
        with urllib.request.urlopen(request, timeout=45) as response:
            metadata = json.load(response)
        wheel = next((item for item in metadata["urls"] if item["filename"] == filename), None)
        if wheel is None:
            raise SystemExit(f"Wheel not found on PyPI: {filename}")
        request = urllib.request.Request(wheel["url"], headers={"User-Agent": "UniTool-Termux-Runtime/1.0"})
        with urllib.request.urlopen(request, timeout=90) as response, archive.open("wb") as output:
            output.write(response.read())
        expected_digest = wheel["digests"]["sha256"]
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if expected_digest is not None and digest != expected_digest:
        raise SystemExit(f"SHA-256 mismatch for {filename}")
    with zipfile.ZipFile(archive) as package:
        for item in package.infolist():
            target = (destination / item.filename).resolve()
            if destination.resolve() not in target.parents and target != destination.resolve():
                raise SystemExit(f"Unsafe wheel path in {wheel['filename']}: {item.filename}")
        package.extractall(destination)
    print(f"Installed verified pure-Python wheel: {name}=={version}")
PY
[[ -d "$PYTHON_SITE_PACKAGES/Cryptodome" ]] || {
  echo "Termux python-pycryptodomex did not stage Cryptodome modules" >&2
  exit 1
}
for module in usb serial colorama pip; do
  [[ -d "$PYTHON_SITE_PACKAGES/$module" ]] || {
    echo "Missing bundled Python module: $module" >&2
    exit 1
  }
done

find "$PYTHON_STDLIB" -type f -name '*.so' -exec patchelf --remove-rpath {} +
python3 - "$PYTHON_STDLIB" "$ASSET_PARENT/termux-python-runtime.zip" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as output:
    for path in sorted(source.rglob("*")):
        if path.is_file():
            output.write(path, (source.name + "/" + path.relative_to(source).as_posix()))
with zipfile.ZipFile(archive) as check:
    names = set(check.namelist())
    required = (source.name + "/os.py", source.name + "/encodings/__init__.py")
    missing = [name for name in required if name not in names]
    if missing:
        raise SystemExit("Termux Python archive missing: " + ", ".join(missing))
print(f"Archived Termux {source.name} standard library: {archive.stat().st_size} bytes")
PY


if [[ -d "$USR/share/git-core" ]]; then
  cp -aL "$USR/share/git-core/." "$ASSET_ROOT/share/git-core/"
fi
# APT transport methods are executable helpers, not shared libraries.
for helperdir in "$USR/lib/apt" "$USR/libexec/apt"; do
  if [[ -d "$helperdir" ]]; then
    relative="${helperdir#"$USR/"}"
    mkdir -p "$ASSET_ROOT/$relative"
    cp -aL "$helperdir/." "$ASSET_ROOT/$relative/"
  fi
done
for sharedir in apt dpkg keyrings gnupg termux-keyring termux-licenses; do
  if [[ -d "$USR/share/$sharedir" ]]; then
    mkdir -p "$ASSET_ROOT/share/$sharedir"
    cp -aL "$USR/share/$sharedir/." "$ASSET_ROOT/share/$sharedir/"
  fi
done
if [[ -d "$USR/etc" ]]; then
  mkdir -p "$ASSET_ROOT/etc"
  chmod -R u+rwX "$ASSET_ROOT/etc" 2>/dev/null || true
  python3 - "$USR" "$USR/etc" "$ASSET_ROOT/etc" <<'PY'
import os, pathlib, shutil, sys
usr = pathlib.Path(sys.argv[1]).resolve()
source = pathlib.Path(sys.argv[2])
destination = pathlib.Path(sys.argv[3])
prefix = pathlib.Path("/data/data/com.termux/files/usr")

def resolve(path):
    current = path
    for _ in range(32):
        if not current.is_symlink():
            break
        raw = os.readlink(current)
        if os.path.isabs(raw):
            raw_path = pathlib.Path(raw)
            if raw_path == prefix or prefix in raw_path.parents:
                current = usr / raw_path.relative_to(prefix)
            else:
                return None
        else:
            current = current.parent / raw
    current = current.resolve()
    return current if current == usr or usr in current.parents else None

for root, dirs, files in os.walk(source, followlinks=False):
    relative = pathlib.Path(root).relative_to(source)
    target_dir = destination / relative
    target_dir.mkdir(parents=True, exist_ok=True)
    for name in list(dirs):
        candidate = pathlib.Path(root) / name
        if candidate.is_symlink():
            resolved = resolve(candidate)
            dirs.remove(name)
            if resolved and resolved.is_dir():
                shutil.copytree(resolved, target_dir / name, dirs_exist_ok=True, symlinks=False)
    for name in files:
        candidate = pathlib.Path(root) / name
        # Preserve the checked-in host resolver configuration; Termux packages
        # carry placeholder files owned by the Android package builder.
        if candidate.parent == source and name in ("hosts", "resolv.conf"):
            continue
        resolved = resolve(candidate) if candidate.is_symlink() else candidate
        if resolved is not None and resolved.is_file():
            shutil.copy2(resolved, target_dir / name)
        else:
            print(f"Skipping unresolved Termux config link: {candidate}", file=sys.stderr)
PY
fi

# Ship the shared-library closure only, resolving absolute Termux-prefix symlinks
# against the extracted package root rather than the build device host prefix.
python3 - "$USR" "$ASSET_ROOT/lib" <<'PY'
import os, pathlib, shutil, sys
usr = os.path.abspath(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
termux_prefix = "/data/data/com.termux/files/usr"
def resolve_link(path):
    current = path
    for _ in range(32):
        if not os.path.islink(current): break
        raw = os.readlink(current)
        if os.path.isabs(raw):
            if raw == termux_prefix or raw.startswith(termux_prefix + os.sep):
                current = os.path.normpath(os.path.join(usr, os.path.relpath(raw, termux_prefix)))
            else: return None
        else: current = os.path.normpath(os.path.join(os.path.dirname(current), raw))
        if os.path.commonpath((usr, current)) != usr: return None
    if os.path.commonpath((usr, current)) != usr or not os.path.isfile(current): return None
    return current
count = 0
for source in sorted(pathlib.Path(usr, "lib").rglob("*")):
    if not (source.name.endswith(".so") or ".so." in source.name): continue
    source_path = str(source)
    actual = resolve_link(source_path) if source.is_symlink() else source_path
    if actual is None:
        print(f"Skipping broken/out-of-prefix library symlink: {source_path}", file=sys.stderr)
        continue
    if not os.path.isfile(actual): continue
    relative = source.relative_to(pathlib.Path(usr, "lib"))
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(actual, target)
    count += 1
print(f"Staged {count} Termux shared-library files.")
PY
# Termux binaries embed a Termux-specific RUNPATH. Remove it and let the
# app's shell environment resolve all package libraries through $PREFIX/lib.
while IFS= read -r -d '' elf; do
  if readelf -h "$elf" >/dev/null 2>&1; then
    patchelf --remove-rpath "$elf"
  fi
done < <(find "$ASSET_ROOT/bin" "$ASSET_ROOT/lib" "$ASSET_ROOT/libexec" -type f -print0)

# Confirm the primary commands and critical dynamic libraries were staged.
for command in git curl wget openssl less python python3 pip pip3 apt apt-get apt-cache apt-config dpkg dpkg-deb dpkg-query; do
  [[ -s "$ASSET_ROOT/bin/$command" ]] || {
    echo "Missing staged command: $command" >&2
    exit 1
  }
done
for library in libcurl.so libcrypto.so.3 libssl.so.3 libpcre2-8.so libiconv.so libidn2.so libunistring.so libuuid.so libandroid-support.so libandroid-posix-semaphore.so libandroid-glob.so libexpat.so libnghttp2.so libnghttp3.so libngtcp2.so libssh2.so libz.so.1; do
  [[ -s "$ASSET_ROOT/lib/$library" ]] || {
    echo "Missing staged library: $library" >&2
    exit 1
  }
done

# Check every DT_NEEDED entry in the staged package executables and libraries.
# Only Android platform libraries may be resolved outside the private prefix.
while IFS= read -r -d '' elf; do
  # These directories also contain text/configuration files. Only inspect ELF
  # objects; readelf -d returns non-zero for ordinary files under set -e.
  readelf -h "$elf" >/dev/null 2>&1 || continue
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    [[ -s "$ASSET_ROOT/lib/$needed" ]] && continue
    case "$needed" in
      libc.so|libdl.so|libm.so|liblog.so|libandroid.so|libpthread.so|libresolv.so)
        ;;
      *)
        echo "Unresolved dependency: $needed (required by $elf)" >&2
        exit 1
        ;;
    esac
  done < <(readelf -d "$elf" 2>/dev/null | awk -F'[][]' '/Shared library: / { print $2 }')
done < <(find "$ASSET_ROOT/bin" "$ASSET_ROOT/lib" "$ASSET_ROOT/libexec" -type f -print0)

[[ -s "$ASSET_ROOT/bin/pkg" ]] || { echo "Missing UniTool pkg wrapper" >&2; exit 1; }
for method in http https; do
  [[ -x "$ASSET_ROOT/lib/apt/methods/$method" ]] || {
    echo "Missing APT transport method: $method" >&2
    exit 1
  }
done
echo "Official Termux CLI and APT/dpkg runtime staged successfully."
du -sh "$ASSET_ROOT/bin" "$ASSET_ROOT/lib" "$ASSET_ROOT/libexec"
