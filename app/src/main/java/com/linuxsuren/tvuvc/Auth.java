package com.linuxsuren.tvuvc;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三服务共享访问认证（RTSP :8554 / HTTP :8090 / ONVIF :8000），单一账号，默认关闭。
 * <p>
 * 移植自 ohos-ipcam-streamer feat/auth（PR #4）同款设计：
 * <ul>
 *   <li><b>RTSP</b>：HTTP Digest（MD5，无 qop 兼容形态，IP 摄像机事实标准）+ Basic；
 *       OPTIONS 放行（客户端能力探测），其余方法须凭证；nonce 按连接签发，不匹配即重挑战</li>
 *   <li><b>HTTP</b>：状态页/快照/MJPEG 全部 Basic，浏览器原生弹登录框</li>
 *   <li><b>ONVIF</b>：WS-Security UsernameToken（PasswordDigest =
 *       Base64(SHA1(nonce 原始字节 + Created + 口令))，Created ±5 分钟；兼容 PasswordText）；
 *       GetSystemDateAndTime/GetCapabilities/GetServices 免认证（pre-auth 惯例，供对时与能力发现），
 *       其余未授权回 HTTP 401；WS-Discovery 发现不设防</li>
 * </ul>
 * 口令必须明文保存于应用私有 SharedPreferences——RTSP Digest 与 WS-PasswordDigest
 * 的服务端校验都需要以明文参与哈希，无法只存摘要。
 */
public final class Auth {

    private static final String TAG = "tv-uvc-auth";
    /** 401 挑战 realm（RTSP/HTTP 共用） */
    static final String REALM = "tv-uvc";
    /** WS-UsernameToken Created 时间窗（±5 分钟，ONVIF 惯例） */
    private static final long CREATED_WINDOW_MS = 300000;

    private static volatile boolean enabled;
    private static volatile String user = "";
    private static volatile String pass = "";

    private Auth() {
    }

