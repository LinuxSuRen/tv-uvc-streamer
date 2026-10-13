package com.linuxsuren.tvuvc;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 自愈任务：周期性检查采集服务是否存活，不在则直接拉起。
 * JobScheduler 由系统调度，不受 MIUI 第三方广播拦截影响，重启后仍会按计划执行。
 */
public class KeepAliveJob extends JobService {

    private static final int JOB_ID = 0x7101;
    /** 一次性立即任务：进程内 startService 被定制 ROM 后台拒绝时借道 Job 白名单上下文 */
    private static final int JOB_ID_IMMEDIATE = 0x7102;

    @Override
    public boolean onStartJob(JobParameters params) {
        // ensureRunning 的重启动作只投递 startService，均同步完成：
        // 必须返回 false。曾经「jobFinished + return true」的矛盾用法会让
        // 一次性立即 Job 被 onStopJob 无限重排，形成重启风暴（Max2.0 实测）
        MjpegServer.ensureRunning(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 周期任务尽可能重新调度；一次性立即任务不重排（避免立即 Job 风暴）
        return params.getJobId() != JOB_ID_IMMEDIATE;
    }

    /** 立即执行一次自愈检查（无约束、最多 2 秒后必须运行）：Job 上下文在系统临时白名单内，startService 不受后台限制（Max2.0 实测进程内重启被静默拒绝而 Job 路径放行） */
    public static void scheduleImmediate(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        JobInfo info = new JobInfo.Builder(JOB_ID_IMMEDIATE,
                new ComponentName(context, KeepAliveJob.class))
                .setMinimumLatency(0)
                .setOverrideDeadline(2000)
                .build();
        try {
            scheduler.schedule(info);
        } catch (IllegalArgumentException ignored) {
        } catch (IllegalStateException e) {
            // 同 schedule()：限流静默降级，不允许异常上主线程
            android.util.Log.w("tv-uvc-streamer", "immediate job schedule rate-limited");
        }
    }

    /** schedule 节流窗口：onStartCommand 风暴时避免烧 JobScheduler 250 次/60s 配额 */
    private static final long SCHEDULE_THROTTLE_MS = 5_000;
    private static long lastScheduleAt;

    /** 幂等调度：15 分钟周期 + 重启后保持（getPendingJob 需 API 24，用 cancel+schedule 兼容 API 23） */
    public static void schedule(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastScheduleAt < SCHEDULE_THROTTLE_MS) {
            return; // 5 秒内已排过：15 分钟周期任务无需更密刷新，风暴轮次直接跳过
        }
        lastScheduleAt = now;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        scheduler.cancel(JOB_ID);
        JobInfo info = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, KeepAliveJob.class))
                .setPeriodic(15 * 60 * 1000)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        try {
            scheduler.schedule(info);
        } catch (IllegalArgumentException ignored) {
            // 极少数 ROM 不支持 persisted 任务
        } catch (IllegalStateException e) {
            // JobScheduler 250 次/60s 限流：startService 风暴下每轮 onStartCommand 都
            // cancel+schedule 会烧穿配额（联想 TB335ZC/Android 16 实测 14s 后 FATAL
            // 炸主线程）。限流是暂时态，静默降级等下一轮重排，绝不让异常上主线程
            android.util.Log.w("tv-uvc-streamer", "job schedule rate-limited, skip this round");
        }
    }

    /** 用户主动退出时必须取消保活任务，否则服务会被周期性拉起 */
    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }
}
