package com.unitool.terminal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.unitool.cli.MtkCli;
import com.unitool.lk.UblCommand;
import com.unitool.protocol.mtk.MtkProtocol;
import com.unitool.adb.AdbClient;
import com.unitool.usb.UsbDeviceInfo;
import com.unitool.usb.UsbHostManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The terminal session.
 *
 * <p>Holds the state of the session (working directory, command history, the open
 * MediaTek connection) and executes the commands that are typed into the terminal.
 *
 * <p>Every command runs on a background thread, the output is marshalled back to the UI
 * thread by {@link MainThreadOutput}.
 */
public class TerminalSession {

    private static final String TAG = "Terminal";

    private final Context context;
    private final UsbHostManager usbHostManager;
    private final MtkProtocol mtkProtocol;
    private final AdbClient adbClient = new AdbClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> history = new ArrayList<>();

    private File workingDir;
    private Runnable onExit;
    private volatile int runningPid = -1;
    private ShellSession shell;
    private Runnable pendingComplete;
    private TerminalOutput sessionOutput;

    public TerminalSession(Context context, UsbHostManager usbHostManager) {
        this.context = context.getApplicationContext();
        this.usbHostManager = usbHostManager;
        this.mtkProtocol = new MtkProtocol(usbHostManager);
        this.workingDir = defaultWorkingDir(this.context);
    }

    /** The name of the directory of the app on the storage of the phone. */
    public static final String DIRECTORY_NAME = "UniTool";

