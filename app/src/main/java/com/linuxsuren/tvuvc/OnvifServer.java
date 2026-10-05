package com.linuxsuren.tvuvc;

import android.content.Context;
import android.net.wifi.WifiManager;

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
import java.util.Locale;
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

    private OnvifServer() {
    }

    public static synchronized void start(Context context) throws IOException {
        if (running) {
            return;
        }
        running = true; // 必须先置位，再启动线程（accept 循环依赖此标志）
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
        if (discoverySocket != null) {
            sendBye();
            discoverySocket.close();
            discoverySocket = null;
        }
        if (httpServer != null) {
            try {
                httpServer.close();
            } catch (IOException ignored) {
            }
            httpServer = null;
        }
        if (multicastLock != null) {
            multicastLock.release();
            multicastLock = null;
        }
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
        return "onvif://www.onvif.org/type/NetworkVideoTransmitter"
                + " onvif://www.onvif.org/name/tvuvc"
                + " onvif://www.onvif.org/hardware/MiTV4"
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

    private static void sendBye() {
        sendMulticast(discoveryMessage("Bye", null));
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
                    + "<td:Manufacturer>LinuxSuRen</td:Manufacturer>"
                    + "<td:Model>tv-uvc-streamer</td:Model>"
                    + "<td:FirmwareVersion>0.1.0</td:FirmwareVersion>"
                    + "<td:SerialNumber>MiTV4-ANSM0</td:SerialNumber>"
                    + "<td:HardwareId>MiTV4</td:HardwareId>"
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
            return soapBody("<trt:GetProfilesResponse>"
                    + "<trt:Profiles fixed=\"true\" token=\"profile_1\">"
                    + "<tt:Name>main</tt:Name>"
                    + "<tt:VideoSourceConfiguration token=\"vsc_1\">"
                    + "<tt:Name>VideoSource_1</tt:Name><tt:SourceToken>src_1</tt:SourceToken>"
                    + "<tt:Bounds x=\"0\" y=\"0\" width=\"1280\" height=\"720\"/>"
                    + "</tt:VideoSourceConfiguration>"
                    + "<tt:VideoEncoderConfiguration token=\"vec_1\">"
                    + "<tt:Name>JPEG1280x720</tt:Name>"
                    + "<tt:UseCount>1</tt:UseCount>"
                    + "<tt:Encoding>JPEG</tt:Encoding>"
                    + "<tt:Resolution><tt:Width>1280</tt:Width><tt:Height>720</tt:Height></tt:Resolution>"
                    + "<tt:Quality>4</tt:Quality>"
                    + "<tt:RateControl><tt:FrameRateLimit>25</tt:FrameRateLimit>"
                    + "<tt:BitrateLimit>8192</tt:BitrateLimit></tt:RateControl>"
                    + "</tt:VideoEncoderConfiguration>"
                    + "</trt:Profiles>"
                    + "</trt:GetProfilesResponse>");
        }
        if (request.contains("GetStreamUri")) {
            return soapBody("<trt:GetStreamUriResponse><trt:MediaUri>"
                    + "<tt:Uri>rtsp://" + ip + ":" + RtspServer.PORT + "/cam</tt:Uri>"
                    + "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>"
                    + "<tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>"
                    + "<tt:Timeout>PT60S</tt:Timeout>"
                    + "</trt:MediaUri></trt:GetStreamUriResponse>");
        }
        // 未知动作：返回空 SOAP（兼容部分客户端的探测序列）
        return soapBody("");
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
