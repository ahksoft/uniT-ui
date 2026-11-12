package com.unitool.terminal;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * The master side of a pseudo terminal.
 *
 * <p>The terminal library gives the number of the file descriptor of the terminal, the
 * programs of the app are started in it. The file descriptor is wrapped so that the
 * output can be read and the input can be written.
 */
public final class PtyTerminal {

    private final int fd;
    private final ParcelFileDescriptor descriptor;
    private final boolean owns;

    private PtyTerminal(int fd, ParcelFileDescriptor descriptor, boolean owns) {
        this.fd = fd;
        this.descriptor = descriptor;
        this.owns = owns;
    }

    /**
     * Wraps the file descriptor of a terminal.
     *
     * @throws IOException when the descriptor cannot be used
     */
    public static PtyTerminal open(int fd) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // the descriptor takes over the file descriptor of the library
            return new PtyTerminal(fd, ParcelFileDescriptor.fromFd(fd), true);
        }
        try {
            Method dup = ParcelFileDescriptor.class.getMethod("dup", int.class);
            ParcelFileDescriptor copy = (ParcelFileDescriptor) dup.invoke(null, fd);
            return new PtyTerminal(fd, copy, false);
        } catch (Exception e) {
            throw new IOException("the terminal cannot be used: " + e.getMessage(), e);
        }
    }

    /** The file descriptor of the library. */
    public int getFd() {
        return fd;
    }

    /** Reads the output of the program, -1 when the terminal is closed. */
    public int read(ByteBuffer buffer) throws IOException {
        try {
            return Os.read(descriptor.getFileDescriptor(), buffer);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                return 0;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Writes the input of the program. */
    public void write(byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            try {
                offset += Os.write(descriptor.getFileDescriptor(), data, offset,
                        data.length - offset);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                    continue;
                }
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    /** Closes the terminal. */
    public void close() {
        try {
            descriptor.close();
        } catch (Exception ignored) {
        }
        if (!owns) {
            // the library still holds the file descriptor
            try {
                com.termux.terminal.JNI.close(fd);
            } catch (Throwable ignored) {
            }
        }
    }
}
