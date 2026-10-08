package com.linuxsuren.tvuvc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置摄像头采集（Camera2，多摄像头并发）。
 * <p>
 * 枚举全部相机（前置/后置/外接）同时开启，各自独立采集会话；
 * 帧按枚举序号发布到 {@link MjpegServer#publish(byte[], int)}。
 * 优先 JPEG 直出（多数设备支持）；不支持时回退 YUV_420_888 软压缩。
 * 部分设备的 HAL 限制并发路数，开启失败的相机自动跳过，全部失败才算失败。
 * <p>
 * 画面方向：加速度传感器跟踪设备摆放，JPEG_ORIENTATION 由 HAL 转正像素。
 */
public final class BuiltInCamera {

    private static final String TAG = "tv-uvc-streamer";

    /** 单路相机会话 */
    private static final class Cam {
        final int index;
        final String id; // Camera2 相机 ID，看门狗重开用
        final String label;
        final int facing;
        final int sensorOrientation;
        final boolean jpegDirect;
        final ImageReader reader;
        CameraDevice device;
        CameraCaptureSession session;
        volatile boolean streaming;
        volatile int reopenFailures;

        Cam(int index, String id, String label, int facing, int sensorOrientation, boolean jpegDirect, ImageReader reader) {
            this.index = index;
            this.id = id;
            this.label = label;
            this.facing = facing;
            this.sensorOrientation = sensorOrientation;
            this.jpegDirect = jpegDirect;
            this.reader = reader;
        }
    }

    private static final List<Cam> CAMS = new ArrayList<>();
    private static HandlerThread thread;
    private static Handler handler;
    private static DeviceOrientation orientation;
    private static boolean running;
    private static android.content.Context appContext;
    /** 帧停滞看门狗：>6s 无新帧重开该路会话，连续 3 次整体重启（参考 ohos-ipcam-streamer） */
    private static Thread watchdog;
    private static volatile long cooldownUntil;
    private static final java.util.Map<Integer, Long> LAST_FRAMES = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> LAST_ADVANCE = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Integer> STALL_COUNT = new java.util.HashMap<>();

    private BuiltInCamera() {
    }

    public static boolean hasCamera(Context context) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            return manager != null && manager.getCameraIdList().length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 不打开相机即可枚举的相机标签（下标即全局相机编号，供选择界面使用） */
    public static List<String> cameraLabels(Context context) {
        List<String> labels = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return labels;
            }
            String[] ids = manager.getCameraIdList();
            for (int i = 0; i < ids.length; i++) {
                Integer facing = manager.getCameraCharacteristics(ids[i])
                        .get(CameraCharacteristics.LENS_FACING);
                labels.add(labelOf(facing == null ? -1 : facing, i));
            }
        } catch (Exception ignored) {
        }
        return labels;
    }

    /** 相机可输出的最大面积（像素），优先 JPEG 格式，供自动模式排序 */
    private static long maxArea(CameraManager manager, String id) {
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            }
            long best = 0;
            if (sizes != null) {
                for (Size s : sizes) {
                    best = Math.max(best, (long) s.getWidth() * s.getHeight());
                }
            }
            return best;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前在推流的相机（编号 → 名称），供 ONVIF / 状态页枚举 */
    public static List<String> activeCameras() {
        List<String> labels = new ArrayList<>();
        synchronized (CAMS) {
            for (Cam cam : CAMS) {
                if (cam.streaming) {
                    labels.add(cam.label);
                }
            }
        }
        return labels;
    }

    @SuppressLint("MissingPermission")
    public static boolean start(Context context, final int wantWidth, final int wantHeight,
                                final Runnable onReady, final Runnable onFailure) {
        if (running) {
            return true;
        }
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return false;
            }
            final String[] ids = manager.getCameraIdList();
            if (ids == null || ids.length == 0) {
                return false;
            }
            thread = new HandlerThread("builtin-cam");
            thread.start();
            handler = new Handler(thread.getLooper());

            // 相机选择：用户指定编号则只开该路；自动模式按最大分辨率降序尝试
            // （不支持并发的硬件上，先开的高分辨率相机占用 ISP，即"默认分辨率最高"）
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("tvuvc", Context.MODE_PRIVATE);
            int selected = prefs.getInt("camera_index", -1);
            final List<Integer> order = new ArrayList<>();
            if (selected >= 0 && selected < ids.length) {
                order.add(selected);
            } else {
                for (int i = 0; i < ids.length; i++) {
                    order.add(i);
                }
                final CameraManager m = manager;
                final String[] idArr = ids;
                order.sort((a, b) -> Long.compare(maxArea(m, idArr[b]), maxArea(m, idArr[a])));
            }

            final AtomicInteger pending = new AtomicInteger(order.size());
            final AtomicInteger streaming = new AtomicInteger(0);

            for (int i : order) {
                final String id = ids[i];
                try {
                    CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                    Integer facingBoxed = ch.get(CameraCharacteristics.LENS_FACING);
                    final int facing = facingBoxed == null ? -1 : facingBoxed;
                    Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    // 首选 JPEG 直出，其次 YUV 软压缩
                    Size jpegSize = pickSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG), wantWidth, wantHeight);
                    final boolean jpegDirect = jpegSize != null;
                    Size yuvSize = pickSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888), wantWidth, wantHeight);
                    Size useSize = jpegDirect ? jpegSize : yuvSize;
                    if (useSize == null) {
                        pending.decrementAndGet();
                        checkAllFailed(pending, streaming, onFailure);
                        continue;
                    }

                    final int index = i;
                    final Cam cam = new Cam(index, id, labelOf(facing, i), facing,
                            so == null ? 0 : so, jpegDirect,
                            ImageReader.newInstance(useSize.getWidth(), useSize.getHeight(),
                                    jpegDirect ? ImageFormat.JPEG : ImageFormat.YUV_420_888, 3));
                    synchronized (CAMS) {
                        CAMS.add(cam);
                    }
                    cam.reader.setOnImageAvailableListener(im -> {
                        Image image = null;
                        try {
                            image = im.acquireLatestImage();
                            if (image == null) {
                                return;
                            }
                            byte[] jpeg;
                            if (cam.jpegDirect) {
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                jpeg = new byte[buffer.remaining()];
                                buffer.get(jpeg);
                                jpeg = normalizeJpeg(jpeg);
                            } else {
                                jpeg = yuvToJpeg(image);
                            }
                            if (jpeg != null && jpeg.length > 4) {
                                MjpegServer.publish(jpeg, cam.index);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (image != null) {
                                image.close();
                            }
                        }
                    }, handler);

                    manager.openCamera(id, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            cam.device = device;
                            try {
                                device.createCaptureSession(Arrays.asList(cam.reader.getSurface()),
                                        new CameraCaptureSession.StateCallback() {
                                            @Override
                                            public void onConfigured(CameraCaptureSession configured) {
                                                cam.session = configured;
                                                applyRepeating(cam);
                                                cam.streaming = true;
                                                MjpegServer.registerCamera(cam.index, cam.label);
                                                cooldownUntil = System.currentTimeMillis() + 8000;
                                                if (streaming.incrementAndGet() == 1) {
                                                    running = true;
                                                    // 跟随设备摆放动态转正画面（平放保持上次姿态）
                                                    if (orientation == null) {
                                                        orientation = DeviceOrientation.start(context,
                                                                deg -> handler.post(() -> {
                                                                    synchronized (CAMS) {
                                                                        for (Cam c : CAMS) {
                                                                            applyRepeating(c);
                                                                        }
                                                                    }
                                                                }));
                                                    }
                                                    if (onReady != null) {
                                                        onReady.run();
                                                    }
                                                }
                                            }

                                            @Override
                                            public void onConfigureFailed(CameraCaptureSession s) {
                                                closeCam(cam);
                                                pending.decrementAndGet();
                                                checkAllFailed(pending, streaming, onFailure);
                                            }
                                        }, handler);
                            } catch (Exception e) {
                                closeCam(cam);
                                pending.decrementAndGet();
                                                checkAllFailed(pending, streaming, onFailure);
                            }
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            if (cam.streaming && running) {
                                // 运行中被系统/高优先级客户端踢出：自愈重开
                                android.util.Log.w(TAG, "camera " + cam.index + " disconnected, healing");
                                reopenCam(cam);
                                return;
                            }
                            closeCam(cam);
                            pending.decrementAndGet();
                            checkAllFailed(pending, streaming, onFailure);
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            android.util.Log.e(TAG, "camera " + cam.index + " error: " + error);
                            if (cam.streaming && running) {
                                reopenCam(cam);
                                return;
                            }
                            closeCam(cam);
                            pending.decrementAndGet();
                                                checkAllFailed(pending, streaming, onFailure);
                        }
                    }, handler);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "open camera " + i + " failed", e);
                    pending.decrementAndGet();
                    checkAllFailed(pending, streaming, onFailure);
                }
            }
            checkAllFailed(pending, streaming, onFailure);
            appContext = context.getApplicationContext();
            cooldownUntil = System.currentTimeMillis() + 10000;
            startWatchdog();
            return true;
        } catch (Exception e) {
            android.util.Log.e(TAG, "builtin camera start failed", e);
            return false;
        }
    }

    /** 帧停滞看门狗：每秒巡检各路帧数，>6 秒不前进重开该路，连续 3 次整体重启 */
    private static void startWatchdog() {
        if (watchdog != null && watchdog.isAlive()) {
            return;
        }
        watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now < cooldownUntil || !running) {
                    continue;
                }
                List<Cam> snapshot;
                synchronized (CAMS) {
                    snapshot = new ArrayList<>(CAMS);
                }
                for (Cam cam : snapshot) {
                    if (!cam.streaming) {
                        continue;
                    }
                    long frames = MjpegServer.frameCount(cam.index);
                    Long last = LAST_FRAMES.get(cam.index);
                    if (last == null || frames > last) {
                        LAST_FRAMES.put(cam.index, frames);
                        LAST_ADVANCE.put(cam.index, now);
                        STALL_COUNT.put(cam.index, 0);
                        continue;
                    }
                    Long advance = LAST_ADVANCE.get(cam.index);
                    if (advance == null || advance <= 0) {
                        LAST_ADVANCE.put(cam.index, now);
                        continue;
                    }
                    if (now - advance > 6000) {
                        LAST_ADVANCE.put(cam.index, now);
                        Integer stalls = STALL_COUNT.get(cam.index);
                        int count = (stalls == null ? 0 : stalls) + 1;
                        STALL_COUNT.put(cam.index, count);
                        // 冷却期：等待重开/重启生效，避免连续触发
                        cooldownUntil = now + 12000;
                        if (count >= 3) {
                            STALL_COUNT.put(cam.index, 0);
                            android.util.Log.w(TAG, "camera " + cam.index + " repeated stall, full restart");
                            if (appContext != null) {
                                StreamService.restart(appContext);
                            }
                            return;
                        }
                        android.util.Log.w(TAG, "camera " + cam.index + " frame stall, reopening");
                        reopenCam(cam);
                    }
                }
            }
        }, "cam-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 重开单路相机会话（熄屏唤醒 / HAL 卡死 / 被系统高优先级客户端踢出后自愈） */
    private static void reopenCam(final Cam cam) {
        final android.content.Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return;
        }
        closeCam(cam);
        // 重新入册：closeCam 已将其移出，重开成功后看门狗/方向回调才能继续覆盖这路
        synchronized (CAMS) {
            CAMS.add(cam);
        }
        int live = 0;
        synchronized (CAMS) {
            for (Cam c : CAMS) {
                if (c.streaming) {
                    live++;
                }
            }
        }
        final AtomicInteger streamingCount = new AtomicInteger(live);
        final AtomicInteger pending = new AtomicInteger(1);
        try {
            manager.openCamera(cam.id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cam.device = device;
                    cam.reopenFailures = 0;
                    try {
                        device.createCaptureSession(Arrays.asList(cam.reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession configured) {
                                        cam.session = configured;
                                        applyRepeating(cam);
                                        cam.streaming = true;
                                        MjpegServer.registerCamera(cam.index, cam.label);
                                        cooldownUntil = System.currentTimeMillis() + 8000;
                                        streamingCount.incrementAndGet();
                                        android.util.Log.i(TAG, "camera " + cam.index + " reopened (heal)");
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        pending.decrementAndGet();
                                        healFailed(cam, pending, streamingCount);
                                    }
                                }, handler);
                    } catch (Exception e) {
                        pending.decrementAndGet();
                        healFailed(cam, pending, streamingCount);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    healFailed(cam, pending, streamingCount);
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    android.util.Log.e(TAG, "reopen camera " + cam.index + " error: " + error);
                    healFailed(cam, pending, streamingCount);
                }
            }, handler);
        } catch (Exception e) {
            android.util.Log.e(TAG, "reopen camera " + cam.index + " failed", e);
            pending.decrementAndGet();
            healFailed(cam, pending, streamingCount);
        }
    }

    /** 单路重开失败：连续 3 次升级为整体重启，否则稍后由看门狗再触发 */
    private static void healFailed(Cam cam, AtomicInteger pending, AtomicInteger streamingCount) {
        closeCam(cam);
        cam.reopenFailures++;
        android.util.Log.w(TAG, "camera " + cam.index + " heal failed (" + cam.reopenFailures + ")");
        if (cam.reopenFailures >= 3) {
            cam.reopenFailures = 0;
            fullRestart();
            return;
        }
        cooldownUntil = System.currentTimeMillis() + 10000;
        checkAllFailed(pending, streamingCount, () -> fullRestart());
    }

    private static void fullRestart() {
        android.util.Log.e(TAG, "no camera alive after heal attempts, full pipeline restart");
        if (appContext != null) {
            StreamService.restartPipeline(appContext);
        }
    }

    /** 全部相机都尝试完且一路都没起来时才算失败 */
    private static void checkAllFailed(AtomicInteger pending, AtomicInteger streaming, Runnable onFailure) {
        if (pending.get() <= 0 && streaming.get() == 0 && onFailure != null) {
            onFailure.run();
        }
    }

    private static String labelOf(int facing, int index) {
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_BACK:
                return "后置";
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "前置";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "外接";
            default:
                return "相机" + index;
        }
    }

    /**
     * 按当前设备姿态重建重复请求：JPEG_ORIENTATION 由 HAL 直接转正像素，
     * 快照/MJPEG/RTSP 各消费端无需感知 EXIF 方向。
     * 前摄与后摄的换算方向相反（前摄镜像），映射以后摄 orientation=90 实测校准。
     */
    private static void applyRepeating(Cam cam) {
        CameraCaptureSession s = cam.session;
        if (s == null || cam.device == null) {
            return;
        }
        try {
            int suggest = orientation == null ? 90 : orientation.getSuggest();
            int display = displayRotationOf(suggest);
            int jpegOrientation = cam.facing == CameraCharacteristics.LENS_FACING_FRONT
                    ? (cam.sensorOrientation + display) % 360
                    : (cam.sensorOrientation - display + 360) % 360;
            CaptureRequest.Builder builder = cam.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            builder.addTarget(cam.reader.getSurface());
            s.setRepeatingRequest(builder.build(), null, handler);
        } catch (Exception e) {
            android.util.Log.e(TAG, "apply repeating request failed", e);
        }
    }

    /** 传感器建议角 → 屏幕旋转角（以竖屏为自然方向的设备） */
    private static int displayRotationOf(int suggestDeg) {
        switch (suggestDeg) {
            case 0:
                return 90;
            case 180:
                return 270;
            case 270:
                return 180;
            case 90:
            default:
                return 0;
        }
    }

    /** JPEG 扫描段里是否存在 DRI 重启标记（RTP-JPEG 客户端普遍不支持带重启标记的帧） */
    private static boolean hasRestartMarkers(byte[] j) {
        int i = 2; // 跳过 SOI
        while (i + 4 <= j.length) {
            if (j[i] != (byte) 0xFF) {
                i++;
                continue;
            }
            int marker = j[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return false; // EOI 或到达扫描数据：DRI 必在其前
            }
            if (marker == 0xDD) {
                return true;
            }
            int segLen = ((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF);
            if (segLen < 2 || i + 2 + segLen > j.length) {
                return false;
            }
            i += 2 + segLen;
        }
        return false;
    }

    /** 归一化：无 DRI 直通（零开销）；带 DRI 则重编码（同时去掉 EXIF、统一标准 Huffman 表） */
    private static byte[] normalizeJpeg(byte[] jpeg) {
        if (!hasRestartMarkers(jpeg)) {
            return jpeg;
        }
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Size pickSize(Size[] sizes, int wantWidth, int wantHeight) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Arrays.sort(sizes, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                int da = Math.abs(a.getWidth() - wantWidth) + Math.abs(a.getHeight() - wantHeight);
                int db = Math.abs(b.getWidth() - wantWidth) + Math.abs(b.getHeight() - wantHeight);
                return da - db;
            }
        });
        return sizes[0];
    }

    /** YUV_420_888 转 JPEG：组装 NV21 后软压缩（每帧 ~10-20ms） */
    private static byte[] yuvToJpeg(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];
        ByteBuffer yPlane = image.getPlanes()[0].getBuffer();
        yPlane.get(nv21, 0, width * height);
        ByteBuffer uPlane = image.getPlanes()[1].getBuffer();
        ByteBuffer vPlane = image.getPlanes()[2].getBuffer();
        int uvSize = width * height / 4;
        byte[] u = new byte[uvSize];
        byte[] v = new byte[uvSize];
        uPlane.get(u);
        vPlane.get(v);
        int pos = width * height;
        for (int i = 0; i < uvSize; i++) {
            nv21[pos++] = v[i];
            nv21[pos++] = u[i];
        }
        android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 80, out);
        return out.toByteArray();
    }

    private static void closeCam(Cam cam) {
        cam.streaming = false;
        MjpegServer.unregisterCamera(cam.index);
        if (cam.session != null) {
            try {
                cam.session.close();
            } catch (Exception ignored) {
            }
            cam.session = null;
        }
        if (cam.device != null) {
            try {
                cam.device.close();
            } catch (Exception ignored) {
            }
            cam.device = null;
        }
        if (cam.reader != null) {
            try {
                cam.reader.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (CAMS) {
            CAMS.remove(cam);
        }
    }

    public static synchronized void stop() {
        running = false;
        if (watchdog != null) {
            watchdog.interrupt();
            watchdog = null;
        }
        LAST_FRAMES.clear();
        LAST_ADVANCE.clear();
        STALL_COUNT.clear();
        if (orientation != null) {
            orientation.stop();
            orientation = null;
        }
        synchronized (CAMS) {
            for (Cam cam : new ArrayList<>(CAMS)) {
                closeCam(cam);
            }
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }
}
