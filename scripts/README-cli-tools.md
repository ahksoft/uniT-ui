# Standalone CLI build

Linux host requirements: Android NDK r25.2.9519653, Git, make, Perl, Python 3, Autoconf/Automake, and standard build utilities.

```sh
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/25.2.9519653"
bash scripts/build-cli-tools-android.sh
```

The initial pipeline pins upstream Git tags and cross-builds separate ARM64 executables for OpenSSL, curl, GNU Wget, and Git. Each output is named `lib<command>.so` solely so Android packages it through `jniLibs`; it remains a standalone executable ELF.

This is a starting cross-build pipeline, not a verified release. Validate upstream tag availability, build compatibility, all DT_NEEDED dependencies, Git remote helper programs, certificate-bundle provisioning, and on-device command/HTTPS tests before enabling command aliases. The existing libusb broker and Python runtime are not modified.
