package com.unitool.terminal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native command line tools that are shipped with the app.
 *
 * <p>The executables are stored in {@code jniLibs/arm64-v8a} and are installed by the
 * package manager into the native library directory of the app
 * ({@code /data/app/../lib/arm64}). From there they can be started like normal programs,
 * so the core UniTool tool chain (adb, fastboot, heimdall, qdl, mtk/umbrage, ...) is
 * available in the terminal and to the Compose task screens.
 *
 * <p>The set below mirrors {@code assets/lib_symlinks.properties}, which is the single
 * source of truth used by the interactive terminal ({@code NativeToolchain}). Some tools
 * have a file name that is not {@code lib<name>.so} (for example
 * {@code libumbrage_cli_exec.so} is exposed as {@code mtk}/{@code umbrage}) and some
 * libraries provide several commands (for example {@code libcoreutils.so}). Those cases
 * are modelled with an explicit file name and an alias list.
 *
 * <p>The tools must not be copied into the data directory of the app: since Android 10 an
 * app is not allowed to execute a file from its own data directory, the native library
 * directory is the only place where this works.
 */
public final class NativeTools {

    private static final String TAG = "NativeTools";

    /** One command line tool. */
    public static final class Tool {
        public final String name;
        public final String summary;
        private final String fileName;
        private final List<String> aliases;

        Tool(String name, String fileName, String summary, String... aliases) {
            this.name = name;
            this.fileName = fileName;
            this.summary = summary;
            this.aliases = Collections.unmodifiableList(Arrays.asList(aliases));
        }

        /** The name of the file in the native library directory. */
        public String fileName() {
            return fileName;
        }

        /** Other command names that start the same executable. */
        public List<String> aliases() {
            return aliases;
        }

        /** The name plus every alias. */
        public List<String> commandNames() {
            List<String> names = new ArrayList<>();
            names.add(name);
            names.addAll(aliases);
            return names;
        }
    }

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    /** Alternative names for the tools. */
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        // Core UniTool CLI tools (see assets/lib_symlinks.properties).
        add(new Tool("adb", "libadb.so",
                "the adb client (devices, shell, push, pull) - libusb backend"));
        add(new Tool("fastboot", "libfastboot.so",
                "flash a device in fastboot mode - libusb backend"));
        add(new Tool("heimdall", "libheimdall_rpc.so",
                "Samsung flashing through the in-app Heimdall RPC bridge",
                "heimdall-rpc"));
        add(new Tool("qdl", "libqdl.so",
                "flash a Qualcomm device in EDL mode (sahara / firehose)"));
        add(new Tool("mtk", "libumbrage_cli_exec.so",
                "the native MediaTek BROM / preloader / DA tool (umbrage)",
                "umbrage", "ant", "antumbra", "mtk-tool"));
        add(new Tool("make_f2fs", "libmake_f2fs.so",
                "create an Android f2fs filesystem image"));
        add(new Tool("mke2fs", "libmke2fs.so",
                "create an ext2/ext3/ext4 filesystem image"));
        add(new Tool("tst", "libtest.so",
                "libusb / USB descriptor smoke test"));
        add(new Tool("bash", "libbash.so",
                "the bash shell", "sh"));
        add(new Tool("clear", "libclear.so",
                "clear the terminal screen"));
        add(new Tool("coreutils", "libcoreutils.so",
                "the Android coreutils multi-call binary",
                "cut", "cp", "mv", "mkdir", "rm", "rmdir", "echo", "env", "ln",
                "pwd", "tty", "sleep", "timeout", "split", "test", "readlink",
                "realpath", "printf", "mktemp", "dirname", "dircolors", "dir",
                "basename", "ls"));
        add(new Tool("unitool-usb-probe", "libunitool_usb_probe.so",
                "enumerate the USB devices visible through libusb_bridge"));
        add(new Tool("unitool-libusb-probe", "libunitool_libusb_probe.so",
                "exercise the packaged libusb API used by adb"));
        add(new Tool("unitool-libusb-wrap-probe", "libunitool_libusb_wrap_probe.so",
                "exercise the libusb wrapper used by the CLI tools"));
        add(new Tool("unitool-mtk-usb-probe", "libunitool_mtk_usb_probe.so",
                "read-only MediaTek USB enumeration and descriptor probe"));
    }

    private static void add(Tool tool) {
        TOOLS.put(tool.name, tool);
        for (String alias : tool.aliases()) {
            ALIASES.put(alias, tool.name);
        }
    }

    private NativeTools() {
    }

    /** All tools, in the order they are shown in the terminal. */
    public static List<Tool> tools() {
        return new ArrayList<>(TOOLS.values());
    }

    /** Looks a tool up, the name may also be given with an alias. */
    public static Tool find(String name) {
        if (name == null) return null;
        Tool tool = TOOLS.get(name);
        if (tool != null) return tool;
        String alias = ALIASES.get(name);
        return alias == null ? null : TOOLS.get(alias);
    }

    /** The native library directory of the app, the tools are installed there. */
    public static File libDir(Context context) {
        String dir = context.getApplicationInfo().nativeLibraryDir;
        return dir == null ? null : new File(dir);
    }

    /**
     * A directory that holds the tools under their normal names.
     *
     * <p>The package manager installs the executables as {@code lib<name>.so}, a shell
     * needs them as {@code <name>}. The directory only holds links, the programs are still
     * started from the native library directory of the app.
     *
     * @return the directory or {@code null} when it cannot be created
     */
    public static File binDir(Context context) {
        File libs = libDir(context);
        if (libs == null) return null;
        File dir = new File(context.getFilesDir(), "bin");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.d(TAG, "cannot create " + dir.getAbsolutePath());
            return null;
        }
        for (Tool tool : TOOLS.values()) {
            File target = new File(libs, tool.fileName());
            if (!target.exists()) continue;
            for (String command : tool.commandNames()) {
                File link = new File(dir, command);
                try {
                    // the directory of the app changes with every update, the links are made again
                    if (target.getCanonicalPath().equals(link.getCanonicalPath())) continue;
                } catch (Exception ignored) {
                }
                try {
                    //noinspection ResultOfMethodCallIgnored
                    link.delete();
                    android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
                } catch (Exception e) {
                    Log.d(TAG, "cannot link " + command + ": " + e.getMessage());
                }
            }
        }
        return dir;
    }

    /**
     * The executable of the tool.
     *
     * @return the file or {@code null} when the tool was not installed
     */
    public static File executable(Context context, Tool tool) {
        if (tool == null) return null;
        File dir = libDir(context);
        if (dir == null) return null;
        File file = new File(dir, tool.fileName());
        if (!file.exists()) return null;
        if (!file.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, false);
        }
        return file;
    }

    /** True when the tool is installed and can be started. */
    public static boolean isAvailable(Context context, Tool tool) {
        File file = executable(context, tool);
        return file != null && file.canExecute();
    }

    /**
     * The name of the program, without the {@code lib} prefix and the {@code .so} ending.
     *
     * <p>The programs use the name of themselves in their messages, they should not show
     * the path of the library of the app.
     */
    public static String programName(File executable) {
        String name = executable.getName();
        if (name.startsWith("lib") && name.endsWith(".so")) {
            return name.substring(3, name.length() - 3);
        }
        return name;
    }

    /** The names of all tools, used for the error message of an unknown command. */
    public static List<String> names() {
        List<String> names = new ArrayList<>(TOOLS.keySet());
        Collections.sort(names);
        return names;
    }
}
