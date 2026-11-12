package com.unitool.terminal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * An interactive bash shell inside the terminal.
 *
 * <p>The shell is started in a pseudo terminal, so it behaves like the shell of a real
 * terminal: it reads the commands from the terminal and it writes its output into it. The
 * shell reads the commands from the terminal of the app, the terminal keeps the control
 * over the prompt and over the input line.
 *
 * <p>The working directory of the shell is asked after every command, the answer is used
 * for the prompt of the terminal.
 */
public class ShellSession {

    private static final String TAG = "ShellSession";

    /** Marks the line with the working directory of the shell. */
    private static final String CWD_MARKER = "__unitool_cwd__:";

    private final PtyTerminal terminal;
    private final int pid;
    private final Thread reader;
    private final Runnable onCommandDone;
    private volatile String workingDir = "";
    private volatile boolean closed;
    private int pending;

    /**
     * Starts the shell.
     *
     * @param onCommandDone called when a command has been finished
     */
    public ShellSession(Context context, File workingDir, TerminalOutput out,
                        Runnable onCommandDone) throws IOException {
        this.onCommandDone = onCommandDone;
        File bash = NativeTools.executable(context, NativeTools.find("bash"));
        if (bash == null) {
            throw new IOException("bash is not installed");
        }

        Map<String, String> env = ToolEnv.environment(context, workingDir);
        // the terminal of the app shows the prompt, the shell must not print one
        env.put("PS1", "");
        env.put("PS2", "");
        // the shell reports the working directory after every command
        env.put("PROMPT_COMMAND", "printf '" + CWD_MARKER + "%s\\n' \"$PWD\"");

        // without the line editing of readline the terminal echoes the input, so the
        // command is shown exactly once
        List<String> argv = Arrays.asList("bash", "--noprofile", "--norc", "+o", "emacs");
        int[] processId = new int[1];
        int fd = com.termux.terminal.JNI.createSubprocess(bash.getAbsolutePath(),
                workingDir == null ? "/" : workingDir.getAbsolutePath(),
                argv.toArray(new String[0]), ToolEnv.toArray(env), processId, 40, 60);
        if (fd < 0) {
            throw new IOException("the terminal cannot be opened: " + fd);
        }
        pid = processId[0];
        terminal = PtyTerminal.open(fd);

        reader = new Thread(() -> read(out), "shell-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Sends a command to the shell. */
    public synchronized void send(String line) {
        if (closed) return;
        try {
            pending++;
            terminal.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.d(TAG, "cannot write to the shell: " + e.getMessage());
            closed = true;
        }
    }

    /** The working directory of the shell. */
    public String getWorkingDir() {
        return workingDir;
    }

    /** The working directory of the shell as a file. */
    public File getWorkingDirFile() {
        String dir = workingDir;
        if (dir == null || dir.isEmpty()) return null;
        try {
            return new File(dir);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Ends the shell. */
    public void close() {
        closed = true;
        try {
            android.os.Process.sendSignal(pid, 9);
        } catch (Exception ignored) {
        }
        terminal.close();
    }

    private void read(TerminalOutput out) {
        AnsiFilter filter = new AnsiFilter();
        StringBuilder line = new StringBuilder();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4096);
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
                        show(out, line.toString());
                        line.setLength(0);
                    } else {
                        line.append(c);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "shell closed: " + e.getMessage());
        }
        if (line.length() > 0) show(out, line.toString());
        closed = true;
        commandDone();
    }

    /** Writes a line of the shell into the terminal. */
    private void show(TerminalOutput out, String line) {
        if (line.startsWith(CWD_MARKER)) {
            workingDir = line.substring(CWD_MARKER.length()).trim();
            commandDone();
            return;
        }
        out.println(line);
    }

    /** The command of the shell is finished, the terminal shows the prompt again. */
    private synchronized void commandDone() {
        if (pending > 0) pending--;
        if (onCommandDone != null) onCommandDone.run();
    }
}
