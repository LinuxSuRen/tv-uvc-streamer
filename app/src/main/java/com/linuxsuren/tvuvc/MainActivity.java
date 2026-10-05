package com.linuxsuren.tvuvc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.widget.TextView;

/**
 * 授权入口：完成 USB 摄像头授权后启动 {@link StreamService} 并立即 finish，
 * 让进程成为纯后台 Service（规避 MIUI 前台切换强杀）。
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.linuxsuren.tvuvc.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusView;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    startStreamService();
                } else {
                    statusView.setText("USB 授权被拒绝");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        registerReceiver(receiver, new IntentFilter(ACTION_USB_PERMISSION));
    }

    @Override
    protected void onStart() {
        super.onStart();
        UsbDevice camera = StreamService.findCamera(usbManager);
        if (camera == null) {
            statusView.setText("未发现 USB 摄像头，请插入");
            return;
        }
        if (usbManager.hasPermission(camera)) {
            startStreamService();
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(camera, pi);
        statusView.setText("等待 USB 授权确认…");
    }

    private void startStreamService() {
        KeepAliveJob.schedule(this);
        StreamService.start(this);
        statusView.setText("已转后台服务\nRTSP " + RtspServer.url()
                + "\nMJPEG " + MjpegServer.url() + "stream");
        finish();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
