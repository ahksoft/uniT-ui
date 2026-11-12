package com.termux.terminal;

/**
 * The native part of the terminal library.
 *
 * <p>The library starts the programs in a pseudo terminal, so they behave like they do in a
 * real terminal: they can use colors, they move the cursor and they read the keyboard.
 * The file descriptor of the master side of the terminal is the handle of the terminal.
 */
public abstract class JNI {

    static {
        System.loadLibrary("termux");
    }

    /** Closes the terminal. */
    public static native void close(int fd);

    /**
     * Starts a program in a new pseudo terminal.
     *
     * @param commandPath  the program that is started
     * @param workingDirectory the directory the program is started in
     * @param argv         the arguments, the first one is the name of the program
     * @param env          the environment, as "name=value" entries
     * @param processId    receives the process id of the program
     * @param terminalId   the id of the terminal
     * @param rows         the number of rows of the terminal
     *
     * @return the file descriptor of the master side of the terminal
     */
    public static native int createSubprocess(String commandPath, String workingDirectory,
                                               String[] argv, String[] env, int[] processId,
                                               int terminalId, int rows);

    /** Waits for a program and returns its exit code. */
    public static native int waitFor(int pid);
}
