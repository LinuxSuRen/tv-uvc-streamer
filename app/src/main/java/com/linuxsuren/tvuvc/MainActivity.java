package com.linuxsuren.tvuvc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;

/**
 * 入口页：负责 USB 摄像头授权与采集/服务生命周期管理。
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.linuxsuren.tvuvc.USB_PERMISSION";
    private static final int WANT_WIDTH = 1280;
    private static final int WANT_HEIGHT = 720;
    private static final int WANT_FPS = 25;

    private UsbManager usbManager;
    private TextView statusView;
    private boolean running;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (granted && device != null) {
                    startCapture(device);
                } else {
                    setStatus("USB 授权被拒绝");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                ensureCamera();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && running) {
                    stopCapture();
                    setStatus("摄像头已拔出");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        statusView = findViewById(R.id.status);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(receiver, filter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensureCamera();
    }

    @Override
    protected void onDestroy() {
        stopCapture();
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private UsbDevice findCamera() {
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

    private void ensureCamera() {
        if (running) {
            return;
        }
        UsbDevice camera = findCamera();
        if (camera == null) {
            setStatus("未发现 USB 摄像头，请插入后等待授权弹窗");
            return;
        }
        if (usbManager.hasPermission(camera)) {
            startCapture(camera);
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(camera, pi);
        setStatus("等待 USB 授权确认…");
    }

    private void startCapture(final UsbDevice device) {
        if (running) {
            return;
        }
        final UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            setStatus("打开设备失败");
            return;
        }
        final int fd = connection.getFileDescriptor();
        new Thread(() -> {
            boolean ok = UvcCapture.nativeStart(fd, WANT_WIDTH, WANT_HEIGHT, WANT_FPS);
            runOnUiThread(() -> {
                if (ok) {
                    running = true;
                    try {
                        MjpegServer.start();
                        setStatus("采集中 " + WANT_WIDTH + "x" + WANT_HEIGHT
                                + "\n流地址 " + MjpegServer.url() + "stream"
                                + "\n快照 " + MjpegServer.url() + "snapshot.jpg");
                    } catch (Exception e) {
                        UvcCapture.nativeStop();
                        setStatus("HTTP 服务启动失败: " + e.getMessage());
                    }
                } else {
                    connection.close();
                    setStatus("采集启动失败（检查摄像头是否支持 MJPEG "
                            + WANT_WIDTH + "x" + WANT_HEIGHT + "）");
                }
            });
        }, "uvc-start").start();
    }

    private synchronized void stopCapture() {
        if (!running) {
            return;
        }
        running = false;
        MjpegServer.stop();
        UvcCapture.nativeStop();
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }
}
