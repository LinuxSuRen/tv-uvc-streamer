package com.linuxsuren.tvuvc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * 开机/摄像头插入时直接拉起采集服务（不经过任何界面切换，
 * 规避 MIUI 在应用离开前台时的「free resource」强杀）。
 */
public class AutoStartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDevice camera = StreamService.findCamera(usbManager);
            if (camera != null && usbManager.hasPermission(camera)) {
                try {
                    StreamService.start(context);
                } catch (IllegalStateException ignored) {
                    // Android 8+ 后台启动限制；等 JobScheduler 下个周期自愈
                }
            } else if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
                // 无授权记录：拉起界面走授权流程
                Intent ui = new Intent(context, MainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(ui);
            }
        }
    }
}
