package com.linuxsuren.tvuvc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    static final String EXTRA_RESTART_PIPELINE = "restart_pipeline";
    private static final int WANT_WIDTH = 1280;
    private static final int WANT_HEIGHT = 720;
    private static final int WANT_FPS = 25;
    private static final String CHANNEL_ID = "tvuvc-stream";
    private static final int NOTIFICATION_ID = 1;

    private boolean running;
    /** 启动流水线进行中标志：相机打开/回退是异步的，防止并发重复启动（KeepAliveJob 与界面同时拉起时实测会并发打开两次相机/争抢 UVC 上下文） */
    private final AtomicBoolean starting = new AtomicBoolean();
    private UsbDeviceConnection connection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) || !running) {
                return;
            }
            // 只有正在采集的摄像头（视频类设备）被拔出才停止；
            // 部分整机将 WiFi 等模块内挂在 USB 总线上，其重枚举广播不应中断推流
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || hasVideoInterface(device)) {
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
        promoteToForeground();
        KeepAliveJob.schedule(this);
        if (intent != null && intent.getBooleanExtra(EXTRA_RESTART_PIPELINE, false) && running) {
            // 管线重启：保留服务与服务器，仅重建采集（看门狗升级恢复路径）
            android.util.Log.w("tv-uvc-streamer", "restarting capture pipeline");
            running = false;
            starting.set(false);
            BuiltInCamera.stop();
        }
        if (running || !starting.compareAndSet(false, true)) {
            return START_STICKY;
        }
        // 优先内置摄像头（Camera2 系统路径，避开定制设备上的原生层异常）；
        // 无内置、未授权或启动失败时回退 USB UVC（电视场景）。
        // openCamera 的 connect binder 调用可能被卡死的相机 HAL 无限期阻塞
        // （OPPO 实测 ANR），因此整个启动尝试放工作线程，绝不占主线程
        final boolean builtinAuthorized = BuiltInCamera.hasCamera(this)
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (builtinAuthorized) {
            new Thread(() -> {
                // 服务器先行（参考 ohos-ipcam-streamer）：相机暖机期间拉流端即可连接；
                // 相机全败时由 stopSelf 兜底回收服务
                try {
                    MjpegServer.setCrashInfo(CrashGuard.lastCrash(StreamService.this));
                } catch (Exception ignored) {
                }
                try {
                    MjpegServer.start();
                    RtspServer.start();
                    OnvifServer.start(StreamService.this);
                } catch (Exception e) {
                    android.util.Log.e("tv-uvc-streamer", "server start failed", e);
                    stopSelf();
                    return;
                }
                boolean accepted = BuiltInCamera.start(this, WANT_WIDTH, WANT_HEIGHT,
                        () -> {
                            running = true;
                            starting.set(false);
                            acquireLocks();
                        },
                        () -> startFromUsb());
                if (!accepted) {
                    if (!running) {
                        startFromUsb();
                    } else {
                        starting.set(false);
                    }
                }
            }, "builtin-cam-start").start();
            return START_STICKY;
        }
        startFromUsb();
        return START_STICKY;
    }

    private void startFromUsb() {
        if (running) {
            starting.set(false);
            return;
        }
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice camera = findCamera(usbManager);
        if (camera == null || !usbManager.hasPermission(camera)) {
            starting.set(false);
            stopSelf();
            return;
        }
        connection = usbManager.openDevice(camera);
        if (connection == null) {
            starting.set(false);
            stopSelf();
            return;
        }
        final int fd = connection.getFileDescriptor();
        new Thread(() -> {
            boolean ok = UvcCapture.nativeStart(fd, WANT_WIDTH, WANT_HEIGHT, WANT_FPS);
            if (ok) {
                try {
                    MjpegServer.registerCamera(0, "USB 摄像头");
                    MjpegServer.start();
                    RtspServer.start();
                    OnvifServer.start(StreamService.this);
                    running = true;
                    starting.set(false);
                    acquireLocks();
                    return;
                } catch (Exception e) {
                    android.util.Log.e("tv-uvc-streamer", "server start failed", e);
                    UvcCapture.nativeStop();
                }
            }
            starting.set(false);
            stopSelf();
        }, "uvc-start").start();
    }

    /**
     * 升级为前台服务并挂常驻通知：Android 12 的「应用闲置」会在约 1 分钟后
     * 强停纯后台 started Service（实测 "Stopping service due to app idle"），
     * 前台服务豁免该机制；Android 6（电视场景）同样兼容，仅多一条状态通知。
     */
    private void promoteToForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26
                && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.foreground_channel), NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.foreground_title))
                .setContentText(MjpegServer.url() + "stream")
                .setOngoing(true);
        startForeground(NOTIFICATION_ID, builder.build());
    }

    /** 统一启动入口：所有机型走纯后台服务（targetSdk 23 豁免 API 26+ 后台限制） */
    static void start(Context context) {
        context.startService(new Intent(context, StreamService.class));
    }

    /** 采集参数变更（如切换摄像头）后的重启：先停稳再拉起，避免 running 标志竞态 */
    static void restart(Context context) {
        context.stopService(new Intent(context, StreamService.class));
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> start(context), 800);
    }

    /**
     * 管线整体重启（看门狗升级恢复用）：服务保持存活，只重建采集管线。
     * 不走 stopService——应用会跌入 cached 态，定制 ROM 会静默拒绝延迟的
     * 后台 startService（实测服务记录归零、管线死寂）。
     */
    static void restartPipeline(Context context) {
        Intent intent = new Intent(context, StreamService.class);
        intent.putExtra(EXTRA_RESTART_PIPELINE, true);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // 极端场景服务已死：交给 KeepAliveJob 周期自愈
        }
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
            if (hasVideoInterface(device)) {
                return device;
            }
        }
        return null;
    }

    private static boolean hasVideoInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onDestroy() {
        if (running) {
            running = false;
            starting.set(false);
            BuiltInCamera.stop();
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
