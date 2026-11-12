#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-}/ndk/25.2.9519653}"
API="${ANDROID_API:-24}"
HOST_TAG="${ANDROID_NDK_HOST_TAG:-linux-x86_64}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
WORK="${WORK_DIR:-$ROOT/build/cli-tools}"
STAGE="$WORK/stage"
JNI="$ROOT/app/src/main/jniLibs/arm64-v8a"

test -x "$TOOLCHAIN/bin/aarch64-linux-android${API}-clang" || { echo "NDK toolchain not found: $TOOLCHAIN" >&2; exit 2; }
for tool in git make perl python3; do command -v "$tool" >/dev/null || { echo "Missing host tool: $tool" >&2; exit 2; }; done
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CXX="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" STRIP="$TOOLCHAIN/bin/llvm-strip"
export AS="$CC" CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC" LDFLAGS="-Wl,--build-id=sha1"
export PATH="$TOOLCHAIN/bin:$PATH"
mkdir -p "$WORK/src" "$JNI"
rm -rf "$STAGE"; mkdir -p "$STAGE/bin" "$STAGE/lib"

clone_tag() {
  local name="$1" url="$2" tag="$3"
  local dir="$WORK/src/$name"
  if [[ ! -d "$dir/.git" ]]; then git clone --filter=blob:none --no-checkout "$url" "$dir"; fi
  git -C "$dir" fetch --depth 1 origin "refs/tags/$tag:refs/tags/$tag"
  git -C "$dir" checkout --force "$tag"
  git -C "$dir" clean -ffd
  printf '%s\n' "$dir"
}

OPENSSL_SRC="$(clone_tag openssl https://github.com/openssl/openssl.git openssl-3.5.9)"
(
 cd "$OPENSSL_SRC"
 ./Configure android-arm64 -D__ANDROID_API__="$API" shared no-tests --prefix="$STAGE" --openssldir="/data/data/ahk.unitool.gsm/files/usr/etc/tls"
 make -j"$(getconf _NPROCESSORS_ONLN)"
 make install_sw
)
cp -a "$STAGE/lib/"*.so* "$JNI/" 2>/dev/null || true
test -x "$STAGE/bin/openssl"; cp "$STAGE/bin/openssl" "$JNI/libopenssl.so"

CURL_SRC="$(clone_tag curl https://github.com/curl/curl.git curl-8_17_0)"
(
 cd "$CURL_SRC"
 autoreconf -fi
 ./configure --host=aarch64-linux-android --prefix="$STAGE" --with-openssl="$STAGE" \
  --disable-ldap --disable-ldaps --disable-rtsp --disable-dict --disable-telnet \
  --disable-tftp --disable-pop3 --disable-imap --disable-smtp --disable-gopher \
  --disable-mqtt --disable-manual --disable-docs --disable-shared --enable-static --enable-ipv6 \
  --without-libpsl
 make -j"$(getconf _NPROCESSORS_ONLN)"; make install
)
test -x "$STAGE/bin/curl"; cp "$STAGE/bin/curl" "$JNI/libcurl.so"

# Build PCRE2 from the official Termux package recipe dependency.
# Wget and Git need the ARM64 target headers/library, not the Ubuntu host PCRE2.
PCRE2_VERSION="10.49"
PCRE2_SHA256="53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384"
PCRE2_ARCHIVE="$WORK/src/pcre2-$PCRE2_VERSION.tar.bz2"
PCRE2_SRC="$WORK/src/pcre2-$PCRE2_VERSION"
curl --fail --location --retry 5 --retry-all-errors --connect-timeout 30 \
  "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE2_VERSION/pcre2-$PCRE2_VERSION.tar.bz2" -o "$PCRE2_ARCHIVE"
echo "$PCRE2_SHA256  $PCRE2_ARCHIVE" | sha256sum --check -
rm -rf "$PCRE2_SRC"
mkdir -p "$PCRE2_SRC"
tar -xf "$PCRE2_ARCHIVE" --strip-components=1 -C "$PCRE2_SRC"
(
 cd "$PCRE2_SRC"
 ./configure --host=aarch64-linux-android --prefix="$STAGE" --enable-jit \
  --enable-pcre2-16 --enable-pcre2-32 --disable-pcre2grep-libz \
  --disable-pcre2grep-libbz2 --disable-pcre2grep-libreadline
 make -j"$(getconf _NPROCESSORS_ONLN)"
 make install
)
cp -a "$STAGE/lib/libpcre2-8.so"* "$JNI/"

