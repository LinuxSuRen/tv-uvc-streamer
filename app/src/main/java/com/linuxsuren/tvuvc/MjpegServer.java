package com.linuxsuren.tvuvc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 MJPEG HTTP 服务。
 * <p>
 * 端点：
 * <ul>
 *   <li>/             状态页</li>
 *   <li>/snapshot.jpg 最新单帧</li>
 *   <li>/stream       multipart/x-mixed-replace MJPEG 流</li>
 * </ul>
 */
public final class MjpegServer {

    public static final int PORT = 8090;
    private static final String BOUNDARY = "tvuvcframe";

    private static volatile byte[] latest;
    private static final Object LOCK = new Object();
    private static final AtomicLong FRAME_COUNT = new AtomicLong();
    private static ServerSocket serverSocket;
    private static Thread acceptThread;

    private MjpegServer() {
    }

    public static synchronized void start() throws IOException {
        if (acceptThread != null && acceptThread.isAlive()) {
            return;
        }
        serverSocket = new ServerSocket(PORT);
        acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
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

    /** 采集层发布一帧。 */
    public static void publish(byte[] frame) {
        if (frame == null || frame.length < 2) {
            return;
        }
        FRAME_COUNT.incrementAndGet();
        synchronized (LOCK) {
            latest = frame;
            LOCK.notifyAll();
        }
    }

    public static long frameCount() {
        return FRAME_COUNT.get();
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
            OutputStream out = client.getOutputStream();
            if (path.startsWith("/snapshot.jpg")) {
                writeSnapshot(out);
            } else if (path.startsWith("/stream")) {
                writeStream(out);
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

    private static byte[] latestFrame() throws IOException {
        synchronized (LOCK) {
            if (latest == null) {
                try {
                    LOCK.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return latest;
        }
    }

    private static void writeSnapshot(OutputStream out) throws IOException {
        byte[] frame = latestFrame();
        if (frame == null) {
            out.write(("HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            return;
        }
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                + frame.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(frame);
    }

    private static void writeStream(OutputStream out) throws IOException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace;boundary="
                + BOUNDARY + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        while (!Thread.currentThread().isInterrupted()) {
            byte[] frame = latestFrame();
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
        long frames = FRAME_COUNT.get();
        String html = "<html><head><meta charset='utf-8'><title>tv-uvc-streamer</title></head>"
                + "<body style='font-family:monospace;background:#111;color:#eee;padding:2em'>"
                + "<h2>tv-uvc-streamer</h2>"
                + "<p>frames: " + frames + "</p>"
                + "<p><a style='color:#8cf' href='http://" + ip + ":" + PORT + "/stream'>/stream</a> MJPEG 流</p>"
                + "<p><a style='color:#8cf' href='http://" + ip + ":" + PORT + "/snapshot.jpg'>/snapshot.jpg</a> 单帧快照</p>"
                + "<p><img src='/snapshot.jpg' width='640'></p>"
                + "</body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    static String url() {
        return String.format(Locale.US, "http://%s:%d/", lanAddress(), PORT);
    }
}
