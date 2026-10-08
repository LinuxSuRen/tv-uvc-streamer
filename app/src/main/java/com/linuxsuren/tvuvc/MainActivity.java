package com.linuxsuren.tvuvc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * 授权入口与运行状态页。
 * <p>
 * 授权（内置相机运行时授权 / USB 摄像头设备授权）完成后停留在本页展示
 * 设备信息与推流地址；关闭按钮 = 停止服务并彻底退出进程；
 * 系统返回键 = 仅关闭界面，服务在后台继续推流。
 * <p>
 * 无调试通道的定制设备上，上次崩溃堆栈会显示在状态页（{@link CrashGuard}）。
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.linuxsuren.tvuvc.USB_PERMISSION";
    private static final int REQ_CAMERA = 1;

    private UsbManager usbManager;
    private TextView statusView;
    private String crashInfo;
    private boolean serviceStarted;

    private long lastFrames = -1;
    private long lastTick = 0;
    private final Handler ui = new Handler();
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            ui.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    startStreamService();
                } else {
                    renderStatus("USB 授权被拒绝");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install();
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));

        Button close = findViewById(R.id.close);
        close.setOnClickListener(v -> {
            KeepAliveJob.cancel(this);
            stopService(new Intent(this, StreamService.class));
            finish();
            // 用户明确要求退出：结束进程，避免残留空进程；socket/相机由系统随进程回收
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        buildCameraSelector();
    }

    /** 多摄设备显示相机选择：自动（分辨率最高）或指定某一颗；不支持并发的硬件靠此切换 */
    private void buildCameraSelector() {
        android.widget.LinearLayout box = findViewById(R.id.camera_buttons);
        box.removeAllViews();
        java.util.List<String> labels = BuiltInCamera.cameraLabels(this);
        if (labels.size() < 2) {
            return; // 单摄（或无相机）无需选择
        }
        android.content.SharedPreferences prefs = getSharedPreferences("tvuvc", MODE_PRIVATE);
        int selected = prefs.getInt("camera_index", -1);
        // 「自动」选项
        box.addView(cameraButton("自动（分辨率最高）", selected == -1, index -> {
            prefs.edit().putInt("camera_index", -1).apply();
            restartCapture();
        }));
        for (int i = 0; i < labels.size(); i++) {
            final int cameraIndex = i;
            box.addView(cameraButton(labels.get(i), selected == i, index -> {
                prefs.edit().putInt("camera_index", cameraIndex).apply();
                restartCapture();
            }));
        }
    }

    private android.widget.Button cameraButton(String text, boolean selected, android.view.View.OnClickListener listener) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText((selected ? "● " : "○ ") + text);
        button.setTextColor(0xFFEEEEEE);
        button.setTextSize(16);
        button.setOnClickListener(listener);
        return button;
    }

    private void restartCapture() {
        renderStatus("正在切换摄像头…");
        statusView.postDelayed(() -> {
            StreamService.restart(MainActivity.this);
            statusView.postDelayed(this::buildCameraSelector, 1600);
        }, 150);
    }

    @Override
    protected void onStart() {
        super.onStart();
        crashInfo = CrashGuard.lastCrash(this);
        UsbDevice camera = StreamService.findCamera(usbManager);
        boolean hasBuiltin = BuiltInCamera.hasCamera(this);
        if (camera == null && !hasBuiltin) {
            renderStatus("未发现摄像头（USB 或内置）");
            return;
        }
        // 内置相机授权与是否插着 USB 相机无关（部分整机内挂 USB 摄像头，
        // 曾因此每次启动都被误导向 USB 授权弹框）；服务端本就优先内置路径
        boolean usbReady = camera != null && usbManager.hasPermission(camera);
        boolean builtinReady = hasBuiltin
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (usbReady || builtinReady || serviceStarted) {
            startStreamService();
            return;
        }
        if (hasBuiltin) {
            // 内置相机在但未授权：优先走 CAMERA 运行时授权
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
            renderStatus("等待相机授权…");
            return;
        }
        if (camera != null) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(camera, pi);
            renderStatus("等待 USB 授权确认…");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startStreamService();
        } else {
            renderStatus("相机授权被拒绝");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void startStreamService() {
        serviceStarted = true;
        KeepAliveJob.schedule(this);
        StreamService.start(this);
        renderStatus();
    }

    /** 运行状态页：设备信息 + 推流地址 + 实时帧计数（每秒刷新） */
    private void renderStatus() {
        renderStatus(null);
    }

    private void renderStatus(String override) {
        StringBuilder sb = new StringBuilder();
        if (crashInfo != null) {
            sb.append("上次崩溃:\n").append(crashInfo).append("\n\n");
        }
        if (override != null) {
            sb.append(override);
            statusView.setText(sb);
            return;
        }
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("（Android ").append(Build.VERSION.RELEASE).append("）\n\n");
        if (serviceStarted) {
            long frames = MjpegServer.frameCount();
            sb.append("推流运行中\n");
            if (lastFrames >= 0 && frames >= lastFrames && lastTick > 0) {
                long elapsed = System.currentTimeMillis() - lastTick;
                int fps = elapsed > 0 ? (int) ((frames - lastFrames) * 1000 / elapsed) : 0;
                sb.append("累计帧数: ").append(frames)
                        .append(String.format(Locale.US, "（约 %d fps）\n", fps));
            } else {
                sb.append("累计帧数: ").append(frames).append('\n');
            }
            lastFrames = frames;
            lastTick = System.currentTimeMillis();
            sb.append('\n')
                    .append("RTSP  ").append(RtspServer.url()).append('\n')
                    .append("MJPEG  ").append(MjpegServer.url()).append("stream\n")
                    .append("快照   ").append(MjpegServer.url()).append("snapshot.jpg\n")
                    .append("ONVIF  http://").append(MjpegServer.lanAddress())
                    .append(':').append(OnvifServer.HTTP_PORT).append("/onvif/device_service\n\n")
                    .append("按返回键可保持后台推流");
        } else {
            sb.append("服务未启动");
        }
        statusView.setText(sb);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
