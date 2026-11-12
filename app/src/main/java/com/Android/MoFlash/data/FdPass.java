package com.Android.MoFlash.data;

/**
 * Passes a file descriptor to another process.
 *
 * <p>The native part is the helper library that is used by the flash tools of this app, it
 * was written for the app {@code com.Android.MoFlash}: the name of this class and of its
 * package cannot be changed, the library looks for exactly this name in the java runtime.
 *
 * <p>The receiver connects to an abstract unix socket, sends one byte and gets the file
 * descriptor of the device with the answer. The tools read that name from the environment
 * variable {@code MOMIUI_USB_SOCK}, which is why the app can give a usb device to a program
 * that cannot use the usb stack of android itself.
 */
public final class FdPass {
    static {
        System.loadLibrary("fdpass");
    }

    private FdPass() {
    }

    /**
     * Waits for the other process and gives it the descriptor.
     *
     * @param fd   the descriptor of the usb device
     * @param name the name of an abstract unix socket, it has to start with {@code @}
     * @return the number of bytes that were sent, or a negative number on an error:
     *         -1 bad name, -2 copy of the name failed, -3 bind failed, -4 listen failed,
     *         -5 accept failed, -6 nobody connected within ten seconds
     */
    public static native int sendFd(int fd, String name);
}
