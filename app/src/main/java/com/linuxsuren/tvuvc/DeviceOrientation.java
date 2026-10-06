package com.linuxsuren.tvuvc;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 加速度传感器判断设备摆放，给出画面旋转建议角（顺时针）：
 * 横放 0°/180°，竖放 90°/270°，平放保持上次建议。
 * 移植自 ohos-ipcam-streamer 的 OrientationSensor，角度语义一致：
 * 与画面正立的对应关系以后摄 orientation=90 实测校准。
 */
public final class DeviceOrientation implements SensorEventListener {

    /** 建议角变化回调（传感器线程） */
    public interface Callback {
        void onSuggest(int suggestDeg);
    }

    private final SensorManager sensorManager;
    private final Callback callback;
    private volatile int suggestDeg = 90; // 默认竖放（绝大多数手机的自然姿态）

    private DeviceOrientation(SensorManager sensorManager, Callback callback) {
        this.sensorManager = sensorManager;
        this.callback = callback;
    }

    /** 注册加速度监听；无传感器设备返回 null（建议角恒为默认值） */
    public static DeviceOrientation start(Context context, Callback callback) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            return null;
        }
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            return null;
        }
        DeviceOrientation orientation = new DeviceOrientation(sm, callback);
        sm.registerListener(orientation, accel, SensorManager.SENSOR_DELAY_NORMAL);
        return orientation;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    public int getSuggest() {
        return suggestDeg;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (az > ay && az > ax) {
            return; // 平放：保持
        }
        int deg = suggestDeg;
        if (ax > ay * 2 && ax > 4) {
            deg = x < 0 ? 0 : 180; // 横放
        } else if (ay > ax * 2 && ay > 4) {
            deg = y > 0 ? 90 : 270; // 竖放
        }
        if (deg != suggestDeg) {
            suggestDeg = deg;
            callback.onSuggest(deg);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
