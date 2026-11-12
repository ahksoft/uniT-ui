package com.unitool.terminal;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The environment for the native programs.
 *
 * <p>The programs are started in a pseudo terminal, they get the same environment a program
 * of the system would get: a home directory of their own, the tools of the app in the search
 * path and the library directory of the app for the shared libraries.
 */
public final class ToolEnv {

    private ToolEnv() {
    }

    /** The directory that is used as the home directory of the programs. */
    public static File home(Context context) {
        File home = new File(TerminalSession.appDirectory(context), "home");
        if (!home.exists() && !home.mkdirs()) {
            home = new File(context.getFilesDir(), "home");
            //noinspection ResultOfMethodCallIgnored
            home.mkdirs();
        }
        return home;
    }

    /**
     * Builds the environment of a program.
     *
     * @param workingDir the directory the program is started in
     */
    public static Map<String, String> environment(Context context, File workingDir) {
        Map<String, String> env = new HashMap<>(System.getenv());
        File libs = NativeTools.libDir(context);
        File bin = NativeTools.binDir(context);
        String libPath = libs == null ? "" : libs.getAbsolutePath();

        // The interactive terminal installs the core command symlinks into
        // $PREFIX/bin and the Termux runtime into $PREFIX/lib. Prefer those
        // directories so a task started from the Compose UI behaves exactly
        // like the same command typed into the core terminal.
        File prefix = new File(context.getFilesDir(), "usr");
        File prefixBin = new File(prefix, "bin");
        File prefixLib = new File(prefix, "lib");
        String prefixLibPath = prefixLib.getAbsolutePath();

        StringBuilder path = new StringBuilder();
        if (prefixBin.isDirectory()) path.append(prefixBin.getAbsolutePath()).append(':');
        if (bin != null) path.append(bin.getAbsolutePath()).append(':');
        if (!libPath.isEmpty()) path.append(libPath).append(':');
        path.append("/system/bin:/system/xbin");

        StringBuilder libraryPath = new StringBuilder();
        if (!prefixLibPath.isEmpty()) libraryPath.append(prefixLibPath);
        if (!libPath.isEmpty()) {
            if (libraryPath.length() > 0) libraryPath.append(':');
            libraryPath.append(libPath);
        }

        env.put("HOME", home(context).getAbsolutePath());
        env.put("TMPDIR", new File(prefix, "tmp").isDirectory()
                ? new File(prefix, "tmp").getAbsolutePath()
                : context.getCacheDir().getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        env.put("TERMUX_PREFIX", prefix.getAbsolutePath());
        env.put("PATH", path.toString());
        env.put("LD_LIBRARY_PATH", libraryPath.toString());
        // Android UsbManager-authorized descriptors are exposed through the
        // UniTool libusb broker, not /dev/bus/usb, so adb/fastboot must use
        // their libusb backends.
        env.put("ADB_LIBUSB", "1");
        if (libs != null) {
            File libusb = new File(libs, "libusb.so");
            env.put("UNITOOL_LIBUSB_PATH", libusb.getAbsolutePath());
            env.put("UNITOOL_LIBUSB_BRIDGE_PATH",
                    new File(libs, "libusb_bridge.so").getAbsolutePath());
            env.put("PYUSB_LIBUSB_PATH", libusb.getAbsolutePath());
        }
        env.put("MKE2FS_CONFIG", new File(prefixBin, "mke2fs.conf").getAbsolutePath());
        env.put("SSL_CERT_FILE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
        env.put("CURL_CA_BUNDLE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        // the shell of the app has a broken getcwd on some devices, the working directory is
        // taken from the environment instead
        if (workingDir != null) {
            env.put("PWD", workingDir.getAbsolutePath());
        }
        return env;
    }

    /** The environment as the array of "name=value" entries that the terminal needs. */
    public static String[] toArray(Map<String, String> env) {
        String[] result = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            result[i++] = entry.getKey() + "=" + entry.getValue();
        }
        return result;
    }
}
