package com.unitool;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.unitool.usb.UsbHostManager;

import ahk.unitool.gsm.terminal.TerminalContextProvider;
import ahk.unitool.gsm.terminal.TerminalRuntime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UniToolApp extends Application {
    private static final String TAG = "UniTool";

    private UsbHostManager usbHostManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        // The terminal emulator loads its native library through ReLinker,
        // which needs an application context.
        TerminalContextProvider.init(this);

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== UNCAUGHT EXCEPTION ===");
                pw.println("Thread: " + thread.getName() + " (id=" + thread.getId() + ")");
                pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                ex.printStackTrace(pw);
                pw.flush();
                String trace = sw.toString();
                Log.e(TAG, trace);
                File crashFile = new File(getFilesDir(), "crash_log.txt");
                try (FileOutputStream fos = new FileOutputStream(crashFile, true)) {
                    fos.write(trace.getBytes("UTF-8"));
                    fos.write('\n');
                }
            } catch (Exception ignored) {}
        });

        try {
            createNotificationChannel();
            startForegroundService();
            acquireWakeLock();

            usbHostManager = new UsbHostManager(this);
            Log.i(TAG, "UsbHostManager created");

            // The packaged adb/fastboot/heimdall/qdl binaries talk to the
            // native libusb_bridge broker. Start it for the whole process so
            // both the Compose flashing engine and the interactive terminal
            // can reach authorized USB devices.
            TerminalRuntime.INSTANCE.start(this);
            Log.i(TAG, "Terminal USB broker started");

            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbReceiver, filter);
            Log.i(TAG, "USB receiver registered");
        } catch (Throwable t) {
            Log.e(TAG, "FATAL: startup failed", t);
        }
    }

    @Override
    public void onTerminate() {
        releaseWakeLock();
        try {
            unregisterReceiver(usbReceiver);
            stopService(new Intent(this, UniToolService.class));
            TerminalRuntime.INSTANCE.stop();
        } catch (Exception ignored) {}
        super.onTerminate();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "unitool_foreground",
                    "UniTool Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps UniTool running in background");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void startForegroundService() {
        try {
            Intent serviceIntent = new Intent(this, UniToolService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start foreground service: " + e.getMessage());
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "UniTool:AppWakeLock"
                );
                wakeLock.acquire(30 * 60 * 1000L);
                Log.i(TAG, "Wake lock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to acquire wake lock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.i(TAG, "Wake lock released");
        }
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) return;

            switch (intent.getAction()) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    Log.i(TAG, "USB attached: " + device.getVendorId() + ":" + device.getProductId());
                    usbHostManager.onDeviceAttached(device);
                    break;
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    Log.i(TAG, "USB detached: " + device.getVendorId() + ":" + device.getProductId());
                    usbHostManager.onDeviceDetached(device);
                    break;
            }
        }
    };

    public UsbHostManager getUsbHostManager() {
        return usbHostManager;
    }
}
