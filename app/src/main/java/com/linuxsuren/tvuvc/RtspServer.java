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
        serverSocket = new ServerSocket(PORT);
        acceptThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
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
                // 读掉该请求剩余头部
                String transport = null;
                String h;
                while ((h = in.readLine()) != null && !h.isEmpty()) {
                    if (h.toLowerCase(Locale.US).startsWith("transport:")) {
                        transport = h.substring(h.indexOf(':') + 1).trim();
                    }
                }
                switch (method) {
                    case "OPTIONS":
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseqOf(line)
                                + "\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n");
                        break;
                    case "DESCRIBE":
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseqOf(line)
                                + "\r\nContent-Type: application/sdp\r\nContent-Length: "
                                + sdp().length() + "\r\n\r\n" + sdp());
                        break;
                    case "SETUP":
                        session = Integer.toHexString(new Random().nextInt(0x7FFFFFFF));
                        client = new Client(socket.getInetAddress(), transport);
                        CLIENTS.add(client);
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseqOf(line)
                                + "\r\nSession: " + session
                                + "\r\nTransport: " + transport + ";server_port=" + client.rtpPort + "-" + (client.rtpPort + 1)
                                + "\r\n\r\n");
                        break;
                    case "PLAY":
                        if (client != null) {
                            client.playing = true;
                        }
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseqOf(line)
                                + "\r\nSession: " + session
                                + "\r\nRTP-Info: url=" + url() + ";seq=0;rtptime=0"
                                + "\r\nRange: npt=0-\r\n\r\n");
                        break;
                    case "TEARDOWN":
                        if (client != null) {
                            CLIENTS.remove(client);
                            client.close();
                        }
                        write(out, "RTSP/1.0 200 OK\r\nCSeq: " + cseqOf(line)
                                + "\r\nSession: " + session + "\r\n\r\n");
                        return;
                    default:
                        write(out, "RTSP/1.0 501 Not Implemented\r\nCSeq: " + cseqOf(line) + "\r\n\r\n");
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

    private static String cseqOf(String requestLine) {
        return String.valueOf(System.nanoTime() & 0xFFFF); // 极简：客户端基本不校验顺序
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

    // ---------------- RTP 发送 ----------------

    private static void broadcastFrame(byte[] frame, long frameIndex) {
        for (Client c : CLIENTS) {
            if (c.playing) {
                c.sendFrame(frame, frameIndex);
            }
        }
    }

    private static final class Client {
        final InetAddress address;
        final int rtpPort;
        final int rtcpPort;
        final DatagramSocket socket;
        final int ssrc;
        volatile boolean playing;
        int sequence = 0;

        Client(InetAddress address, String transport) throws IOException {
            this.address = address;
            int[] ports = parseClientPorts(transport);
            this.rtpPort = ports[0];
            this.rtcpPort = ports[1];
            this.socket = new DatagramSocket();
            this.ssrc = new Random().nextInt();
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
            final int type = 1; // 4:2:0（Y=2x2），对应 ffmpeg rtpdec_jpeg 的 hsample/vsample=(2,2)
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
                    pkt[16] = (byte) type;                         // 1 = 4:2:0
                    pkt[17] = (byte) 255;                          // 质量 255 = 携带量化表
                    pkt[18] = (byte) (1280 / 8);                   // 宽/8
                    pkt[19] = (byte) (720 / 8);                    // 高/8
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
                    socket.send(new DatagramPacket(pkt, pkt.length, address, rtpPort));
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
        }
    }

    /** JPEG 帧解析结果：量化表（标准 JPEG 格式，含表 ID 字节）与扫描数据区间 */
    private static final class ParsedJpeg {
        final byte[] tables;
        final int scanStart;
        final int scanEnd;

        ParsedJpeg(byte[] tables, int scanStart, int scanEnd) {
            this.tables = tables;
            this.scanStart = scanStart;
            this.scanEnd = scanEnd;
        }
    }

    /**
     * 解析 JPEG：收集全部 DQT 段（FFDB）并去掉每张表 1 字节的表 ID（ffmpeg 要求
     * 量化表为 64 字节裸值的连续序列，两张表即 128 字节）；定位 SOS（FFDA）段之后
     * 的熵编码扫描数据起点；结尾去掉 EOI（FFD9）。MJPEG 基线帧为单扫描。
     */
    private static ParsedJpeg parseJpeg(byte[] j) {
        java.io.ByteArrayOutputStream rawTables = new java.io.ByteArrayOutputStream();
        int scanStart = -1;
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
            } else if (marker == 0xDA) { // SOS：扫描数据起点 = 段尾
                scanStart = i + 2 + segLen;
                break;
            }
            i += 2 + segLen;
        }
        if (scanStart < 0 || rawTables.size() == 0) {
            return null;
        }
        int scanEnd = j.length;
        if (scanEnd - scanStart >= 2
                && j[scanEnd - 2] == (byte) 0xFF && j[scanEnd - 1] == (byte) 0xD9) {
            scanEnd -= 2;
        }
        return new ParsedJpeg(rawTables.toByteArray(), scanStart, scanEnd);
    }
}
