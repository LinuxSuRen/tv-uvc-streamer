package com.linuxsuren.tvuvc;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * ONVIF Event 服务核心（PullPoint 订阅模型，ONVIF Core §Events）：
 * <ul>
 *   <li>订阅：CreatePullPointSubscription 创建，返回独立 PullPoint 地址
 *       （http://ip:port/onvif/sub_N），客户端向该地址发 PullMessages</li>
 *   <li>拉取：PullMessages 阻塞至 Timeout 或有消息（规范要求：pull 会把订阅
 *       的 TerminationTime 顺延到「本次请求到达时刻 + Timeout」，不早于）</li>
 *   <li>续期/退订：Renew / Unsubscribe（wsnt 命名空间）</li>
 *   <li>缓冲：每订阅环形队列，溢出丢最旧；TTL 过期的订阅自动回收</li>
 *   <li>限频：同一 topic+source 相邻两次发布最小间隔 2s——信号抖动/认证
 *       爆破不会淹没队列</li>
 * </ul>
 * 消息以渲染好的 wsnt:NotificationMessage XML 片段存储，命名空间由
 * 应答信封统一声明（tns1 标准话题 + lns 自定义话题）。
 */
public final class OnvifEventBus {

    private static final String TAG = "tv-uvc-events";
    /** 订阅默认存活（CreatePullPointSubscription 未指定时） */
    static final long DEFAULT_TTL_MS = 60_000;
    /** 单订阅缓冲上限（溢出丢最旧） */
    private static final int QUEUE_CAP = 100;
    /** 同一 topic+source 的最小发布间隔（防抖动刷屏） */
    private static final long PUBLISH_MIN_INTERVAL_MS = 2_000;

    /** 一次事件的结构化载荷：source/data 各为 [name, value] 数组 */
    static final class Event {
        final String topic;
        final String[][] source;
        final String[][] data;
        final String utcTime;

        Event(String topic, String[][] source, String[][] data) {
            this.topic = topic;
            this.source = source;
            this.data = data;
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            this.utcTime = f.format(new Date());
        }
    }

    private static final class Sub {
        final String id;
        volatile long deadline;
        final ArrayDeque<Event> queue = new ArrayDeque<>();

