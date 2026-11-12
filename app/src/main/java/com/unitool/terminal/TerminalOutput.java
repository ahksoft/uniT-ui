package com.unitool.terminal;

/**
 * Sink for everything a command writes.
 *
 * <p>Implemented by the terminal screen. All methods are called on the UI thread by the
 * {@link TerminalSession}.
 */
public interface TerminalOutput {

    void print(String text);

    void println(String text);

    void println();

    /** Writes an error message (usually shown in red). */
    void error(String text);

    /** Removes everything from the screen. */
    void clear();
}
