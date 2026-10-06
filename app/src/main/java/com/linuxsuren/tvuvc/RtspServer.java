package com.linuxsuren.tvuvc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简 RTSP 服务（MJPEG over RTP，RFC 2435）。
 * <p>
 * 地址：rtsp://&lt;电视IP&gt;:8554/cam
 * 仅实现单播：RTSP over TCP 控制 + RTP over UDP 传输；FFmpeg 系客户端（VLC/ffmpeg/OpenCV/Frigate）可拉。
 */
public final class RtspServer {

    public static final int PORT = 8554;
    private static final byte PAYLOAD_TYPE = 26; // 静态载荷类型 26 = JPEG
    private static final int CLOCK = 90000;
    private static final int MAX_PACKET = 1400;  // 单包载荷上限（MTU 安全值）

    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static final List<Client> CLIENTS = new CopyOnWriteArrayList<>();

    private RtspServer() {
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
                }
            }
        }, "rtsp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        MjpegServer.addListener(RtspServer::broadcastFrame);
    }

    public static synchronized void stop() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (Client c : CLIENTS) {
            c.close();
        }
        CLIENTS.clear();
    }

    static String url() {
        return String.format(Locale.US, "rtsp://%s:%d/cam", MjpegServer.lanAddress(), PORT);
    }

    // ---------------- RTSP 会话 ----------------

    private static void serve(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();
            String session = null;
            Client client = null;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(" ");
                String method = parts[0];
                // /cam（兼容 cam0）与 /camN 对应第 N 路摄像头
                int camera = cameraOfUrl(parts.length > 1 ? parts[1] : "");
                // 读掉该请求剩余头部（CSeq 必须原样回显，ffmpeg 等客户端校验不匹配会断连）
                String cseq = null;
                String transport = null;
                String h;
                while ((h = in.readLine()) != null && !h.isEmpty()) {
                    String lower = h.toLowerCase(Locale.US);
                    if (lower.startsWith("cseq:")) {
                        cseq = h.substring(h.indexOf(':') + 1).trim();
                    } else if (lower.startsWith("transport:")) {
                        transport = h.substring(h.indexOf(':') + 1).trim();
                    }
                }
                if (cseq == null) {
                    cseq = "0";
                }
                switch (method) {
                    case "OPTIONS":
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                + "\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n");
                        break;
                    case "DESCRIBE":
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                + "\r\nContent-Type: application/sdp\r\nContent-Length: "
                                + sdp().length() + "\r\n\r\n" + sdp());
                        break;
                    case "SETUP":
                        session = Integer.toHexString(new Random().nextInt(0x7FFFFFFF));
                        client = new Client(socket.getInetAddress(), transport, out, camera);
                        CLIENTS.add(client);
                        synchronized (client.lock) {
                            write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                    + "\r\nSession: " + session
                                    + "\r\nTransport: " + client.transportReply
                                    + "\r\n\r\n");
                        }
                        break;
                    case "PLAY":
                        if (client != null) {
                            client.playing = true;
                            if (client.tcp != null) {
                                // interleaved 客户端长连接上可能长时间无请求，关掉读超时避免误杀
                                socket.setSoTimeout(0);
                            }
                            synchronized (client.lock) {
                                write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                        + "\r\nSession: " + session
                                        + "\r\nRTP-Info: url=" + url() + ";seq=0;rtptime=0"
                                        + "\r\nRange: npt=0-\r\n\r\n");
                            }
                        } else {
                            write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                    + "\r\nSession: " + session
                                    + "\r\nRTP-Info: url=" + url() + ";seq=0;rtptime=0"
                                    + "\r\nRange: npt=0-\r\n\r\n");
                        }
                        break;
                    case "TEARDOWN":
                        if (client != null) {
                            CLIENTS.remove(client);
                            client.close();
                        }
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseq
                                + "\r\nSession: " + session + "\r\n\r\n");
                        return;
                    default:
                        write(out, "RTSP/1.0 501 Not Implemented\r\nCSeq: " + cseq + "\r\n\r\n");
                }
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String sdp() {
        return "v=0\r\n"
                + "o=- 0 0 IN IP4 " + MjpegServer.lanAddress() + "\r\n"
                + "s=tv-uvc-streamer\r\n"
                + "m=video 0 RTP/AVP " + PAYLOAD_TYPE + "\r\n"
                + "c=IN IP4 0.0.0.0\r\n"
                + "a=control:" + url() + "\r\n"
                + "a=rtpmap:" + PAYLOAD_TYPE + " JPEG/90000\r\n"
                + "a=framerate:25\r\n";
    }

    private static void write(OutputStream out, String data) throws IOException {
        out.write(data.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** 从请求行 URL（rtsp://host:port/cam1 或 /cam1）解析摄像头编号，未识别为 0 */
    static int cameraOfUrl(String url) {
        if (url == null || url.isEmpty()) {
            return 0;
        }
        int pathStart = url.indexOf("://");
        String path = pathStart >= 0 ? url.substring(pathStart + 3) : url;
        int slash = path.indexOf('/');
        path = slash >= 0 ? path.substring(slash + 1) : "";
        // 去掉查询串
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (path.startsWith("cam") && path.length() > 3) {
            try {
                return Integer.parseInt(path.substring(3));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0; // /cam 及其他路径兼容 0 号摄像头
    }

    // ---------------- RTP 发送 ----------------

    /** 只向订阅了对应摄像头的客户端分发 */
    private static void broadcastFrame(byte[] frame, int camera, long frameIndex) {
        for (Client c : CLIENTS) {
            if (c.camera == camera && c.playing) {
                c.sendFrame(frame, frameIndex);
            }
        }
    }

    private static final class Client {
        final InetAddress address;
        final int camera; // 订阅的摄像头编号
        final int rtpPort;
        final int rtcpPort;
        final DatagramSocket socket;  // UDP 模式使用；interleaved 模式为 null
        final OutputStream tcp;       // interleaved 模式：RTSP 同连接回传 RTP；UDP 模式为 null
        final int interleave;         // interleaved 通道号
        final String transportReply;  // SETUP 应答的 Transport 头
        final Object lock = new Object(); // RTSP 应答与 RTP 数据同 socket 写出，需互斥
        final int ssrc;
        volatile boolean playing;
        int sequence = 0;

        Client(InetAddress address, String transport, OutputStream tcpSink, int cameraIndex) throws IOException {
            this.address = address;
            this.camera = cameraIndex;
            this.ssrc = new Random().nextInt();
            Integer channel = parseInterleaved(transport);
            if (channel != null && tcpSink != null) {
                // RTP over TCP（interleaved）：数据经 "$" 帧走 RTSP 连接，地址/端口无意义
                this.tcp = tcpSink;
                this.interleave = channel;
                this.socket = null;
                this.rtpPort = 0;
                this.rtcpPort = 0;
                this.transportReply = transport;
            } else {
                this.tcp = null;
                this.interleave = -1;
                int[] ports = parseClientPorts(transport);
                this.rtpPort = ports[0];
                this.rtcpPort = ports[1];
                this.socket = new DatagramSocket();
                this.transportReply = transport + ";server_port=" + rtpPort + "-" + (rtpPort + 1);
            }
        }

        private static Integer parseInterleaved(String transport) {
            if (transport == null) {
                return null;
            }
            int i = transport.indexOf("interleaved=");
            if (i < 0) {
                return null;
            }
            try {
                return Integer.parseInt(transport.substring(i + "interleaved=".length()).split("[;-]")[0].trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static int[] parseClientPorts(String transport) {
            int rtp = 5000, rtcp = 5001;
            if (transport != null) {
                int i = transport.indexOf("client_port=");
                if (i >= 0) {
                    String rest = transport.substring(i + "client_port=".length());
                    String[] seg = rest.split("[;-]");
                    try {
                        rtp = Integer.parseInt(seg[0].trim());
                        rtcp = seg.length > 1 ? Integer.parseInt(seg[1].trim()) : rtp + 1;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return new int[]{rtp, rtcp};
        }

        /** 按 RFC 2435 分帧发送。JPEG 主头：[type-specific|fragment-offset(3B)|type|q|w/8|h/8]。 */
        void sendFrame(byte[] jpeg, long frameIndex) {
            if (jpeg.length < 4 || jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) 0xD8) {
                return;
            }
            ParsedJpeg p = parseJpeg(jpeg);
            if (p == null) {
                return;
            }
            // RFC 2435 type：0 = 4:2:2，1 = 4:2:0（由 SOF 首分量采样因子决定）
            // 注：type 64+（重启标记）ffmpeg rtpdec_jpeg 不支持（Unimplemented），直接丢弃；
            // 发布层已对含 DRI 的帧重编码归一化，正常不会走到这里
            if (p.restartInterval > 0) {
                return;
            }
            int type = p.sampling == 0x21 ? 0 : 1;
            int timestamp = (int) ((frameIndex * CLOCK / 25) & 0xFFFFFFFFL);
            // 首片额外携带 4 字节量化头 + 量化表；其余分片仅 20 字节开销
            int maxChunk = MAX_PACKET - 12 - 8;
            if (maxChunk < 256) {
                return;
            }
            int scanLen = p.scanEnd - p.scanStart;
            int chunks = (scanLen + maxChunk - 1) / maxChunk;
            if (chunks < 1 || chunks > 60000) {
                return;
            }
            int off = 0; // 当前分片在扫描数据中的字节偏移（写入 24 位分片偏移字段）
            try {
                for (int n = 0; n < chunks; n++) {
                    int len = Math.min(maxChunk, scanLen - off);
                    boolean first = (n == 0);
                    boolean last = (n == chunks - 1);
                    int headLen = first ? 4 + p.tables.length : 0;
                    byte[] pkt = new byte[12 + 8 + headLen + len];
                    pkt[0] = (byte) 0x80;
                    pkt[1] = (byte) (PAYLOAD_TYPE | (last ? 0x80 : 0)); // M 位仅末片（帧完成信号）
                    pkt[2] = (byte) ((sequence >> 8) & 0xFF);
                    pkt[3] = (byte) (sequence & 0xFF);
                    pkt[4] = (byte) ((timestamp >> 24) & 0xFF);
                    pkt[5] = (byte) ((timestamp >> 16) & 0xFF);
                    pkt[6] = (byte) ((timestamp >> 8) & 0xFF);
                    pkt[7] = (byte) (timestamp & 0xFF);
                    pkt[8] = (byte) ((ssrc >> 24) & 0xFF);
                    pkt[9] = (byte) ((ssrc >> 16) & 0xFF);
                    pkt[10] = (byte) ((ssrc >> 8) & 0xFF);
                    pkt[11] = (byte) (ssrc & 0xFF);
                    // RFC 2435 主 JPEG 头（ffmpeg 布局：偏移在字节 1-3，type 在字节 4）
                    pkt[12] = 0;                                   // type-specific
                    pkt[13] = (byte) ((off >> 16) & 0xFF);         // fragment offset 高
                    pkt[14] = (byte) ((off >> 8) & 0xFF);          // fragment offset 中
                    pkt[15] = (byte) (off & 0xFF);                 // fragment offset 低
                    pkt[16] = (byte) type;                         // 0=4:2:2，1=4:2:0
                    pkt[17] = (byte) 255;                          // 质量 255 = 携带量化表
                    pkt[18] = (byte) (p.width / 8);                // 宽/8（来自 SOF）
                    pkt[19] = (byte) (p.height / 8);               // 高/8
                    int pos = 20;
                    if (first) {
                        // 量化表头：MBZ、精度（8bit=0）、表长度
                        pkt[20] = 0;
                        pkt[21] = 0;
                        pkt[22] = (byte) ((p.tables.length >> 8) & 0xFF);
                        pkt[23] = (byte) (p.tables.length & 0xFF);
                        System.arraycopy(p.tables, 0, pkt, 24, p.tables.length);
                        pos = 24 + p.tables.length;
                    }
                    System.arraycopy(jpeg, p.scanStart + off, pkt, pos, len);
                    if (tcp != null) {
                        // interleaved：4 字节帧头 '$' + 通道 + 长度，走 RTSP 连接
                        byte[] framed = new byte[4 + pkt.length];
                        framed[0] = '$';
                        framed[1] = (byte) interleave;
                        framed[2] = (byte) ((pkt.length >> 8) & 0xFF);
                        framed[3] = (byte) (pkt.length & 0xFF);
                        System.arraycopy(pkt, 0, framed, 4, pkt.length);
                        synchronized (lock) {
                            tcp.write(framed);
                            tcp.flush();
                        }
                    } else {
                        socket.send(new DatagramPacket(pkt, pkt.length, address, rtpPort));
                    }
                    sequence = (sequence + 1) & 0xFFFF;
                    off += len;
                }
            } catch (IOException ignored) {
                close();
                CLIENTS.remove(this);
            }
        }

        void close() {
            playing = false;
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            // interleaved 模式的 socket 归属 RTSP 会话线程，不在此关闭
        }
    }

    /** JPEG 帧解析结果：量化表、扫描数据区间、SOF 尺寸、Y 采样因子、重启间隔 */
    private static final class ParsedJpeg {
        final byte[] tables;
        final int scanStart;
        final int scanEnd;
        final int width;
        final int height;
        final int sampling; // 高 4 位水平采样、低 4 位垂直采样
        final int restartInterval; // DRI 重启间隔（MCU 数），0 = 无重启标记

        ParsedJpeg(byte[] tables, int scanStart, int scanEnd, int width, int height, int sampling, int restartInterval) {
            this.tables = tables;
            this.scanStart = scanStart;
            this.scanEnd = scanEnd;
            this.width = width;
            this.height = height;
            this.sampling = sampling;
            this.restartInterval = restartInterval;
        }
    }

    /**
     * 解析 JPEG：收集全部 DQT 段（FFDB）并去掉每张表 1 字节的表 ID（ffmpeg 要求
     * 量化表为 64 字节裸值的连续序列，两张表即 128 字节）；SOF 段（FFC0/C1/C2）
     * 提取真实宽高与采样因子（决定 RFC 2435 的 type 与 w/8、h/8 字段，禁止写死）；
     * 定位 SOS（FFDA）段之后的熵编码扫描数据起点；结尾去掉 EOI（FFD9）。
     */
    private static ParsedJpeg parseJpeg(byte[] j) {
        java.io.ByteArrayOutputStream rawTables = new java.io.ByteArrayOutputStream();
        int scanStart = -1;
        int width = 0;
        int height = 0;
        int sampling = 0x22; // 默认 4:2:0
        int restartInterval = 0;
        int i = 2; // 跳过 SOI
        while (i + 4 <= j.length) {
            if (j[i] != (byte) 0xFF) {
                i++;
                continue;
            }
            int marker = j[i + 1] & 0xFF;
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
                i += 2;
                continue;
            }
            if (marker == 0xD9) { // EOI
                break;
            }
            int segLen = ((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF);
            if (segLen < 2 || i + 2 + segLen > j.length) {
                return null;
            }
            if (marker == 0xDB) { // DQT：段内为若干 1+64 字节表记录
                int segEnd = i + 2 + segLen;
                int t = i + 4;
                while (t + 65 <= segEnd) {
                    rawTables.write(j, t + 1, 64); // 跳过表 ID 字节
                    t += 65;
                }
            } else if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) { // SOF
                // FF C0 | len | precision | height(2) | width(2) | ncomp | [id sampling qtbl]*
                if (i + 13 <= j.length && segLen >= 8) {
                    height = ((j[i + 5] & 0xFF) << 8) | (j[i + 6] & 0xFF);
                    width = ((j[i + 7] & 0xFF) << 8) | (j[i + 8] & 0xFF);
                    sampling = j[i + 11] & 0xFF; // 首分量（Y）
                }
            } else if (marker == 0xDD) { // DRI：重启间隔（MCU 数），扫描数据内含 RSTn 标记
                if (segLen >= 4 && i + 6 <= j.length) {
                    restartInterval = ((j[i + 4] & 0xFF) << 8) | (j[i + 5] & 0xFF);
                }
            } else if (marker == 0xDA) { // SOS：扫描数据起点 = 段尾
                scanStart = i + 2 + segLen;
                break;
            }
            i += 2 + segLen;
        }
        if (scanStart < 0 || rawTables.size() == 0 || width <= 0 || height <= 0
                || width / 8 > 255 || height / 8 > 255) {
            return null; // 无 SOF/超限尺寸无法生成合法 RTP JPEG 头
        }
        int scanEnd = j.length;
        if (scanEnd - scanStart >= 2
                && j[scanEnd - 2] == (byte) 0xFF && j[scanEnd - 1] == (byte) 0xD9) {
            scanEnd -= 2;
        }
        return new ParsedJpeg(rawTables.toByteArray(), scanStart, scanEnd, width, height, sampling, restartInterval);
    }
}
