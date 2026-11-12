UniTool runtime bootstrap files.

The application copies these text configuration files into its private
files/usr tree on first launch. Python integration files are installed under
files/usr/lib/python/site-packages. Native executable binaries are packaged
separately in jniLibs for each Android ABI and are never extracted from
third-party APKs by the build.

Python USB integration:
- lib/python/site-packages is added to PYTHONPATH by TerminalService.
- sitecustomize.py configures PyUSB's default libusb1 backend to load the
  absolute path in UNITOOL_LIBUSB_PATH.
- unitool_usb.py exposes get_backend() and find_all() for explicit use.
- The configured library is the APK's existing libusb.so, built with the
  UniTool USB FD broker backend. Do not install or load another libusb copy.
