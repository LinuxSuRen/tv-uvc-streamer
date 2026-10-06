package com.linuxsuren.tvuvc;

import android.content.Context;
import android.content.Intent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 MJPEG HTTP 服务（多摄像头）。
 * <p>
 * 端点（cam 参数缺省为 0 号摄像头，单摄设备用法不变）：
 * <ul>
 *   <li>/             状态页（列出全部摄像头与入口）</li>
 *   <li>/snapshot.jpg?cam=N 最新单帧</li>
 *   <li>/stream?cam=N       multipart/x-mixed-replace MJPEG 流</li>
 * </ul>
 */
public final class MjpegServer {

    /** 帧订阅者（RTSP 等其他服务消费同一路帧流，含摄像头编号） */
    public interface FrameListener {
        void onFrame(byte[] frame, int camera, long frameIndex);
    }

    private static final List<FrameListener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(FrameListener listener) {
        // 幂等：服务每次启动都会注册 RTSP 广播监听器，静态列表跨重启存活，
        // 重复注册会导致每帧被多次推流（RTP 序列错乱、流量翻倍）
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(FrameListener listener) {
        LISTENERS.remove(listener);
    }

    public static final int PORT = 8090;
    private static final String BOUNDARY = "tvuvcframe";

    /** 每路摄像头的最新帧、名称与帧计数；cam 0 兼容单摄/UVC 场景 */
    private static final Map<Integer, byte[]> LATEST = new HashMap<>();
    private static final Map<Integer, String> LABELS = new HashMap<>();
    private static final Map<Integer, Long> COUNTS = new HashMap<>();
    private static final Object LOCK = new Object();
    private static long totalFrames;
    private static long lastIndex;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;

    private MjpegServer() {
    }

    /** 采集层注册摄像头（会话建立后调用），供状态页与 ONVIF 枚举 */
    public static void registerCamera(int camera, String label) {
        synchronized (LOCK) {
            LABELS.put(camera, label == null ? ("camera " + camera) : label);
        }
    }

    /** 相机关闭（切换/停止）时反注册：ONVIF 只应广播真实在推流的相机，陈旧帧一并清理 */
    public static void unregisterCamera(int camera) {
        synchronized (LOCK) {
            LABELS.remove(camera);
            LATEST.remove(camera);
            COUNTS.remove(camera);
        }
    }

    /** 当前在推流的摄像头（编号 → 名称），快照拷贝 */
    public static Map<Integer, String> cameras() {
        synchronized (LOCK) {
            return new HashMap<>(LABELS);
        }
    }

    public static synchronized void start() throws IOException {
        if (acceptThread != null && acceptThread.isAlive()) {
            return;
        }
        // accept 线程持局部引用：stop() 将静态字段置 null 时不会读到半空状态（实测 NPE 崩溃）
        final ServerSocket socket = new ServerSocket(PORT);
        serverSocket = socket;
        acceptThread = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    Thread t = new Thread(() -> serve(client));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException ignored) {
                    // serverSocket 关闭时退出循环
                }
            }
        }, "mjpeg-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static synchronized void stop() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
    }

    /** 采集层发布一帧（兼容入口：0 号摄像头） */
    public static void publish(byte[] frame) {
        publish(frame, 0);
    }

    /** 采集层发布一帧（指定摄像头编号） */
    public static void publish(byte[] frame, int camera) {
        if (frame == null || frame.length < 2) {
            return;
        }
        long index;
        synchronized (LOCK) {
            index = ++lastIndex;
            LATEST.put(camera, frame);
            Long count = COUNTS.get(camera);
            COUNTS.put(camera, count == null ? 1L : count + 1);
            totalFrames++;
            LOCK.notifyAll();
        }
        for (FrameListener l : LISTENERS) {
            l.onFrame(frame, camera, index);
        }
    }

    /** 全部摄像头累计帧数（界面展示用） */
    public static long frameCount() {
        synchronized (LOCK) {
            return totalFrames;
        }
    }

    /** 供自愈任务调用：服务未运行时直接拉起采集服务。 */
    public static void ensureRunning(Context context) {
        if (serverSocket == null) {
            try {
                StreamService.start(context);
            } catch (IllegalStateException ignored) {
                // 极端后台限制场景；下个周期会重试
            }
        }
    }

    /** 取本机局域网 IPv4 地址，用于展示访问入口。 */
    public static String lanAddress() {
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return "0.0.0.0";
    }

    private static void serve(Socket client) {
        try {
            client.setTcpNoDelay(true);
            String path = readRequestPath(client.getInputStream());
            if (path == null) {
                client.close();
                return;
            }
            int camera = cameraOf(path);
            OutputStream out = client.getOutputStream();
            if (path.startsWith("/snapshot.jpg")) {
                writeSnapshot(out, camera);
            } else if (path.startsWith("/stream")) {
                writeStream(out, camera);
            } else {
                writeStatusPage(out);
            }
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 从 "/snapshot.jpg?cam=1" 之类路径解析摄像头编号（缺省 0） */
    static int cameraOf(String path) {
        if (path == null) {
            return 0;
        }
        int q = path.indexOf('?');
        if (q < 0) {
            return 0;
        }
        for (String kv : path.substring(q + 1).split("&")) {
            if (kv.startsWith("cam=")) {
                try {
                    return Integer.parseInt(kv.substring(4).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String readRequestPath(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        int nl = 0;
        // 读到空行（头部结束）或超长即止
        while (sb.length() < 8192 && nl < 2 && (c = in.read()) != -1) {
            sb.append((char) c);
            if (c == '\n') {
                nl++;
            } else if (c != '\r') {
                nl = 0;
            }
        }
        String first = sb.length() > 0 ? sb.substring(0, sb.indexOf("\r\n") > 0 ? sb.indexOf("\r\n") : sb.length()) : "";
        // 形如 GET /stream HTTP/1.1
        String[] parts = first.split(" ");
        if (parts.length >= 2) {
            return parts[1];
        }
        return "/";
    }

    private static byte[] latestFrame(int camera) throws IOException {
        synchronized (LOCK) {
            if (!LATEST.containsKey(camera)) {
                try {
                    LOCK.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return LATEST.get(camera);
        }
    }

    private static void writeSnapshot(OutputStream out, int camera) throws IOException {
        byte[] frame = latestFrame(camera);
        if (frame == null) {
            out.write(("HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return;
        }
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + frame.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(frame);
    }

    private static void writeStream(OutputStream out, int camera) throws IOException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary="
                + BOUNDARY + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        while (!Thread.currentThread().isInterrupted()) {
            byte[] frame = latestFrame(camera);
            if (frame == null) {
                continue;
            }
            StringBuilder part = new StringBuilder();
            part.append("--").append(BOUNDARY).append("\r\n");
            part.append("Content-Type: image/jpeg\r\n");
            part.append("Content-Length: ").append(frame.length).append("\r\n\r\n");
            out.write(part.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(frame);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private static void writeStatusPage(OutputStream out) throws IOException {
        String ip = lanAddress();
        Map<Integer, String> cams = cameras();
        StringBuilder body = new StringBuilder();
        body.append("<html><head><meta charset='utf-8'><title>tv-uvc-streamer</title></head>")
                .append("<body style='font-family:monospace;background:#111;color:#eee;padding:2em'>")
                .append("<h2>tv-uvc-streamer</h2>")
                .append("<p>device: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL)
                .append(" (Android ").append(android.os.Build.VERSION.RELEASE).append(")</p>");
        if (cams.isEmpty()) {
            body.append("<p>frames: ").append(totalFrames).append("</p>");
        }
        for (Map.Entry<Integer, String> e : cams.entrySet()) {
            int cam = e.getKey();
            String suffix = cam == 0 ? "" : "?cam=" + cam;
            body.append("<h3>").append(e.getValue()).append(" (camera ").append(cam).append(")</h3>")
                    .append("<p><a style='color:#8cf' href='http://").append(ip).append(':').append(PORT)
                    .append("/stream").append(suffix).append("'>/stream").append(suffix).append("</a>")
                    .append(" · <a style='color:#8cf' href='http://").append(ip).append(':').append(PORT)
                    .append("/snapshot.jpg").append(suffix).append("'>/snapshot.jpg").append(suffix).append("</a>")
                    .append("</p><p><img src='/snapshot.jpg").append(suffix).append("' width='480'></p>");
        }
        body.append("</body></html>");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + bytes.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
    }

    static String url() {
        return String.format(Locale.US, "http://%s:%d/", lanAddress(), PORT);
    }
}
