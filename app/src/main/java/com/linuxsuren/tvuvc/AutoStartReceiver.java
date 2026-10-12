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
 *   <li>内置相机且 CAMERA 运行时授权在手（手机/平板/工控板）→ 直接起服务
 *       （与是否插着未授权的 UVC 无关，USB 仅是内置失败后的回退）；
 *       未授权则不开机拉界面打扰用户，等待首次手动启动授权</li>
 * </ul>
 */
public class AutoStartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                // QUICKBOOT 在 manifest 中注册但厂商定制 action 无公开常量
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean usbAttached = "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action);
        if (!boot && !usbAttached) {
            return;
        }
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice camera = StreamService.findCamera(usbManager);
        if (camera != null && usbManager.hasPermission(camera)) {
            startService(context);
            return;
        }
        // 内置相机已授权即静默自启，不再要求「无 USB 摄像头」：曾有一颗未授权
        // UVC 插着时整个开机自启被静默阻断（Max2.0 实测）。StreamService 本就
        // 优先内置路径，USB 仅在内置全部失败后才回退
        if (BuiltInCamera.hasCamera(context)
                && context.checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startService(context);
            return;
        }
        // 只有「摄像头插入且无授权记录、又无可用内置相机」才拉起界面走授权流程；
        // 部分整机将 WiFi 等模块内挂 USB 总线，其重枚举广播不应打扰用户
        if (usbAttached && camera != null) {
            Intent ui = new Intent(context, MainActivity.class);
            ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(ui);
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
