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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
            // PullPoint 订阅地址路由：/onvif/sub_N 上的请求按订阅操作分发
            //（PullMessages 可能阻塞至 Timeout，应答前延长 socket 超时不必要——
            // 阻塞发生在事件总线的条件等待，与读超时无关）
            String subId = extract(request, "POST /onvif/(sub_[A-Za-z0-9]+)");
            String response;
            if (subId != null) {
                response = handlePullPoint(subId, request);
            } else {
                response = handleSoap(request);
            }
            OutputStream out = client.getOutputStream();
            if (response == null) {
                // 认证拒绝：按 ONVIF 客户端兼容惯例回 HTTP 401（空体）
                out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return;
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
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

    /**
     * 按 HTTP 协议读取请求：头部读到空行（\r\n\r\n）即止，再按 Content-Length
     * 精确读取 SOAP 体。旧实现盲读等待 </s:Envelope> 或 10s 超时——浏览器 GET
     * 无 SOAP 体，每次响应都要等满 soTimeout，表现为"无法访问/超时"（真机实测
     * 恰好 10.05s）。字节层扫描头边界、整体解码，避免多字节字符被分块读截断。
     */
    private static String readRequest(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int headerEnd = -1; // 头部结束位置（\r\n\r\n 的末字节下标+1）
        while (headerEnd < 0 && bos.size() < 65536) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            bos.write(buf, 0, n);
            headerEnd = findHeaderEnd(bos.toByteArray());
        }
        byte[] all = bos.toByteArray();
        if (headerEnd < 0) {
            return new String(all, StandardCharsets.UTF_8);
        }
        String head = new String(java.util.Arrays.copyOfRange(all, 0, headerEnd), StandardCharsets.UTF_8);
        long contentLength = 0;
        Matcher m = Pattern.compile("(?i)^Content-Length:\\s*(\\d+)", Pattern.MULTILINE)
                .matcher(head);
        if (m.find()) {
            try {
                contentLength = Long.parseLong(m.group(1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        while (all.length - headerEnd < contentLength && all.length < 65536) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            bos.write(buf, 0, n);
            all = bos.toByteArray();
        }
        return new String(all, StandardCharsets.UTF_8);
    }

    /** 头部结束（\r\n\r\n）位置：返回其末字节之后的位置，未找到为 -1 */
    private static int findHeaderEnd(byte[] data) {
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /** 处理 SOAP 请求；认证未通过返回 null（serve 层转 HTTP 401） */
    private static String handleSoap(String request) {
        String ip = MjpegServer.lanAddress();
        // 认证门禁：pre-auth 白名单（GetSystemDateAndTime 对时/GetCapabilities/GetServices
        // 能力发现）放行，其余操作（GetProfiles/GetStreamUri/快照/设备信息/事件订阅）
        // 要求 WS-Security UsernameToken；WS-Discovery 发现不设防
        if (Auth.isEnabled()
                && !request.contains("GetSystemDateAndTime")
                && !request.contains("GetCapabilities")
                && !request.contains("GetServices")
                && !Auth.verifyWsUsernameToken(request)) {
            android.util.Log.w("tv-uvc-onvif", "soap 401 unauthorized");
            OnvifEventBus.publish("lns:Security/AuthFailure",
                    new String[][]{{"Service", "ONVIF"}},
                    new String[][]{{"State", "Failed"}});
            return null;
        }
        // ---- Event 服务（PullPoint 订阅模型）----
        if (request.contains("CreatePullPointSubscription")) {
            long ttl = parseIsoDuration(extract(request, "InitialTerminationTime>([^<]+)<"));
            String id = OnvifEventBus.create(ttl);
            return soapBodyEvents("<tev:CreatePullPointSubscriptionResponse>"
                    + "<tev:SubscriptionReference><wsa:Address>"
                    + subscriptionAddress(id) + "</wsa:Address></tev:SubscriptionReference>"
                    + "<wsnt:CurrentTime>" + isoNow() + "</wsnt:CurrentTime>"
                    + "<wsnt:TerminationTime>" + isoAfter(ttl > 0 ? ttl : OnvifEventBus.DEFAULT_TTL_MS) + "</wsnt:TerminationTime>"
                    + "</tev:CreatePullPointSubscriptionResponse>");
        }
        if (request.contains("GetEventProperties")) {
            return soapBodyEvents("<tev:GetEventPropertiesResponse>"
                    + "<tev:TopicNamespaceLocation>http://www.onvif.org/onvif/ver10/topics/index.html</tev:TopicNamespaceLocation>"
                    + "<wsnt:FixedTopicSet>true</wsnt:FixedTopicSet>"
                    + "<wsnt:TopicExpressionDialect>http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet</wsnt:TopicExpressionDialect>"
                    + "<tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter</tev:MessageContentFilterDialect>"
                    + topicSet()
                    + "</tev:GetEventPropertiesResponse>");
        }
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
                    + "<tt:Events><tt:XAddr>" + xAddrs() + "</tt:XAddr></tt:Events>"
                    + "</td:Capabilities></td:GetCapabilitiesResponse>");
        }
        if (request.contains("GetServices")) {
            return soapBody("<td:GetServicesResponse>"
                    + "<td:Service><td:Namespace>http://www.onvif.org/ver10/device/wsdl</td:Namespace>"
                    + "<td:XAddr>" + xAddrs() + "</td:XAddr><td:Version><tt:Major>1</tt:Major><tt:Minor>0</tt:Minor></td:Version></td:Service>"
                    + "<td:Service><td:Namespace>http://www.onvif.org/ver10/media/wsdl</td:Namespace>"
                    + "<td:XAddr>http://" + ip + ":" + HTTP_PORT + "/onvif/media_service</td:XAddr>"
                    + "<td:Version><tt:Major>1</tt:Major><tt:Minor>0</tt:Minor></td:Version></td:Service>"
                    + "<td:Service><td:Namespace>http://www.onvif.org/ver10/events/wsdl</td:Namespace>"
                    + "<td:XAddr>" + xAddrs() + "</td:XAddr>"
                    + "<td:Version><tt:Major>1</tt:Major><tt:Minor>0</tt:Minor></td:Version></td:Service>"
                    + "</td:GetServicesResponse>");
        }
        if (request.contains("GetProfiles")) {
            // 多摄像头：每路一个 Profile（token=profile_{N+1}，N 为全局相机编号），
            // Name 体现相机名（前置/后置/外接）；编号直接取注册表，避免位置错位
            java.util.Map<Integer, String> cams = new java.util.TreeMap<>(MjpegServer.cameras());
            if (cams.isEmpty()) {
                // 「只广播真实在推流的相机」：无相机在推流时返回空列表。
                // 曾经兜底一个假 Profile，客户端拿到取流地址却无流可取，
                // 被误报成"摄像头未返回任何媒体配置"（Max2.0 实测）
                return soapBody("<trt:GetProfilesResponse/>");
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

    // ---------------- Event 服务（PullPoint）----------------

    /** PullPoint 订阅端点（/onvif/sub_N）的操作分发：Pull/Renew/Unsubscribe */
    private static String handlePullPoint(String id, String request) {
        // 订阅端点同样要求认证（开启时）——订阅操作不在 pre-auth 白名单
        if (Auth.isEnabled() && !Auth.verifyWsUsernameToken(request)) {
            OnvifEventBus.publish("lns:Security/AuthFailure",
                    new String[][]{{"Service", "ONVIF"}},
                    new String[][]{{"State", "Failed"}});
            return null;
        }
        if (request.contains("PullMessages")) {
            long waitMs = parseIsoDuration(extract(request, "Timeout>([^<]+)<"));
            int limit = 10;
            try {
                String lim = extract(request, "MessageLimit>(\\d+)<");
                if (lim != null) {
                    limit = Integer.parseInt(lim);
                }
            } catch (NumberFormatException ignored) {
            }
            String messages;
            try {
                messages = OnvifEventBus.pull(id, waitMs, limit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (messages == null) {
                return soapBodyEvents(soapFaultEvents("ter:SubscriptionLost",
                        "subscription expired or unknown"));
            }
            return soapBodyEvents("<tev:PullMessagesResponse>"
                    + "<tev:CurrentTime>" + isoNow() + "</tev:CurrentTime>"
                    + "<tev:TerminationTime>" + isoAfter(OnvifEventBus.DEFAULT_TTL_MS) + "</tev:TerminationTime>"
                    + messages
                    + "</tev:PullMessagesResponse>");
        }
        if (request.contains("Renew")) {
            long ttl = parseIsoDuration(extract(request, "TerminationTime>([^<]+)<"));
            if (ttl < 0) {
                ttl = OnvifEventBus.DEFAULT_TTL_MS;
            }
            if (!OnvifEventBus.renew(id, ttl)) {
                return soapBodyEvents(soapFaultEvents("ter:SubscriptionLost",
                        "subscription expired or unknown"));
            }
            return soapBodyEvents("<wsnt:RenewResponse>"
                    + "<wsnt:TerminationTime>" + isoAfter(ttl) + "</wsnt:TerminationTime>"
                    + "</wsnt:RenewResponse>");
        }
        if (request.contains("Unsubscribe")) {
            OnvifEventBus.unsubscribe(id);
            return soapBodyEvents("<wsnt:UnsubscribeResponse/>");
        }
        return soapBodyEvents(soapFaultEvents("ter:ActionNotSupported",
                "operation not supported on pull point"));
    }

    private static String soapFaultEvents(String code, String text) {
        return "<s:Fault><s:Code><s:Value>s:Sender</s:Value>"
                + "<s:Subcode><s:Value>" + code + "</s:Value></s:Subcode></s:Code>"
                + "<s:Reason><s:Text xml:lang=\"en\">" + text + "</s:Text></s:Reason></s:Fault>";
    }

    /** 支持的话题集（GetEventProperties），标准话题 tns1 + 自定义 lns */
    private static String topicSet() {
        return "<wstop:TopicSet>"
                + "<wstop:VideoSource><wstop:SignalLoss wstop:topic=\"true\"/></wstop:VideoSource>"
                + "<wstop:RuleEngine><wstop:CellMotion><wstop:Motion wstop:topic=\"true\"/></wstop:CellMotion></wstop:RuleEngine>"
                + "<wstop:Security><wstop:AuthFailure wstop:topic=\"true\"/></wstop:Security>"
                + "<wstop:Power><wstop:Battery wstop:topic=\"true\"/></wstop:Power>"
                + "</wstop:TopicSet>";
    }

    private static String subscriptionAddress(String id) {
        return "http://" + MjpegServer.lanAddress() + ":" + HTTP_PORT + "/onvif/" + id;
    }

    /** 事件应答信封：声明事件相关命名空间（tev/wsa/wsnt/wstop + 话题 tns1/lns） */
    private static String soapBodyEvents(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:td=\"http://www.onvif.org/ver10/device/wsdl\""
                + " xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\""
                + " xmlns:tt=\"http://www.onvif.org/ver10/schema\""
                + " xmlns:tev=\"http://www.onvif.org/ver10/events/wsdl\""
                + " xmlns:wsa=\"http://www.w3.org/2005/08/addressing\""
                + " xmlns:wsnt=\"http://docs.oasis-open.org/wsn/b-2\""
                + " xmlns:wstop=\"http://docs.oasis-open.org/wsn/t-1\""
                + " xmlns:tns1=\"http://www.onvif.org/ver10/topics\""
                + " xmlns:lns=\"http://www.linuxsuren.com/onvif/topics\">"
                + "<s:Body>" + inner + "</s:Body></s:Envelope>";
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String isoAfter(long afterMs) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(System.currentTimeMillis() + afterMs));
    }

    /** ISO 8601 时长（PT#H#M#S，含小数秒）转毫秒；无法解析返回 -1 */
    private static long parseIsoDuration(String d) {
        if (d == null) {
            return -1;
        }
        Matcher m = Pattern.compile("(?i)^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?$").matcher(d.trim());
        if (!m.find()) {
            return -1;
        }
        long ms = 0;
        if (m.group(1) != null) {
            ms += Long.parseLong(m.group(1)) * 3600_000L;
        }
        if (m.group(2) != null) {
            ms += Long.parseLong(m.group(2)) * 60_000L;
        }
        if (m.group(3) != null) {
            ms += (long) (Double.parseDouble(m.group(3)) * 1000);
        }
        return ms;
    }

    private static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
