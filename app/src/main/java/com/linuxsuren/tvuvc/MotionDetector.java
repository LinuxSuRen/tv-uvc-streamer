package com.linuxsuren.tvuvc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.Locale;

/**
 * 块差分运动检测（tns1:RuleEngine/CellMotion/Motion 事件源）。
 * <p>
 * 从帧流按 ~4fps 抽样，BitmapFactory inSampleSize=8 降采样解码（1280x720 →
 * 160x90，单帧解码毫秒级），网格化亮度差分：
 * <ul>
 *   <li>降采样后按 2:1 宽高比分网格（160x90 → 16 列，行数按比例取整），每格平均亮度与上一帧比较</li>
 *   <li>变化格占比 &gt; ON_RATIO 判定运动开始；&lt; OFF_RATIO 持续 HOLD_MS 判定结束
 *       （滞回 + 保持，防边界抖动产生事件风暴）</li>
 *   <li>解码/差分在独立工作线程执行，最新帧槽位覆盖式更新（处理不过来丢旧帧，
 *       绝不阻塞采集线程）</li>
 * </ul>
 * 仅在运动状态翻转时发布事件（Data 携带 State 与变化格占比），配合事件总线
 * 的限频双保险。
 */
public final class MotionDetector implements MjpegServer.FrameListener {

    private static final int SAMPLE_INTERVAL_MS = 250;
    private static final int SAMPLE = 8;          // 降采样因子（2 的幂）
    private static final int GRID_W = 16;
    private static final double ON_RATIO = 0.12;  // 运动启动阈值
    private static final double OFF_RATIO = 0.05; // 运动结束阈值
    private static final long HOLD_MS = 2_000;    // 低于结束阈值的保持时长
    private static final int CELL_DIFF = 18;      // 单格平均亮度差阈值（0-255）

    private final Object slotLock = new Object();
    private byte[] pending;      // 最新待处理帧（覆盖式）
    private int pendingCamera;
    private Thread worker;
    private volatile boolean running = true;
    private int[] prevLuma;      // 上一帧网格亮度
    private int prevCamera = -1; // 相机切换后重置基线

    private boolean motion;
    private long motionOffSince;

    /** 注册即启动工作线程；停止由 stop() 完成 */
    public static MotionDetector start() {
        MotionDetector d = new MotionDetector();
        d.worker = new Thread(d::loop, "motion-detector");
        d.worker.setDaemon(true);
        d.worker.start();
        MjpegServer.addListener(d);
        return d;
    }

    public void stop() {
        running = false;
        MjpegServer.removeListener(this);
    }

    @Override
    public void onFrame(byte[] frame, int camera, long frameIndex) {
        synchronized (slotLock) {
            pending = frame;
            pendingCamera = camera;
        }
    }

    private void loop() {
        while (running) {
            long next = System.currentTimeMillis() + SAMPLE_INTERVAL_MS;
            byte[] frame;
            int camera;
            synchronized (slotLock) {
                frame = pending;
                camera = pendingCamera;
                pending = null;
            }
            if (frame != null) {
                process(frame, camera);
            }
            long sleep = next - System.currentTimeMillis();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void process(byte[] jpeg, int camera) {
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = SAMPLE;
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opt);
        } catch (OutOfMemoryError e) {
            return;
        }
        if (bmp == null) {
            return;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < GRID_W || h < 1) {
            bmp.recycle();
            return;
        }
        int gridH = Math.max(1, h * GRID_W / w / 2); // 网格约 2:1 宽高比
        int[] luma = new int[GRID_W * gridH];
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();
        int cw = w / GRID_W;
        int ch = h / gridH;
        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < GRID_W; gx++) {
                long sum = 0;
                int baseY = gy * ch;
                int baseX = gx * cw;
                for (int y = 0; y < ch; y++) {
                    int row = (baseY + y) * w + baseX;
                    for (int x = 0; x < cw; x++) {
                        int c = px[row + x];
                        sum += ((c >> 16 & 0xFF) * 299 + (c >> 8 & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
                    }
                }
                luma[gy * GRID_W + gx] = (int) (sum / (cw * ch));
            }
        }
        if (prevLuma == null || prevCamera != camera || prevLuma.length != luma.length) {
            prevLuma = luma; // 首帧/切相机：只建基线
            prevCamera = camera;
            return;
        }
        int changed = 0;
        for (int i = 0; i < luma.length; i++) {
            if (Math.abs(luma[i] - prevLuma[i]) > CELL_DIFF) {
                changed++;
            }
        }
        prevLuma = luma;
        prevCamera = camera;
        double ratio = changed / (double) luma.length;
        long now = System.currentTimeMillis();
        if (!motion && ratio > ON_RATIO) {
            motion = true;
            OnvifEventBus.publish("tns1:RuleEngine/CellMotion/Motion",
                    new String[][]{{"Source", "Camera_" + camera}},
                    new String[][]{{"State", "true"}, {"Ratio", String.format(Locale.US, "%.2f", ratio)}});
        } else if (motion) {
            if (ratio > OFF_RATIO) {
                motionOffSince = 0;
            } else if (motionOffSince == 0) {
                motionOffSince = now;
            } else if (now - motionOffSince >= HOLD_MS) {
                motion = false;
                motionOffSince = 0;
                OnvifEventBus.publish("tns1:RuleEngine/CellMotion/Motion",
                        new String[][]{{"Source", "Camera_" + camera}},
                        new String[][]{{"State", "false"}, {"Ratio", String.format(Locale.US, "%.2f", ratio)}});
            }
        }
    }
}
