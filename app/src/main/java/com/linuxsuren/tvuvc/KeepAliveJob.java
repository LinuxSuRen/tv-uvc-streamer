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

    @Override
    public boolean onStartJob(JobParameters params) {
        MjpegServer.ensureRunning(this);
        jobFinished(params, false);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 尽可能重新调度
    }

    /** 幂等调度：15 分钟周期 + 重启后保持（getPendingJob 需 API 24，用 cancel+schedule 兼容 API 23） */
    public static void schedule(Context context) {
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
        }
    }
}
