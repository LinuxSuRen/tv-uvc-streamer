package com.linuxsuren.tvuvc;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 ONVIF 服务（设备发现 + 取 RTSP 地址）。
 * <p>
 * - WS-Discovery：组播 239.255.255.250:3702 响应 Probe（启动时发 Hello，停止时发 Bye）
 * - SOAP 服务 :8000：GetDeviceInformation / GetCapabilities / GetServices /
 *   GetSystemDateAndTime / GetProfiles / GetStreamUri
 * <p>
 * 目的：NVR / Frigate / ODM 等软件可自动发现本设备并直接拿到 rtsp 地址。
 */
public final class OnvifServer {

    public static final int HTTP_PORT = 8000;
    static final String DEVICE_UUID = "urn:uuid:9a3f2c1e-0001-4b7a-9f0c-tvuvc0001";
    private static final String DISCOVERY_GROUP = "239.255.255.250";
    private static final int DISCOVERY_PORT = 3702;

    private static ServerSocket httpServer;
    private static Thread httpThread;
    private static MulticastSocket discoverySocket;
    private static Thread discoveryThread;
    private static WifiManager.MulticastLock multicastLock;
    private static boolean running;
    private static Context appContext; // 供 SOAP 应答读取设备信息

    private OnvifServer() {
    }

    /** 真实设备标识：厂商/机型/系统版本/序列号（受限时回退 ANDROID_ID），供 ONVIF 应答使用 */
    private static String manufacturer() {
        return Build.MANUFACTURER == null ? "unknown" : Build.MANUFACTURER;
    }

    private static String model() {
        return Build.MODEL == null || Build.MODEL.isEmpty() ? Build.DEVICE : Build.MODEL;
    }

