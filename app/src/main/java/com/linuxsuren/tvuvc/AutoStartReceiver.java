package com.linuxsuren.tvuvc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/**
 * 开机/摄像头插入时直接拉起采集服务（不经过任何界面切换，
 * 规避 MIUI 在应用离开前台时的「free resource」强杀）。
 * <p>
 * 开机自启路径按设备形态：
 * <ul>
 *   <li>USB UVC 摄像头已有授权（电视场景）→ 直接起服务</li>
 *   <li>内置相机且 CAMERA 运行时授权在手（手机/平板/工控板）→ 直接起服务；
 *       未授权则不开机拉界面打扰用户，等待首次手动启动授权</li>
 * </ul>
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
                startService(context);
                return;
            }
            // 无 USB 摄像头（或未授权）：内置相机路径，已授权即静默自启
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && camera == null
                    && BuiltInCamera.hasCamera(context)
                    && context.checkSelfPermission(android.Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startService(context);
                return;
            }
            if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
                // USB 摄像头插入但无授权记录：拉起界面走授权流程
                Intent ui = new Intent(context, MainActivity.class);
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(ui);
            }
        }
    }

    private void startService(Context context) {
        try {
            StreamService.start(context);
        } catch (IllegalStateException ignored) {
            // Android 8+ 后台启动限制；等 JobScheduler 下个周期自愈
        }
    }
}
