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
    /** 相机打开可能被卡死的 HAL 无限期挂起（Max2.0 重开悬置双 client、OPPO connectHelper 挂起均实测），超时放行 starting 允许下轮启动 */
    private static final long STARTING_TIMEOUT_MS = 90_000;
    /** 内置相机全部失败后的退避重试：开机阶段相机 HAL 可能尚未就绪，立即放弃会白白等 15 分钟 JobScheduler */
    private static final int MAX_BUILTIN_RETRIES = 3;
    private static final long BUILTIN_RETRY_DELAY_MS = 10_000;
    /** 失败纪元：超过该时长无新失败则重置重试计数 */
    private static final long BUILTIN_FAILURE_EPOCH_MS = 5 * 60_000;

    private boolean running;
    /** 启动流水线进行中标志：相机打开/回退是异步的，防止并发重复启动（KeepAliveJob 与界面同时拉起时实测会并发打开两次相机/争抢 UVC 上下文） */
    private final AtomicBoolean starting = new AtomicBoolean();
    private volatile long startingSince;
    /** 进程级失败簿记：AMS 重建服务会重置实例字段，开机广播风暴的连环拉起会瞬间烧光重试次数（实测 1 秒 12 次） */
    private static long lastBuiltinFailureAt;
    private static int builtinFailures;
    /** 已排定退避重试：风暴回声的连环拉起不应把它 stopSelf 取消（曾互相取消致重试永不执行） */
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private UsbDeviceConnection connection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) || !running) {
                return;
            }
            // 只有正在采集的 UVC 摄像头被拔出才停止（connection 非空 = UVC 路径在用）；
            // 内置相机推流时，无关的视频类 USB 设备拔插（含成串重枚举）不应中断推流；
            // 部分整机将 WiFi 等模块内挂在 USB 总线上，其重枚举广播同样不应中断
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (connection != null && (device == null || hasVideoInterface(device))) {
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
        applyAuthConfig();
        startEventSources();
        if (intent != null && intent.getBooleanExtra(EXTRA_RESTART_PIPELINE, false) && running) {
            // UVC 正在推流：重启请求多半来自交接空窗期的僵尸误判（标签未及挂上），
            // 不是真实故障——保持工作中的采集源，只补挂标签（重启风暴曾把刚
            // 建立的 UVC 每 50ms 拆一次，播放端全程断流，Max2.0 实测）
            if (connection != null) {
                MjpegServer.registerCamera(0, "USB 摄像头");
                return START_STICKY;
            }
            // 管线重启：保留服务与服务器，仅重建采集（看门狗升级恢复路径）
            android.util.Log.w("tv-uvc-streamer", "restarting capture pipeline");
            running = false;
            starting.set(false);
            BuiltInCamera.stop();
        }
        // 解卡：相机打开/回退全程无回调时 starting 会永久为真（HAL 挂起实测），
        // 后续所有 startService 都会被它挡掉——服务活着却永远起不了管线
        if (starting.get()
                && android.os.SystemClock.elapsedRealtime() - startingSince > STARTING_TIMEOUT_MS) {
            android.util.Log.w("tv-uvc-streamer", "camera start hung, releasing starting flag");
            starting.set(false);
            if (!running) {
                // 清掉挂起尝试的残留（旧采集线程与半开的会话条目），避免脏状态叠加
                BuiltInCamera.stop();
            }
        }
        if (running || !starting.compareAndSet(false, true)) {
            return START_STICKY;
        }
        startingSince = android.os.SystemClock.elapsedRealtime();
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
                            builtinFailures = 0;
                            lastBuiltinFailureAt = 0;
                            acquireLocks();
                        },
                        () -> builtinAllFailed());
                if (!accepted) {
                    if (!running) {
                        builtinAllFailed();
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
            // UVC 已在推流：回退循环中的内置尝试可能与 UVC 共用 0 号槽位，
            // closeCam 会连带反注册 UVC 的标签——此处补挂，状态页/ONVIF 才能恢复枚举
            MjpegServer.registerCamera(0, "USB 摄像头");
            starting.set(false);
            return;
        }
        // 多颗 USB 相机并存时只在已授权设备里选：findCamera 的 HashMap 迭代
        // 顺序不稳定，两次调用可能返回不同设备（曾导致回退判定与实际启动
        // 选到不同相机，未授权即静默退出，Max2.0 双 UVC 实测）
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbDevice camera = findAuthorizedCamera(usbManager);
        if (camera == null) {
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
     * 内置相机全部失败的处理。策略是「优先保障有摄像头可用，而不是硬抢某一颗」：
     * <ul>
     *   <li>有已授权的 USB 摄像头 → 立即回退 UVC，不浪费重试时间
     *       （内置相机被特权应用抢占的设备上，UVC 是唯一可用来源，Max2.0 实测）</li>
     *   <li>没有 USB 替代 → 短退避重试（开机阶段相机 HAL 可能尚未就绪，
     *       立即放弃要干等 15 分钟 JobScheduler）</li>
     * </ul>
     * 多路相机失败回调可能并发，靠 starting 的 CAS 保证单次尝试只处理一次。
     * <p>
     * 时间闸门：开机时 USB 总线枚举风暴（WiFi/触摸模块逐个上线）会产生连环
     * startService，每次都是独立相机尝试——间隔不足退避时长的失败视为风暴
     * 回声，静默退出交给 JobScheduler 周期，不叠加重试（实测 1 秒内 12 次拉起）。
     */
    private void builtinAllFailed() {
        if (!starting.compareAndSet(true, false)) {
            return;
        }
        // 有可用的 USB 相机：立即回退，重试只在别无选择时进行
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (findAuthorizedCamera(usbManager) != null) {
            android.util.Log.w("tv-uvc-streamer", "builtin cameras all failed, falling back to USB camera");
            builtinFailures = 0;
            startFromUsb();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBuiltinFailureAt > BUILTIN_FAILURE_EPOCH_MS) {
            builtinFailures = 0; // 上一轮失败已冷却：重新给满重试机会
        }
        boolean storm = now - lastBuiltinFailureAt < BUILTIN_RETRY_DELAY_MS;
        lastBuiltinFailureAt = now;
        if (!storm
                && builtinFailures < MAX_BUILTIN_RETRIES
                && BuiltInCamera.hasCamera(this)
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            builtinFailures++;
            android.util.Log.w("tv-uvc-streamer", "builtin cameras all failed, retry "
                    + builtinFailures + "/" + MAX_BUILTIN_RETRIES
                    + " in " + (BUILTIN_RETRY_DELAY_MS / 1000) + "s");
            retryScheduled.set(true);
            retryHandler.postDelayed(this::retryBuiltinStart, BUILTIN_RETRY_DELAY_MS);
            return;
        }
        if (storm) {
            if (retryScheduled.get()) {
                return; // 重试已在途：保持服务存活等它，风暴回声不叠加动作
            }
            android.util.Log.w("tv-uvc-streamer", "builtin cameras failed again too soon, backing off to JobScheduler");
            startFromUsb();
            return;
        }
        startFromUsb();
    }

    /** 退避重试入口：走服务标准启动路径（含前台提升与自愈任务重排） */
    private void retryBuiltinStart() {
        retryScheduled.set(false);
        StreamService.restartPipeline(StreamService.this);
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

    /**
     * 认证凭证随服务（自）启动重放：设置页变更时已即时注入 Auth 静态配置，
     * 但进程被杀后由 KeepAliveJob/AutoStartReceiver 拉起时静态字段已丢，须从
     * 私有 SharedPreferences 重放（口令明文存私有沙箱：Digest 服务端校验需明文参与哈希）。
     */
    private void applyAuthConfig() {
        android.content.SharedPreferences prefs = getSharedPreferences("tvuvc", MODE_PRIVATE);
        Auth.configure(prefs.getBoolean("auth_enabled", false),
                prefs.getString("auth_user", ""),
                prefs.getString("auth_pass", ""));
    }

    // ---------------- 事件源（ONVIF Event，PullPoint 模型）----------------

    /** 静态存活：服务重启风暴下不重复注册（监听器幂等 + 定时器防重排） */
    private static MotionDetector motionDetector;
    private static boolean batteryScheduled;
    private static int lastBatteryStatus = -1;
    private static int lastBatteryLevel = -1;
    private static boolean batteryTempHigh;
    private static final int[] BATTERY_THRESHOLDS = {20, 10, 5};
    private static final int BATTERY_TEMP_HIGH = 450; // 0.1°C

    /** 运动检测 + 电池监控：进程生命周期内各起一份 */
    private void startEventSources() {
        if (motionDetector == null) {
            motionDetector = MotionDetector.start();
        }
        if (batteryScheduled) {
            return;
        }
        batteryScheduled = true;
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!batteryScheduled) {
                    return;
                }
                readBattery();
                handler.postDelayed(this, 30_000);
            }
        };
        handler.post(tick);
    }

    /** 电池状态变化才发事件（充电状态切换/电量跨阈值/温度跨 45°C），附电压数据 */
    private void readBattery() {
        try {
            Intent i = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return;
            }
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int temp = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1000); // 0.1°C
            int voltage = i.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1);     // mV
            if (level < 0) {
                return;
            }
            int percent = level * 100 / Math.max(1, scale);
            boolean changed = false;
            if (lastBatteryStatus < 0) {
                changed = true; // 首次上报基线
            } else {
                if (status != lastBatteryStatus) {
                    changed = true;
                }
                for (int t : BATTERY_THRESHOLDS) {
                    if ((lastBatteryLevel > t) != (percent > t)) {
                        changed = true; // 跨越低电量阈值
                    }
                }
                boolean high = temp >= BATTERY_TEMP_HIGH;
                if (high != batteryTempHigh) {
                    changed = true;
                    batteryTempHigh = high;
                }
            }
            lastBatteryStatus = status;
            lastBatteryLevel = percent;
            if (changed) {
                OnvifEventBus.publish("lns:Power/Battery",
                        new String[][]{{"Source", "Battery"}},
                        new String[][]{
                                {"Level", String.valueOf(percent)},
                                {"Status", batteryStatusText(status)},
                                {"Temp", temp > -1000 ? String.format(java.util.Locale.US, "%.1f", temp / 10.0) : "unknown"},
                                {"Voltage", voltage > 0 ? String.valueOf(voltage) : "unknown"},
                        });
            }
        } catch (Exception ignored) {
            // 注册 receiver 极端失败：跳过本周期
        }
    }

    private static String batteryStatusText(int status) {
        switch (status) {
            case android.os.BatteryManager.BATTERY_STATUS_CHARGING:
                return "Charging";
            case android.os.BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "Discharging";
            case android.os.BatteryManager.BATTERY_STATUS_FULL:
                return "Full";
            case android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "NotCharging";
            default:
                return "Unknown";
        }
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
        } catch (IllegalStateException e) {
            // 定制 ROM 会在应用退后台后拒绝进程内 startService（即使 FGS 存活，
            // Max2.0 实测：看门狗 fullRestart 静默失效、管线死寂）。借道
            // JobScheduler 立即任务的白名单上下文重启，2 秒内生效
            KeepAliveJob.scheduleImmediate(context);
        }
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvuvc:stream");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        // Android 10+ 上 WIFI_MODE_FULL 已被系统无视；LOW_LATENCY 才真正关闭
        // WiFi 省电（实测部分新机型省电开启时大流量发送路径黑洞，快照/流卡死）
        wifiLock = wm.createWifiLock(
                android.os.Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL,
                "tvuvc:stream");
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

    /** 第一颗已授权的视频类 USB 设备（多颗并存时迭代顺序不稳定，只认授权集） */
    static UsbDevice findAuthorizedCamera(UsbManager usbManager) {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            if (hasVideoInterface(device) && usbManager.hasPermission(device)) {
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
        // 无条件回收全部组件：曾经仅在 running 时才停服务器，相机全灭路径
        // （stopSelf 于 running=false）会把 HTTP/RTSP/ONVIF 留成僵尸——端口还在
        // 应答却无流可推，ONVIF 只剩兜底假 Profile，自愈任务也被 socket 存活
        // 假象骗过而不重启（Max2.0 实测）。各 stop 均可安全重入
        retryHandler.removeCallbacksAndMessages(null);
        running = false;
        starting.set(false);
        BuiltInCamera.stop();
        OnvifServer.stop();
        RtspServer.stop();
        MjpegServer.stop();
        UvcCapture.nativeStop();
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