        Sub(String id, long ttlMs) {
            this.id = id;
            this.deadline = System.currentTimeMillis() + ttlMs;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Sub> SUBS = new HashMap<>();
    /** key → [最近发布时间, 载荷指纹]：同指纹 2s 内合并，载荷变化放行 */
    private static final Map<String, long[]> LAST_PUBLISH = new HashMap<>();
    private static long seq;

    private OnvifEventBus() {
    }

    /** 创建订阅，返回订阅 id（PullPoint URL 的最后一段） */
    static String create(long ttlMs) {
        String id;
        synchronized (LOCK) {
            expireLocked();
            id = "sub_" + (++seq);
            SUBS.put(id, new Sub(id, ttlMs <= 0 ? DEFAULT_TTL_MS : ttlMs));
        }
        return id;
    }

    /**
     * 阻塞拉取：至多等待 waitMs 等第一条消息，凑满 limit 条即返回。
     * 按规范顺延订阅存活：deadline = 本次请求到达时刻 + waitMs。
     *
     * @return 渲染后的 NotificationMessage XML 串（可为空）
     */
    static String pull(String id, long waitMs, int limit) throws InterruptedException {
        if (waitMs < 0) {
            waitMs = 0;
        }
        if (waitMs > 60_000) {
            waitMs = 60_000;
        }
        if (limit <= 0) {
            limit = 5;
        }
        long deadlineTs = System.currentTimeMillis() + waitMs;
        StringBuilder out = new StringBuilder();
        synchronized (LOCK) {
            Sub sub = SUBS.get(id);
            if (sub == null) {
                return null; // 订阅不存在或已过期
            }
            sub.deadline = Math.max(sub.deadline,
                    System.currentTimeMillis() + Math.max(waitMs, 5_000));
            while (sub.queue.isEmpty() && System.currentTimeMillis() < deadlineTs) {
                LOCK.wait(Math.min(200, deadlineTs - System.currentTimeMillis()));
                if (SUBS.get(id) != sub) {
                    return null;
                }
            }
            int n = Math.min(limit, sub.queue.size());
            for (int i = 0; i < n; i++) {
                out.append(render(sub.queue.poll()));
            }
        }
        return out.toString();
    }

    /** 续期；订阅不存在返回 false */
    static boolean renew(String id, long ttlMs) {
        synchronized (LOCK) {
            Sub sub = SUBS.get(id);
            if (sub == null) {
                return false;
            }
            sub.deadline = System.currentTimeMillis() + (ttlMs <= 0 ? DEFAULT_TTL_MS : ttlMs);
            return true;
        }
    }

    static void unsubscribe(String id) {
        synchronized (LOCK) {
            SUBS.remove(id);
        }
    }

    /** 发布一条事件；限频只抑制「同 topic+source 相同载荷」的重复（2s 窗口）——
     * 状态交替（SignalLoss false→true）载荷不同必须放行，否则快速重启循环里
     * NVR 只能看到恢复永远看不到丢失 */
    static void publish(String topic, String[][] source, String[][] data) {
        String key = topic + '|' + (source.length > 0 ? source[0][1] : "");
        String fp = fingerprint(topic, source, data);
        synchronized (LOCK) {
            expireLocked();
            long[] last = LAST_PUBLISH.get(key);
            long now = System.currentTimeMillis();
            if (last != null && now - last[0] < PUBLISH_MIN_INTERVAL_MS && last[1] == fp.hashCode()) {
                return;
            }
            LAST_PUBLISH.put(key, new long[]{now, fp.hashCode()});
            if (SUBS.isEmpty()) {
                return; // 无人订阅也记限频：避免订阅瞬间补发历史风暴
            }
            Event e = new Event(topic, source, data);
            for (Sub sub : SUBS.values()) {
                if (sub.queue.size() >= QUEUE_CAP) {
                    sub.queue.pollFirst(); // 溢出丢最旧
                }
                sub.queue.addLast(e);
            }
            LOCK.notifyAll();
            Log.d(TAG, "publish " + topic);
        }
    }

    private static String fingerprint(String topic, String[][] source, String[][] data) {
        StringBuilder sb = new StringBuilder(topic);
        for (String[] kv : source) {
            sb.append('|').append(kv[0]).append('=').append(kv[1]);
        }
        for (String[] kv : data) {
            sb.append('|').append(kv[0]).append('=').append(kv[1]);
        }
        return sb.toString();
    }

    /** 过期订阅回收（调用方持锁） */
    private static void expireLocked() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, Sub>> it = SUBS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().deadline < now) {
                it.remove();
            }
        }
    }

    /** 渲染为 wsnt:NotificationMessage 片段（信封声明 tns1/lns/tt/wsnt） */
    private static String render(Event e) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<wsnt:NotificationMessage>")
                .append("<wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">")
                .append(xml(e.topic)).append("</wsnt:Topic>")
                .append("<wsnt:Message><tt:Message UtcTime=\"").append(e.utcTime)
                .append("\" PropertyOperation=\"Changed\">");
        if (e.source.length > 0) {
            sb.append("<tt:Source>");
            for (String[] kv : e.source) {
                sb.append("<tt:SimpleItem Name=\"").append(xml(kv[0]))
                        .append("\" Value=\"").append(xml(kv[1])).append("\"/>");
            }
            sb.append("</tt:Source>");
        }
        if (e.data.length > 0) {
            sb.append("<tt:Data>");
            for (String[] kv : e.data) {
                sb.append("<tt:SimpleItem Name=\"").append(xml(kv[0]))
                        .append("\" Value=\"").append(xml(kv[1])).append("\"/>");
            }
            sb.append("</tt:Data>");
        }
        return sb.append("</tt:Message></wsnt:Message></wsnt:NotificationMessage>").toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