    private static String firmware() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    private static String serialNumber() {
        try {
            String serial = Build.getSerial();
            if (serial != null && !serial.isEmpty() && !"unknown".equals(serial)) {
                return serial;
            }
        } catch (Exception ignored) {
            // API 26+ 普通应用无权读取，回退
        }
        if (appContext != null) {
            String id = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        return "unknown";
    }

    public static synchronized void start(Context context) throws IOException {
        if (running) {
            return;
        }
        running = true; // 必须先置位，再启动线程（accept 循环依赖此标志）
        appContext = context.getApplicationContext();
        // 组播锁：WiFi 驱动默认过滤组播包，必须持有 MulticastLock 才能收到 Probe
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("tvuvc:onvif");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        startDiscovery();
        startHttp();
        sendHello();
    }

    public static synchronized void stop() {
        running = false;
        final MulticastSocket discovery = discoverySocket;
        final ServerSocket http = httpServer;
        final WifiManager.MulticastLock lock = multicastLock;
        discoverySocket = null;
        httpServer = null;
        multicastLock = null;
        if (discovery == null) {
            return;
        }
        // 组播发送是网络操作：主线程执行会抛 NetworkOnMainThreadException（Android 12 实测），
        // 全部清理动作放到后台线程完成
        Thread closer = new Thread(() -> {
            sendBye(discovery);
            discovery.close();
            if (http != null) {
                try {
                    http.close();
                } catch (IOException ignored) {
                }
            }
            if (lock != null) {
                lock.release();
            }
        }, "onvif-stop");
        closer.setDaemon(true);
        closer.start();
    }

    // ---------------- WS-Discovery ----------------

    private static void startDiscovery() throws IOException {
        discoverySocket = new MulticastSocket(DISCOVERY_PORT);
        discoverySocket.joinGroup(InetAddress.getByName(DISCOVERY_GROUP));
        discoveryThread = new Thread(() -> {
            byte[] buf = new byte[4096];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    discoverySocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.contains("Probe") && msg.contains("discovery")) {
                        String relatesTo = extract(msg, "MessageID>([^<]+)<");
                        if (relatesTo != null) {
                            byte[] reply = probeMatches(relatesTo).getBytes(StandardCharsets.UTF_8);
                            discoverySocket.send(new DatagramPacket(reply, reply.length,
                                    packet.getAddress(), packet.getPort()));
                        }
                    }
                } catch (IOException ignored) {
                    // socket 关闭时退出
                }
            }
        }, "onvif-discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private static String xAddrs() {
        return String.format(Locale.US, "http://%s:%d/onvif/device_service",
                MjpegServer.lanAddress(), HTTP_PORT);
    }

    private static String scopes() {
        // Scopes 是空格分隔的 token：设备名去空白；name 是客户端展示用的主标题，
        // 带上宿主机型便于多设备区分（如 onvif-ai 发现列表）
        String hardware = model().replaceAll("\\s+", "_");
        String name = ("tv-uvc-streamer@" + model()).replaceAll("\\s+", "_");
        return "onvif://www.onvif.org/type/NetworkVideoTransmitter"
                + " onvif://www.onvif.org/name/" + name
                + " onvif://www.onvif.org/hardware/" + hardware
                + " onvif://www.onvif.org/Profile/Streaming";
    }

    private static String discoveryMessage(String action, String relatesTo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:a=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
                + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<s:Header>"
                + "<a:MessageID>urn:uuid:" + UUID.randomUUID() + "</a:MessageID>"
                + (relatesTo == null ? "" : "<a:RelatesTo>" + relatesTo + "</a:RelatesTo>")
                + "<a:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:To>"
                + "<a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/" + action + "</a:Action>"
                + "<d:AppSequence InstanceId=\"" + instanceId() + "\" MessageNumber=\"1\"/>"
                + "</s:Header><s:Body>"
                + "<d:" + action + ">"
                + "<d:" + action.replace("es", "") + ">" /* ProbeMatch / Hello / Bye */
                + "<a:EndpointReference><a:Address>" + DEVICE_UUID + "</a:Address></a:EndpointReference>"
                + "<d:Types>dn:NetworkVideoTransmitter</d:Types>"
                + "<d:Scopes>" + scopes() + "</d:Scopes>"
                + "<d:XAddrs>" + xAddrs() + "</d:XAddrs>"
                + "<d:MetadataVersion>1</d:MetadataVersion>"
                + "</d:" + action.replace("es", "") + ">"
                + "</d:" + action + ">"
                + "</s:Body></s:Envelope>";
    }

    private static long instanceId() {
        return System.currentTimeMillis() / 1000;
    }

    private static String probeMatches(String relatesTo) {
        return discoveryMessage("ProbeMatches", relatesTo);
    }

    private static void sendHello() {
        sendMulticast(discoveryMessage("Hello", null));
    }

    private static void sendBye(MulticastSocket socket) {
        try {
            byte[] data = discoveryMessage("Bye", null).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(DISCOVERY_GROUP), DISCOVERY_PORT));
        } catch (IOException ignored) {
        }
    }

    private static void sendMulticast(String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            discoverySocket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(DISCOVERY_GROUP), DISCOVERY_PORT));
        } catch (IOException ignored) {
        }
    }

    // ---------------- SOAP over HTTP ----------------

    private static void startHttp() throws IOException {
        httpServer = new ServerSocket();
        httpServer.setReuseAddress(true);
        httpServer.bind(new InetSocketAddress(HTTP_PORT));
        httpThread = new Thread(() -> {
            while (running) {
                try {
                    Socket client = httpServer.accept();
                    Thread t = new Thread(() -> serve(client));
                    t.setDaemon(true);
                    t.start();
                } catch (IOException ignored) {
                }
            }
        }, "onvif-http");
        httpThread.setDaemon(true);
        httpThread.start();
    }

    private static void serve(Socket client) {
        try {
            client.setSoTimeout(10000);
            String request = readRequest(client.getInputStream());
            String response = handleSoap(request);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/soap+xml; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readRequest(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int total = 0;
        // 粗略读取：SOAP 请求都很小，读到超时或缓冲上限即可
        while (total < 65536) {
            int n;
            try {
                n = in.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            total += n;
            if (total > 4 && sb.indexOf("</s:Envelope>") > 0) {
                break;
            }
        }
        return sb.toString();
    }

    private static String handleSoap(String request) {
        String ip = MjpegServer.lanAddress();
        if (request.contains("GetSystemDateAndTime")) {
            return soapBody("<td:GetSystemDateAndTimeResponse>"
                    + "<td:SystemDateAndTime><tt:DateTimeType>Manual</tt:DateTimeType>"
                    + "<tt:UTCDateTime><tt:Year>" + (1900 + new java.util.Date().getYear()) + "</tt:Year>"
                    + "<tt:Month>" + (1 + new java.util.Date().getMonth()) + "</tt:Month>"
                    + "<tt:Day>" + new java.util.Date().getDate() + "</tt:Day>"
                    + "<tt:Hour>" + new java.util.Date().getHours() + "</tt:Hour>"
                    + "<tt:Minute>" + new java.util.Date().getMinutes() + "</tt:Minute>"
                    + "<tt:Second>" + new java.util.Date().getSeconds() + "</tt:Second></tt:UTCDateTime>"
                    + "</td:SystemDateAndTime></td:GetSystemDateAndTimeResponse>");
        }
        if (request.contains("GetDeviceInformation")) {
            return soapBody("<td:GetDeviceInformationResponse>"
                    + "<td:Manufacturer>" + manufacturer() + "</td:Manufacturer>"
                    + "<td:Model>" + model() + "</td:Model>"
                    + "<td:FirmwareVersion>" + firmware() + "</td:FirmwareVersion>"
                    + "<td:SerialNumber>" + serialNumber() + "</td:SerialNumber>"
                    + "<td:HardwareId>" + (Build.BOARD == null || Build.BOARD.isEmpty() ? model() : Build.BOARD) + "</td:HardwareId>"
                    + "</td:GetDeviceInformationResponse>");
        }
        if (request.contains("GetCapabilities")) {
            return soapBody("<td:GetCapabilitiesResponse><td:Capabilities>"
                    + "<tt:Device><tt:XAddr>" + xAddrs() + "</tt:XAddr></tt:Device>"
                    + "<tt:Media><tt:XAddr>http://" + ip + ":" + HTTP_PORT + "/onvif/media_service</tt:XAddr>"
                    + "<tt:StreamingCapabilities>"
                    + "<tt:RTPMulticast>false</tt:RTPMulticast>"
                    + "<tt:RTP_TCP>true</tt:RTP_TCP>"
                    + "<tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>"
                    + "<tt:NonAggregateControl>true</tt:NonAggregateControl>"
                    + "</tt:StreamingCapabilities></tt:Media>"
                    + "</td:Capabilities></td:GetCapabilitiesResponse>");
        }
        if (request.contains("GetServices")) {
            return soapBody("<td:GetServicesResponse>"
                    + "<td:Service><td:Namespace>http://www.onvif.org/ver10/device/wsdl</td:Namespace>"
                    + "<td:XAddr>" + xAddrs() + "</td:XAddr><td:Version><tt:Major>1</tt:Major><tt:Minor>0</tt:Minor></td:Version></td:Service>"
                    + "<td:Service><td:Namespace>http://www.onvif.org/ver10/media/wsdl</td:Namespace>"
                    + "<td:XAddr>http://" + ip + ":" + HTTP_PORT + "/onvif/media_service</td:XAddr>"
                    + "<td:Version><tt:Major>1</tt:Major><tt:Minor>0</tt:Minor></td:Version></td:Service>"
                    + "</td:GetServicesResponse>");
        }
        if (request.contains("GetProfiles")) {
            // 多摄像头：每路一个 Profile（token=profile_{N+1}，N 为全局相机编号），
            // Name 体现相机名（前置/后置/外接）；编号直接取注册表，避免位置错位
            java.util.Map<Integer, String> cams = new java.util.TreeMap<>(MjpegServer.cameras());
            if (cams.isEmpty()) {
                // UVC USB 摄像头 / 相机未就绪场景：单 Profile
                return soapBody(profileXml(1, "USB 摄像头"));
            }
            StringBuilder sb = new StringBuilder("<trt:GetProfilesResponse>");
            for (Map.Entry<Integer, String> e : cams.entrySet()) {
                sb.append(profileXml(e.getKey() + 1, e.getValue()));
            }
            return soapBody(sb.append("</trt:GetProfilesResponse>").toString());
        }
        if (request.contains("GetStreamUri")) {
            int cam = cameraOfToken(extract(request, "ProfileToken>([^<]+)<"));
            String path = cam == 0 ? "cam" : "cam" + cam;
            return soapBody("<trt:GetStreamUriResponse><trt:MediaUri>"
                    + "<tt:Uri>rtsp://" + ip + ":" + RtspServer.PORT + "/" + path + "</tt:Uri>"
                    + "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>"
                    + "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>"
                    + "<tt:Timeout>PT60S</tt:Timeout>"
                    + "</trt:MediaUri></trt:GetStreamUriResponse>");
        }
        if (request.contains("GetSnapshotUri")) {
            // 快照通道：HTTP 单帧 JPEG，供不支持 MJPEG-RTSP 的客户端降级轮询（如 onvif-ai）
            int cam = cameraOfToken(extract(request, "ProfileToken>([^<]+)<"));
            String suffix = cam == 0 ? "" : "?cam=" + cam;
            return soapBody("<trt:GetSnapshotUriResponse><trt:MediaUri>"
                    + "<tt:Uri>http://" + ip + ":" + MjpegServer.PORT + "/snapshot.jpg" + suffix + "</tt:Uri>"
                    + "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>"
                    + "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>"
                    + "<tt:Timeout>PT60S</tt:Timeout>"
                    + "</trt:MediaUri></trt:GetSnapshotUriResponse>");
        }
        // 未知动作：返回空 SOAP（兼容部分客户端的探测序列）
        return soapBody("");
    }

    /** 单个 Profile 的 XML（n 从 1 起，label 为相机名：前置/后置/外接…） */
    private static String profileXml(int n, String label) {
        return "<trt:Profiles fixed=\"true\" token=\"profile_" + n + "\">"
                + "<tt:Name>" + label + "</tt:Name>"
                + "<tt:VideoSourceConfiguration token=\"vsc_" + n + "\">"
                + "<tt:Name>VideoSource_" + n + "</tt:Name><tt:SourceToken>src_" + n + "</tt:SourceToken>"
                + "<tt:Bounds x=\"0\" y=\"0\" width=\"1280\" height=\"720\"/>"
                + "</tt:VideoSourceConfiguration>"
                + "<tt:VideoEncoderConfiguration token=\"vec_" + n + "\">"
                + "<tt:Name>JPEG_" + n + "</tt:Name>"
                + "<tt:UseCount>1</tt:UseCount>"
                + "<tt:Encoding>JPEG</tt:Encoding>"
                + "<tt:Resolution><tt:Width>1280</tt:Width><tt:Height>720</tt:Height></tt:Resolution>"
                + "<tt:Quality>4</tt:Quality>"
                + "<tt:RateControl><tt:FrameRateLimit>25</tt:FrameRateLimit>"
                + "<tt:BitrateLimit>8192</tt:BitrateLimit></tt:RateControl>"
                + "</tt:VideoEncoderConfiguration>"
                + "</trt:Profiles>";
    }

    /** profile_N → 摄像头编号（N-1），无法解析按 0 号处理 */
    private static int cameraOfToken(String token) {
        if (token == null) {
            return 0;
        }
        int under = token.lastIndexOf('_');
        try {
            return Math.max(0, Integer.parseInt(token.substring(under + 1).trim()) - 1);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String soapBody(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:td=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\">"
                + "<s:Body>" + inner + "</s:Body></s:Envelope>";
    }

    private static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