    /**
     * The directory of the app on the storage of the phone, everything the tools write
     * goes in there.
     *
     * <p>It is made when it does not exist. When the storage cannot be used the private
     * directory of the app is given back.
     */
    public static File appDirectory(Context context) {
        try {
            File external = android.os.Environment.getExternalStorageDirectory();
            if (external != null && external.exists() && external.canRead()) {
                File dir = new File(external, DIRECTORY_NAME);
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                    // the storage cannot be written, the app keeps its files inside
                    return privateDirectory(context);
                }
                return dir;
            }
        } catch (Exception ignored) {
        }
        return privateDirectory(context);
    }

    private static File privateDirectory(Context context) {
        File dir = new File(context.getFilesDir(), DIRECTORY_NAME);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /**
     * The directory the shell starts in.
     *
     * <p>The own directory of the app is used, it is the place where the images, the
     * payloads and the loaders are kept.
     */
    private static File defaultWorkingDir(Context context) {
        return appDirectory(context);
    }

    public MtkProtocol getMtkProtocol() {
        return mtkProtocol;
    }

    /** The directory the next program is started in. */
    public File getWorkingDir() {
        return workingDir;
    }

    public void setOnExit(Runnable onExit) {
        this.onExit = onExit;
    }

    /** Runs a command line, the output is written to {@code out}. */
    public void execute(String line, TerminalOutput out) {
        execute(line, out, null);
    }

    /**
     * Runs a command line.
     *
     * @param onComplete called on the UI thread when the command has finished, used to
     *                   print the next prompt
     */
    public void execute(String line, TerminalOutput out, Runnable onComplete) {
        if (line == null) return;
        final String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        synchronized (history) {
            if (history.isEmpty() || !history.get(history.size() - 1).equals(trimmed)) {
                history.add(trimmed);
            }
        }

        final TerminalOutput safe = new MainThreadOutput(out);
        final MtkCli cli = new MtkCli(context, usbHostManager, mtkProtocol, safe);
        sessionOutput = safe;

        // a program can hold the only worker of the terminal, stopping it must work anyway
        if (trimmed.equals("stop") || trimmed.equals("kill") || trimmed.equals("ctrl-c")) {
            stopRunningProcess();
            safe.println("[native] the running program was stopped");
            if (onComplete != null) mainHandler.post(onComplete);
            return;
        }
        executor.execute(() -> {
            boolean handledByShell = false;
            try {
                if (shell != null) {
                    List<String> tokens = CommandLine.tokenize(trimmed);
                    String name = tokens.isEmpty() ? "" : tokens.get(0);
                    if (isAppCommand(name)) {
                        // the app handles the command itself, the shell waits for the next one
                        dispatch(name, tokens.subList(1, tokens.size()), trimmed, safe, cli);
                    } else {
                        // the shell gets the whole line
                        shell.send(trimmed);
                        pendingComplete = onComplete;
                        handledByShell = true;
                    }
                } else {
                    for (String command : CommandLine.splitCommands(trimmed)) {
                        command = command.trim();
                        if (command.isEmpty()) continue;
                        List<String> tokens = CommandLine.tokenize(command);
                        if (tokens.isEmpty()) continue;
                        String name = tokens.get(0);
                        List<String> args = tokens.subList(1, tokens.size());
                        dispatch(name, args, command, safe, cli);
                    }
                }
            } catch (Exception e) {
                safe.error("error: " + e.getMessage());
                Log.e(TAG, "command failed", e);
            }
            if (!handledByShell && onComplete != null) mainHandler.post(onComplete);
        });
    }

    /**
     * True when the terminal has to show the command itself.
     *
     * <p>The shell echoes the commands that it gets, the commands of the app are shown by
     * the terminal.
     */
    public boolean showsCommand(String line) {
        if (shell == null) return true;
        List<String> tokens = CommandLine.tokenize(line == null ? "" : line.trim());
        String name = tokens.isEmpty() ? "" : tokens.get(0);
        return isAppCommand(name);
    }

    /**
     * True for the commands the app handles itself.
     *
     * <p>They are also usable while the shell is running, the shell knows nothing of the
     * flashing tool.
     */
    private static boolean isAppCommand(String name) {
        switch (name) {
            case "mtkclient":
            case "mtk":
            case "umbrage":
            case "antumbra":
            case "ant":
            case "qdl":
            case "heimdall":
            case "ubl":
            case "lkpatch":
            case "tools":
            case "stop":
            case "kill":
            case "usb":
            case "super":
            case "payload":
            case "run":
            case "adbfy":
            case "exit":
            case "quit":
                return true;
            default:
                return false;
        }
    }

    private void dispatch(String name, List<String> args, String line, TerminalOutput out,
                          MtkCli cli) {
        switch (name) {
            case "help":
            case "?":
                help(out);
                break;
            case "clear":
                out.clear();
                break;
            case "ls":
            case "dir":
                ls(out);
                break;
            case "cd":
                cd(args, out);
                break;
            case "pwd":
                out.println(workingDir.getAbsolutePath());
                break;
            case "cat":
                cat(args, out);
                break;
            case "echo":
                out.println(String.join(" ", args));
                break;
            case "history":
                history(out);
                break;
            case "date":
                out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                break;
            case "whoami":
                out.println("unitool");
                break;
            case "usb":
                usb(out);
                break;
            case "adb":
                // the real adb binary, the app gives it the usb device when needed
                nativeTool("adb", args, out);
                break;
            case "adbfy":
                // the small adb client of the app, it talks over the network
                adb(args, out);
                break;
            case "mtkclient":
                // the Java port of mtkclient, kept for the unitool-specific
                // protocol features
                cli.run(args);
                break;
            case "mtk":
            case "umbrage":
            case "antumbra":
            case "ant":
                // core UniTool's native MediaTek engine (umbrage)
                nativeTool("mtk", args, out);
                break;
            case "qdl":
            case "heimdall":
                // the flash tools, the device is handed over as a descriptor
                nativeTool(name, args, out);
                break;
            case "ubl":
            case "lkpatch":
                // unlock the bootloader of a Transsion phone
                UblCommand.run(args, workingDir, this::nativeToolCode, out);
                break;
            case "stop":
            case "kill":
                // handled before the worker, see execute()
                break;
            case "tools":
                tools(out);
                break;
            case "super":
                ImageTools.buildSuper(context, args, workingDir, out);
                break;
            case "payload":
                ImageTools.payload(context, args, workingDir, out);
                break;
            case "run":
                if (args.isEmpty()) {
                    out.error("run: missing tool name");
                    break;
                }
                nativeTool(args.get(0), args.subList(1, args.size()), out);
                break;

            case "bash":
            case "sh":
                if (args.isEmpty()) {
                    startShell(out);
                } else {
                    nativeTool("bash", args, out);
                }
                break;
            case "exit":
            case "quit":
                if (shell != null) {
                    // leave the shell, the terminal goes on
                    shell.close();
                    shell = null;
                    break;
                }
                stopRunningProcess();
                mtkProtocol.disconnect();
                if (onExit != null) mainHandler.post(onExit);
                break;
            default:
                if (NativeTools.find(name) != null) {
                    nativeTool(name, args, out);
                } else {
                    // everything else is a command of the shell
                    bashCommand(line, out);
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    // native tools
    // ------------------------------------------------------------------

    /** Starts the interactive shell. */
    private void startShell(TerminalOutput out) {
        if (shell != null) {
            out.println("[shell] the shell is already running");
            return;
        }
        try {
            shell = new ShellSession(context, workingDir, out, this::shellCommandDone);
            out.println("[shell] bash is running - type 'exit' to leave the shell");
        } catch (Exception e) {
            shell = null;
            out.error("bash: " + e.getMessage());
        }
    }

    /** Called when the shell has finished a command. */
    private void shellCommandDone() {
        Runnable complete = pendingComplete;
        pendingComplete = null;
        ShellSession current = shell;
        if (current != null && current.isClosed()) {
            shell = null;
            // the terminal keeps the directory of the shell
            File dir = current.getWorkingDirFile();
            if (dir != null && dir.exists()) workingDir = dir;
            if (sessionOutput != null) sessionOutput.println("[shell] the shell has ended");
        }
        if (complete != null) mainHandler.post(complete);
    }

    /** Runs a command with the shell. */
    private void bashCommand(String line, TerminalOutput out) {
        NativeTools.Tool tool = NativeTools.find("bash");
        File executable = NativeTools.executable(context, tool);
        if (executable == null) {
            out.error("bash: the shell is not installed");
            return;
        }
        try {
            NativeRunner.run(context, executable, Arrays.asList("-c", line), workingDir, out,
                    pid -> runningPid = pid);
        } catch (Exception e) {
            out.error("bash: " + e.getMessage());
        } finally {
            runningPid = -1;
        }
    }

    /** Shows the tools that are bundled with the app. */
    private void tools(TerminalOutput out) {
        out.println("Native tools that are bundled with the app:");
        out.println("");
        for (NativeTools.Tool tool : NativeTools.tools()) {
            String state = NativeTools.isAvailable(context, tool) ? "ready" : "missing";
            out.println(String.format("  %-18s %-7s %s", tool.name, state, tool.summary));
        }
        out.println("");
        out.println("  run <tool> [args]    run one of the tools");

    }

    private void nativeTool(String name, List<String> args, TerminalOutput out) {
        nativeToolCode(name, args, out);
    }

    /** Runs a tool and gives back its exit code. */
    private int nativeToolCode(String name, List<String> args, TerminalOutput out) {
        NativeTools.Tool tool = NativeTools.find(name);
        if (tool == null) {
            out.error(name + ": unknown tool - type 'tools' for the list");
            return -1;
        }
        if ("bash".equals(tool.name) && args.isEmpty()) {
            out.println("usage: bash -c \"<command>\"   (the shell of the app)");
            return -1;
        }
        File executable = NativeTools.executable(context, tool);
        if (executable == null) {
            out.error(tool.name + ": the tool is not installed");
            return -1;
        }
        // The tools own their device discovery and waiting. The app does not
        // pre-wait or watch the phone, so a command typed into the terminal and
        // the same command started from a UI task behave exactly the same way.
        // The packaged binaries reach authorized devices through UniTool's
        // libusb_bridge broker, which TerminalRuntime starts with the app.
        int result = -1;
        try {
            int code = NativeRunner.run(context, executable, args, workingDir, out,
                    pid -> runningPid = pid, null, name);
            result = code;
            if (code != 0) {
                out.println("[native] " + tool.name + " exited with " + code);
            }
        } catch (Exception e) {
            out.error(tool.name + ": " + e.getMessage());
        } finally {
            runningPid = -1;
        }
        return result;
    }

    /**
     * Runs a command line and waits until it has finished.
     *
     * @return true when the command ended without an error
     */
    public boolean executeAndWait(String line, TerminalOutput out) {
        if (line == null || line.trim().isEmpty()) return true;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {true};
        execute(line, new TerminalOutput() {
            @Override
            public void print(String text) {
                out.print(text);
            }

            @Override
            public void println(String text) {
                out.println(text);
            }

            @Override
            public void println() {
                out.println();
            }

            @Override
            public void error(String text) {
                ok[0] = false;
                out.error(text);
            }

            @Override
            public void clear() {
                out.clear();
            }
        }, done::countDown);
        try {
            done.await(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];
    }

    /** Stops the command that is running. */
    public void stop() {
        stopRunningProcess();
    }

    /**
     * Stops the running program the way a terminal does: first a ctrl+c (SIGINT) so the
     * program can clean up, and a moment later SIGKILL when it is still there.
     */
    public void stopRunningProcess() {
        final int pid = runningPid;
        if (pid <= 0) return;
        try {
            android.os.Process.sendSignal(pid, 2); // SIGINT, the same as ctrl+c
        } catch (Exception e) {
            try {
                android.os.Process.sendSignal(pid, 9);
            } catch (Exception ignored) {
            }
        }
        Thread killer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                if (!new File("/proc/" + pid).exists()) return;
            }
            try {
                android.os.Process.sendSignal(pid, 9);
            } catch (Exception ignored) {
            }
        }, "stop-watchdog");
        killer.setDaemon(true);
        killer.start();
        runningPid = -1;
    }

    // ------------------------------------------------------------------
    // built in commands
    // ------------------------------------------------------------------

    private void help(TerminalOutput out) {
        out.println("UniTool terminal");
        out.println("");
        out.println("  help              this text");
        out.println("  clear             clear the screen");
        out.println("  ls                list files of the working directory");
        out.println("  cd [dir]          change the directory, without one it goes home");
        out.println("  pwd               print the working directory");
        out.println("  cat <file>        print a file");
        out.println("  echo <text>       print text");
        out.println("  history           the command history");
        out.println("  date              the current date and time");
        out.println("  usb               list the attached USB devices");
        out.println("  stop              stop the program that is running");
        out.println("  adb <args>        the core adb binary (devices, shell, push, pull)");
        out.println("  fastboot <args>   the core fastboot binary");
        out.println("  heimdall <args>   Samsung flashing through the in-app RPC bridge");
        out.println("  qdl <args>        the Qualcomm EDL tool (sahara / firehose)");
        out.println("                    qdl prog_firehose_ddr.elf rawprogram0.xml patch0.xml");
        out.println("  mtk <cmd>         the native MediaTek engine, short: umbrage");
        out.println("                    mtk info | mtk partitions | mtk read boot boot.img");
        out.println("                    mtk write boot.img boot | mtk flash-scatter scatter.xml");
        out.println("  mtkclient <cmd>   the Java port of mtkclient (unitool engine)");
        out.println("  ubl <lk.bin>      unlock the bootloader of a Tecno/Infinix/itel phone");
        out.println("                    ubl pull | ubl flash <img> | ubl all");
        out.println("  adbfy <cmd>       the small network adb client of the app:");
        out.println("                    connect|shell|pair|disconnect|key");
        out.println("  tools             the native tools that are bundled with the app");
        out.println("  run <tool> [args] run one of the native tools");
        out.println("  super <dir>       build a super image (needs lpmake, not in the core set)");
        out.println("  payload <cmd>     list or extract an OTA payload (not in the core set)");
        out.println("  exit              close the app");
        out.println("");
        out.println("  mtk --help        shows all commands of the native MediaTek tool");
        out.println("  mtkclient help    shows all commands of the Java port");
        out.println("  tools             shows all native tools and their state");
    }

    private void ls(TerminalOutput out) {
        File[] files = workingDir.listFiles();
        if (files == null || files.length == 0) {
            out.println("(empty)");
            return;
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            out.println(String.format("%-10s %10d  %s",
                    file.isDirectory() ? "dir" : "file",
                    file.length(),
                    file.getName()));
        }
    }

    private void cd(List<String> args, TerminalOutput out) {
        if (args.isEmpty()) {
            // without an argument the shell goes home
            workingDir = appDirectory(context);
            return;
        }
        String path = args.get(0);
        File target;
        if (path.equals("~")) {
            target = appDirectory(context);
        } else if (path.startsWith("~/")) {
            target = new File(appDirectory(context), path.substring(2));
        } else if (path.startsWith("/")) {
            target = new File(path);
        } else {
            target = new File(workingDir, path);
        }
        if (!target.exists()) {
            out.error("cd: no such directory: " + path);
            return;
        }
        if (!target.isDirectory()) {
            out.error("cd: not a directory: " + path);
            return;
        }
        try {
            workingDir = target.getCanonicalFile();
        } catch (Exception e) {
            workingDir = target;
        }
    }

    private void cat(List<String> args, TerminalOutput out) {
        if (args.isEmpty()) {
            out.error("cat: missing file");
            return;
        }
        File file = ImageTools.resolve(workingDir, args.get(0));
        if (!file.exists()) {
            out.error("cat: no such file: " + args.get(0));
            return;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(file.toPath())) {
                out.println(line);
            }
        } catch (Exception e) {
            out.error("cat: " + e.getMessage());
        }
    }

    private void history(TerminalOutput out) {
        synchronized (history) {
            int i = 1;
            for (String command : history) {
                out.println(String.format("%4d  %s", i++, command));
            }
        }
    }

    private void usb(TerminalOutput out) {
        usbHostManager.rescan();
        List<UsbDeviceInfo> devices = usbHostManager.listDevices();
        if (devices.isEmpty()) {
            out.println("No USB devices attached.");
            return;
        }
        for (UsbDeviceInfo info : devices) {
            StringBuilder line = new StringBuilder();
            line.append(String.format("%s  %-28s", info.getVidPidHex(), info.getDisplayName()));
            if (info.isMediaTek()) {
                line.append("MTK ").append(info.getMtkMode());
            } else if (info.isSpreadtrum()) {
                line.append("SPD ").append(info.detectMode());
            } else {
                line.append("unknown");
            }
            out.println(line.toString());
        }
    }

    private void adb(List<String> args, TerminalOutput out) {
        if (args.isEmpty()) {
            out.println("usage: adb connect <host> [port] | adb shell <cmd> | "
                    + "adb pair <host> <port> <code> | adb disconnect | adb key");
            return;
        }
        String action = args.get(0).toLowerCase();
        switch (action) {
            case "connect": {
                if (args.size() < 2) {
                    out.error("adb connect: missing host");
                    return;
                }
                int port = args.size() > 2 ? Integer.parseInt(args.get(2)) : 5555;
                out.println(adbClient.connect(args.get(1), port));
                return;
            }
            case "shell": {
                if (args.size() < 2) {
                    out.error("adb shell: missing command");
                    return;
                }
                String command = String.join(" ", args.subList(1, args.size()));
                out.println(adbClient.shell(command));
                return;
            }
            case "pair": {
                if (args.size() < 4) {
                    out.error("adb pair: usage: adb pair <host> <port> <code>");
                    return;
                }
                out.println(adbClient.pair(args.get(1), Integer.parseInt(args.get(2)), args.get(3)));
                return;
            }
            case "disconnect":
                adbClient.disconnect();
                out.println("disconnected");
                return;
            case "key":
                out.println(adbClient.getPublicKeyHash());
                return;
            default:
                out.error("adb: unknown action '" + action + "'");
        }
    }

    // ------------------------------------------------------------------
    // life cycle
    // ------------------------------------------------------------------

    public List<String> getHistory() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** The directory of the app, everything the tools write is kept there. */
    public File getHomeDirectory() {
        return appDirectory(context);
    }

    /** Short name of the working directory, used for the prompt. */
    public String getWorkingDirName() {
        try {
            String path = workingDir.getAbsolutePath();
            for (File home : new File[]{appDirectory(context), context.getFilesDir()}) {
                String base = home.getAbsolutePath();
                if (path.equals(base)) return "~";
                if (path.startsWith(base + File.separator)) {
                    return path.substring(base.length());
                }
            }
            return path;
        } catch (Exception e) {
            return "~";
        }
    }

    /** The prompt of the terminal, the shell shows the directory it is in. */
    public String getPrompt() {
        ShellSession current = shell;
        if (current != null) {
            String dir = current.getWorkingDir();
            if (dir == null || dir.isEmpty()) return "unitool:$ ";
            return "unitool:" + dir + " $ ";
        }
        return "unitool:" + getWorkingDirName() + " $ ";
    }

    /** True while the interactive shell is running. */
    public boolean isShellRunning() {
        return shell != null;
    }

    public void close() {
        stopRunningProcess();
        if (shell != null) {
            shell.close();
            shell = null;
        }
        mtkProtocol.disconnect();
        adbClient.disconnect();
        executor.shutdownNow();
    }

    /** Writes everything on the UI thread. */
    private class MainThreadOutput implements TerminalOutput {
        private final TerminalOutput delegate;

        MainThreadOutput(TerminalOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void print(String text) {
            mainHandler.post(() -> delegate.print(text));
        }

        @Override
        public void println(String text) {
            mainHandler.post(() -> delegate.println(text));
        }

        @Override
        public void println() {
            mainHandler.post(delegate::println);
        }

        @Override
        public void error(String text) {
            mainHandler.post(() -> delegate.error(text));
        }

        @Override
        public void clear() {
            mainHandler.post(delegate::clear);
        }
    }
}
