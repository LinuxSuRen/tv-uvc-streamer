package com.linuxsuren.tvuvc;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.HashMap;

/**
 * 采集与流媒体服务。
 * <p>
 * MIUI TV 会在应用离开前台时按「free resource」强杀带前台 Activity 的应用，
 * 但不杀纯后台 started Service（SimpleSSHD 等应用即此模式，实测可后台存活小时级）。
 * 因此 MainActivity 完成授权后立即 finish，全部工作由本服务承担。
 * <p>
 * 服务持有 WakeLock 与 WifiLock：电视熄屏后系统默认的 WiFi 休眠策略会断网，
 * 锁可保证无人操作时转流持续可用（电视常电供电，功耗无虞）。
 */
public class StreamService extends Service {

    static final String EXTRA_FD = "fd";
    private static final int WANT_WIDTH = 1280;
    private static final int WANT_HEIGHT = 720;
    private static final int WANT_FPS = 25;

    private boolean running;
    private UsbDeviceConnection connection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && running) {
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(detachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        KeepAliveJob.schedule(this);
        if (running) {
            return START_STICKY;
        }
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice camera = findCamera(usbManager);
        if (camera == null || !usbManager.hasPermission(camera)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        connection = usbManager.openDevice(camera);
        if (connection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final int fd = connection.getFileDescriptor();
        new Thread(() -> {
            boolean ok = UvcCapture.nativeStart(fd, WANT_WIDTH, WANT_HEIGHT, WANT_FPS);
            if (ok) {
                try {
                    MjpegServer.start();
                    RtspServer.start();
                    OnvifServer.start(StreamService.this);
                    running = true;
                    acquireLocks();
                    return;
                } catch (Exception e) {
                    android.util.Log.e("tv-uvc-streamer", "server start failed", e);
                    UvcCapture.nativeStop();
                }
            }
            stopSelf();
        }, "uvc-start").start();
        return START_STICKY;
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvuvc:stream");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "tvuvc:stream");
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    static UsbDevice findCamera(UsbManager usbManager) {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                    return device;
                }
            }
        }
        return null;
    }

    @Override
    public void onDestroy() {
        if (running) {
            running = false;
            OnvifServer.stop();
            RtspServer.stop();
            MjpegServer.stop();
            UvcCapture.nativeStop();
        }
        releaseLocks();
        if (connection != null) {
            connection.close();
            connection = null;
        }
        unregisterReceiver(detachReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