    /** 推流启动前/设置变更时注入；开启认证必须同时有用户名与口令，否则视为关闭并告警 */
    public static void configure(boolean enable, String user, String pass) {
        if (enable && (isEmpty(user) || isEmpty(pass))) {
            Log.w(TAG, "auth enabled without credentials, fallback to disabled");
            enable = false;
        }
        Auth.enabled = enable;
        Auth.user = user == null ? "" : user;
        Auth.pass = pass == null ? "" : pass;
        Log.i(TAG, "auth " + (enable ? "enabled" : "disabled"));
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String getUser() {
        return user;
    }

    /** 校验明文账号口令（Basic 与 WS-PasswordText 共用）；逐字节异或累积比较，不短路 */
    static boolean checkUserPass(String user, String pass) {
        if (!enabled || isEmpty(user) || isEmpty(pass)) {
            return false;
        }
        byte[] eu = Auth.user.getBytes(StandardCharsets.UTF_8);
        byte[] ep = Auth.pass.getBytes(StandardCharsets.UTF_8);
        byte[] au = user.getBytes(StandardCharsets.UTF_8);
        byte[] ap = pass.getBytes(StandardCharsets.UTF_8);
        int diff = 0;
        diff |= eu.length ^ au.length;
        diff |= ep.length ^ ap.length;
        for (int i = 0; i < Math.max(eu.length, au.length); i++) {
            diff |= (i < eu.length ? eu[i] : 0) ^ (i < au.length ? au[i] : 0);
        }
        for (int i = 0; i < Math.max(ep.length, ap.length); i++) {
            diff |= (i < ep.length ? ep[i] : 0) ^ (i < ap.length ? ap[i] : 0);
        }
        return diff == 0;
    }

    // ---------------- 基础编码/摘要工具 ----------------

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    /** RTSP Digest 挑战 nonce（随机 16 字节 hex） */
    static String randomNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    private static byte[] digest(String algo, byte[] data) {
        try {
            return MessageDigest.getInstance(algo).digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // MD5/SHA-1 任何 Android 实现都内置
        }
    }

    /** MD5 摘要 hex（RTSP Digest 认证，RFC 2617） */
    private static String md5Hex(String input) {
        return toHex(digest("MD5", input.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] concat3(byte[] a, byte[] b, byte[] c) {
        byte[] out = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        System.arraycopy(c, 0, out, a.length + b.length, c.length);
        return out;
    }

    // ---------------- RTSP / HTTP Authorization 校验 ----------------

    /** 解析结果：Basic 为 base64 凭据（cred），Digest 为各参数 */
    private static final class AuthParams {
        String scheme;
        String cred = "";
        String user = "";
        String realm = "";
        String nonce = "";
        String uri = "";
        String response = "";
    }

    private static final Pattern DIGEST_PARAM = Pattern.compile("([A-Za-z]+)=(\"([^\"]*)\"|[^,]+)");

    /** 解析 Authorization 头：Basic 或 Digest（引号/无引号参数都兼容） */
    private static AuthParams parseAuthorization(String value) {
        if (isEmpty(value)) {
            return null;
        }
        int sp = value.indexOf(' ');
        if (sp < 0) {
            return null;
        }
        AuthParams p = new AuthParams();
        p.scheme = value.substring(0, sp).toLowerCase(Locale.US);
        if ("basic".equals(p.scheme)) {
            p.cred = value.substring(sp + 1).trim();
            return p;
        }
        if (!"digest".equals(p.scheme)) {
            return null;
        }
        Matcher m = DIGEST_PARAM.matcher(value);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.US);
            String raw = m.group(2);
            String v = raw.startsWith("\"") ? raw.substring(1, raw.length() - 1) : raw;
            if ("username".equals(key)) {
                p.user = v;
            } else if ("realm".equals(key)) {
                p.realm = v;
            } else if ("nonce".equals(key)) {
                p.nonce = v;
            } else if ("uri".equals(key)) {
                p.uri = v;
            } else if ("response".equals(key)) {
                p.response = v.toLowerCase(Locale.US);
            }
        }
        return p;
    }

    private static boolean verifyBasic(AuthParams p) {
        if (p.cred.isEmpty()) {
            return false;
        }
        try {
            String decoded = new String(Base64.decode(p.cred, Base64.NO_WRAP), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return false;
            }
            return checkUserPass(decoded.substring(0, colon), decoded.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return false; // 非 base64
        }
    }

    /**
     * RTSP 请求认证校验（RFC 2326 §10 / RFC 2617）：
     * <ul>
     *   <li>Basic：base64(user:pass)</li>
     *   <li>Digest MD5（无 qop 兼容形态）：response == MD5(MD5(user:realm:pass):nonce:MD5(method:uri))，
     *       HA2 的 uri 取客户端 Authorization 头内的 uri 字段（与客户端计算口径一致，
     *       避免 SETUP 轨道地址与请求 URL 写法差异）</li>
     * </ul>
     * issuedNonce 为本连接最近签发的 nonce，非空时须严格匹配，过期即重挑战。
     */
    static boolean verifyRtsp(String method, String authorization, String issuedNonce) {
        if (!enabled) {
            return false;
        }
        AuthParams p = parseAuthorization(authorization);
        if (p == null) {
            return false;
        }
        if ("basic".equals(p.scheme)) {
            return verifyBasic(p);
        }
        if (isEmpty(p.user) || isEmpty(p.nonce) || isEmpty(p.response) || isEmpty(p.uri)) {
            return false;
        }
        if (!user.equals(p.user)) {
            return false;
        }
        if (!isEmpty(issuedNonce) && !issuedNonce.equals(p.nonce)) {
            return false;
        }
        String ha1 = md5Hex(p.user + ":" + p.realm + ":" + pass);
        String ha2 = md5Hex(method + ":" + p.uri);
        String expect = md5Hex(ha1 + ":" + p.nonce + ":" + ha2);
        return expect.equals(p.response);
    }

    /** HTTP Basic 认证校验（状态页/快照/MJPEG 共用） */
    static boolean verifyHttpBasic(String authorization) {
        if (!enabled) {
            return false;
        }
        AuthParams p = parseAuthorization(authorization);
        if (p == null || !"basic".equals(p.scheme)) {
            return false;
        }
        return verifyBasic(p);
    }

    // ---------------- ONVIF WS-UsernameToken 校验 ----------------

    /**
     * 命名空间前缀无关的 SOAP 元素文本提取。元素名后必须紧跟空白/属性/闭尖括号，
     * 防止问 Username 时撞上 UsernameToken 开标签（后者文本为空导致恒拒绝）。
     */
    private static String xmlElement(String xml, String local) {
        Matcher m = Pattern
                .compile("<([A-Za-z0-9]+:)?" + local + "([\\s/][^>]*)?>([^<]*)<")
                .matcher(xml);
        return m.find() ? m.group(3) : "";
    }

    /** 元素开标签属性串（小写化，用于判别 Password 的 Type） */
    private static String xmlElementAttrs(String xml, String local) {
        Matcher m = Pattern
                .compile("<([A-Za-z0-9]+:)?" + local + "([\\s/][^>]*)?>")
                .matcher(xml);
        return m.find() && m.group(2) != null ? m.group(2).toLowerCase(Locale.US) : "";
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&");
    }

    /** ISO 8601 时间解析（如 2026-02-05T12:34:56.789Z，兼容 ±HH:MM 偏移），失败返回 Long.MIN_VALUE */
    private static long parseXmlTime(String s) {
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})"
                + "(?:\\.(\\d{1,3}))?(Z|[+-]\\d{2}:?\\d{2})?$").matcher(s);
        if (!s.isEmpty() && m.find()) {
            try {
                java.util.Calendar c = java.util.Calendar
                        .getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US);
                c.clear();
                c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
                long t = c.getTimeInMillis();
                String frac = m.group(7);
                if (frac != null) {
                    while (frac.length() < 3) {
                        frac += "0";
                    }
                    t += Integer.parseInt(frac);
                }
                String off = m.group(8);
                if (off != null && !"Z".equals(off)) {
                    int sign = off.startsWith("-") ? -1 : 1;
                    String hhmm = off.substring(1).replace(":", "");
                    t -= sign * (Integer.parseInt(hhmm.substring(0, 2)) * 3600000L
                            + Integer.parseInt(hhmm.substring(2)) * 60000L);
                }
                return t;
            } catch (NumberFormatException ignored) {
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * ONVIF WS-Security UsernameToken 校验（SOAP 信封内 s:Header/wsse:Security）：
     * <ul>
     *   <li>PasswordDigest：Base64(SHA1(nonce 原始字节 + Created + 口令)) 比对，
     *       Created 须落在 ±5 分钟窗口内（重放的主要时间界）</li>
     *   <li>PasswordText：明文比对（兼容能力较弱的客户端）</li>
     * </ul>
     * 缺任一字段或超窗即拒绝。
     */
    static boolean verifyWsUsernameToken(String envelope) {
        if (!enabled) {
            return false;
        }
        String tokenUser = xmlUnescape(xmlElement(envelope, "Username"));
        if (tokenUser.isEmpty() || !tokenUser.equals(user)) {
            return false;
        }
        String password = xmlElement(envelope, "Password");
        String passAttrs = xmlElementAttrs(envelope, "Password");
        if (password.isEmpty()) {
            return false;
        }
        if (passAttrs.contains("passworddigest")) {
            String nonce = xmlElement(envelope, "Nonce").trim();
            String created = xmlElement(envelope, "Created").trim();
            if (nonce.isEmpty() || created.isEmpty()) {
                return false;
            }
            long t = parseXmlTime(created);
            if (Math.abs(System.currentTimeMillis() - t) > CREATED_WINDOW_MS) {
                Log.w(TAG, "ws token created out of window");
                return false;
            }
            try {
                byte[] expect = digest("SHA1", concat3(
                        Base64.decode(nonce, Base64.NO_WRAP),
                        created.getBytes(StandardCharsets.UTF_8),
                        pass.getBytes(StandardCharsets.UTF_8)));
                return Base64.encodeToString(expect, Base64.NO_WRAP).equals(password);
            } catch (IllegalArgumentException e) {
                return false; // nonce 非 base64
            }
        }
        /* PasswordText */
        return checkUserPass(tokenUser, xmlUnescape(password));
    }
}
