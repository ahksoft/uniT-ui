package com.unitool.terminal;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts a native program in a pseudo terminal.
 *
 * <p>The programs of the app are shell programs, they expect a terminal. In a pseudo
 * terminal they can use colors, they move the cursor and they ask for the size of the
 * window. The output of the program is cleaned from the escape sequences and written into
 * the terminal of the app.
 */
public final class PtyRunner {

    private static final String TAG = "PtyRunner";

    /** The number of the rows of the terminal. */
    private static final int ROWS = 60;

    /** The id of the terminal. */
    private static final int TERMINAL_ID = 40;

    /** Called when the program is running, so that it can be stopped. */
    public interface ProcessListener {
        void onStart(int pid);
    }

    private PtyRunner() {
    }

    /**
     * Starts the program and waits for it.
     *
     * @return the exit code of the program
     */
    public static int run(File executable, List<String> args, File workingDir,
                          Map<String, String> environment, TerminalOutput out,
                          ProcessListener listener) throws IOException {
        return run(executable, args, workingDir, environment, out, listener, null);
    }

    /**
     * Starts the program and waits for it.
     *
     * @param programName the argv[0] to use, or {@code null} to derive it from the file.
     *                    Multi-call binaries such as coreutils depend on this name.
     */
    public static int run(File executable, List<String> args, File workingDir,
                          Map<String, String> environment, TerminalOutput out,
                          ProcessListener listener, String programName) throws IOException {
        List<String> argv = new ArrayList<>();
        // the program shows its name in its messages, not the path of the library
        argv.add(programName == null ? NativeTools.programName(executable) : programName);
        argv.addAll(args);

        int[] pid = new int[1];
        int fd = com.termux.terminal.JNI.createSubprocess(executable.getAbsolutePath(),
                workingDir == null ? "/" : workingDir.getAbsolutePath(),
                argv.toArray(new String[0]), ToolEnv.toArray(environment), pid,
                TERMINAL_ID, ROWS);
        if (fd < 0) {
            throw new IOException("the terminal cannot be opened: " + fd);
        }
        PtyTerminal terminal = PtyTerminal.open(fd);
        try {
            if (listener != null) listener.onStart(pid[0]);
            pump(terminal, out);
            return com.termux.terminal.JNI.waitFor(pid[0]);
        } finally {
            terminal.close();
        }
    }

    /** Reads the output of the program and writes it into the terminal. */
    private static void pump(PtyTerminal terminal, TerminalOutput out) {
        AnsiFilter filter = new AnsiFilter();
        StringBuilder line = new StringBuilder();
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        byte[] data = new byte[4096];
        try {
            while (true) {
                buffer.clear();
                int count = terminal.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                buffer.flip();
                buffer.get(data, 0, count);
                String text = filter.feed(new String(data, 0, count, StandardCharsets.UTF_8));
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '\n') {
                        out.println(line.toString());
                        line.setLength(0);
                    } else {
                        line.append(c);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "output closed: " + e.getMessage());
        }
        if (line.length() > 0) {
            out.println(line.toString());
        }
    }
}