# Follow the official Termux wget recipe source URL and checksum.
# Avoid the Savannah Git endpoint, which has returned HTTP 500 / malformed redirects in CI.
WGET_VERSION="1.25.0"
WGET_SHA256="19225cc756b0a088fc81148dc6a40a0c8f329af7fd8483f1c7b2fe50f4e08a1f"
WGET_ARCHIVE="$WORK/src/wget-$WGET_VERSION.tar.lz"
WGET_SRC="$WORK/src/wget-$WGET_VERSION"
curl --fail --location --retry 5 --retry-all-errors --connect-timeout 30 \
  "https://mirrors.kernel.org/gnu/wget/wget-$WGET_VERSION.tar.lz" -o "$WGET_ARCHIVE"
echo "$WGET_SHA256  $WGET_ARCHIVE" | sha256sum --check -
rm -rf "$WGET_SRC"
mkdir -p "$WGET_SRC"
tar --lzip -xf "$WGET_ARCHIVE" --strip-components=1 -C "$WGET_SRC"
(
 cd "$WGET_SRC"
 PKG_CONFIG_PATH="$STAGE/lib/pkgconfig" CPPFLAGS="-I$STAGE/include" \
  LDFLAGS="-L$STAGE/lib -Wl,--build-id=sha1" \
  ./configure --host=aarch64-linux-android --prefix="$STAGE" --with-ssl=openssl \
   --with-libssl-prefix="$STAGE" --disable-nls --disable-iri --disable-rpath
 make -j"$(getconf _NPROCESSORS_ONLN)"; make install
)
test -x "$STAGE/bin/wget"; cp "$STAGE/bin/wget" "$JNI/libwget.so"

GIT_SRC="$(clone_tag git https://github.com/git/git.git v2.52.0)"
(
 cd "$GIT_SRC"
 make configure
 # Match the cross-build cache answers used by the official Termux Git recipe.
 # These prevent configure from trying to execute ARM64 probes on the x86_64 host.
 ac_cv_fread_reads_directories=yes \
 ac_cv_header_libintl_h=no \
 ac_cv_iconv_omits_bom=no \
 ac_cv_snprintf_returns_bogus=no \
 CPPFLAGS="-I$STAGE/include" \
 LDFLAGS="-L$STAGE/lib -Wl,--build-id=sha1" \
 PKG_CONFIG_PATH="$STAGE/lib/pkgconfig" \
 ./configure --host=aarch64-linux-android --prefix="$STAGE" \
  --with-openssl --with-curl --without-tcltk --without-python
 make -j"$(getconf _NPROCESSORS_ONLN)" \
  NO_GETTEXT=YesPlease NO_TCLTK=YesPlease NO_PERL=YesPlease \
  NO_PYTHON=YesPlease NO_EXPAT=YesPlease NO_INSTALL_HARDLINKS=YesPlease \
  NO_PTHREADS=1 CSPRNG_METHOD=openssl USE_LIBPCRE2=YesPlease
 make install \
  NO_GETTEXT=YesPlease NO_TCLTK=YesPlease NO_PERL=YesPlease \
  NO_PYTHON=YesPlease NO_EXPAT=YesPlease NO_INSTALL_HARDLINKS=YesPlease \
  NO_PTHREADS=1 CSPRNG_METHOD=openssl USE_LIBPCRE2=YesPlease
)
test -x "$STAGE/bin/git"; cp "$STAGE/bin/git" "$JNI/libgit.so"

for pair in "openssl:libopenssl.so" "curl:libcurl.so" "wget:libwget.so" "git:libgit.so"; do
 name="${pair%%:*}"; file="${pair##*:}"
 test -s "$JNI/$file"
 "$TOOLCHAIN/bin/llvm-readelf" -h "$JNI/$file" | grep -q AArch64
 "$TOOLCHAIN/bin/llvm-readelf" -l "$JNI/$file" | grep -q LOAD
 echo "Built Android ARM64 $name: $JNI/$file"
done
echo "Build complete; inspect DT_NEEDED and device-test before release."
