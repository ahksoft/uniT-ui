package com.unitool.terminal;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/**
 * Runs one of the native tools and streams its output into the terminal.
 *
 * <p>The tool is started in a pseudo terminal, so it behaves like a program of a real
 * terminal. The escape sequences of the output are removed before they are shown.
 */
public final class NativeRunner {

    private static final String TAG = "NativeRunner";

    /** Called when the process starts, so that it can be stopped again. */
    public interface ProcessListener {
        void onStart(int pid);
    }

    private NativeRunner() {
    }

    /**
     * Starts the executable and waits for it.
     *
     * @return the exit code of the process
     */
    public static int run(Context context, File executable, List<String> args, File workingDir,
                          TerminalOutput out, ProcessListener listener) throws IOException {
        return run(context, executable, args, workingDir, out, listener, null);
    }

    /**
     * Starts the executable and waits for it.
     *
     * @param extraEnv environment entries for this run only, e.g. the name of the socket
     *                 that carries the usb device
     */
    public static int run(Context context, File executable, List<String> args, File workingDir,
                          TerminalOutput out, ProcessListener listener,
                          Map<String, String> extraEnv) throws IOException {
        return run(context, executable, args, workingDir, out, listener, extraEnv, null);
    }

    /**
     * Starts the executable and waits for it.
     *
     * @param programName the argv[0] to use, or {@code null} to derive it from the file.
     *                    Multi-call binaries such as coreutils depend on this name.
     */
    public static int run(Context context, File executable, List<String> args, File workingDir,
                          TerminalOutput out, ProcessListener listener,
                          Map<String, String> extraEnv, String programName) throws IOException {
        Map<String, String> env = ToolEnv.environment(context, workingDir);
        if (extraEnv != null) env.putAll(extraEnv);
        try {
            return PtyRunner.run(executable, args, workingDir, env, out,
                    listener == null ? null : listener::onStart, programName);
        } catch (UnsatisfiedLinkError e) {
            // the library of the terminal is missing, the program is started without one
            Log.w(TAG, "no terminal available: " + e.getMessage());
            return runPlain(context, executable, args, workingDir, out, listener, extraEnv);
        }
    }

    /** Starts the program without a terminal, it is the fallback. */
    private static int runPlain(Context context, File executable, List<String> args,
                                 File workingDir, TerminalOutput out,
                                 ProcessListener listener,
                                 Map<String, String> extraEnv) throws IOException {
        List<String> command = new java.util.ArrayList<>();
        // without a terminal the path of the program is the name of the program
        command.add(executable.getAbsolutePath());
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null && workingDir.exists()) {
            builder.directory(workingDir);
        }
        builder.environment().clear();
        builder.environment().putAll(ToolEnv.environment(context, workingDir));
        if (extraEnv != null) builder.environment().putAll(extraEnv);

        Process process = builder.start();
        if (listener != null) {
            listener.onStart(-1);
        }
        Thread errors = pump(process.getErrorStream(), out, true);
        Thread output = pump(process.getInputStream(), out, false);
        int code;
        try {
            code = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            code = -1;
        }
        try {
            output.join(1000);
            errors.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Log.d(TAG, executable.getName() + " finished with " + code);
        return code;
    }

    /** Reads a stream and writes it to the terminal, line by line. */
    private static Thread pump(InputStream stream, TerminalOutput out, boolean isError) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isError) {
                        out.error(line);
                    } else {
                        out.println(line);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "stream closed: " + e.getMessage());
            }
        }, "native-out");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
