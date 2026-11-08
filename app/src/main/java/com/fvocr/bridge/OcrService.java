package com.fvocr.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 让模块<b>主进程</b>常驻的前台服务（v2.0.2）。
 *
 * <p><b>为什么必须要有它</b>：HyperOS / MIUI 的 {@code WakePathChecker}
 * （自启动 / 关联启动管控）会拒绝本包的一切 Service / Provider / Broadcast
 * 冷启动：
 * <pre>
 * WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
 *   caller=com.fooview.android.fooview callee=com.fvocr.bridge
 *   classname=com.fvocr.bridge.OcrProvider wakeType=4
 *   classname=com.fvocr.bridge.OcrService wakeType=8
 *   classname=com.fvocr.bridge.BootReceiver action=com.fvocr.bridge.WAKE wakeType=2
 * BroadcastQueueInjector: Unable to launch app ... process is not permitted to wake path
 * ContentProviderHelper: Unable to launch app for com.fvocr.bridge.ocr
 * </pre>
 * FV 侧因此拿到 {@code Unknown authority com.fvocr.bridge.ocr}
 * （旧版 GalleryOCRBridge 能用，是因为借道小米相册这个系统白名单应用）。
 *
 * <p><b>v2.0.1 为什么也没用</b>：当时服务、Provider、Receiver 全在
 * {@code :ocr} 子进程，「激活 :ocr」又得先启动 :ocr，循环依赖；连系统投递的
 * {@code MY_PACKAGE_REPLACED} 都被拦，{@code am broadcast} 的
 * {@code result=0} 只代表广播队列处理完，Receiver 根本没执行。
 *
 * <p><b>v2.0.2 的解法</b>：服务与 {@link OcrProvider} 全收回主进程，
 * 由 {@link WakeActivity}（前台、同应用、显式 Activity）直接启动本服务。
 * 显式 {@code startActivity} 不在 {@code Service/Provider/Broadcast Reject}
 * 管控里；本服务的启动又是 caller == callee 且从前台发起，也满足 Android 12+
 * 对后台 {@code startForegroundService} 的限制。进程一旦常驻，
 * {@code ContentProviderHelper} 就走已运行分支直接返回 Provider，
 * FV 的 {@code content call} 不再触发任何唤醒检查。
 *
 * <p>本服务不初始化引擎（native 仍然 lazy），只负责占住进程。
 */
public class OcrService extends Service {

    private static final String TAG = "FvOcrBridge";
    private static final String CHANNEL_ID = "fvocr_engine";
    private static final int NOTIFICATION_ID = 0x46564F43;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        return START_STICKY;
    }

    private void startAsForeground() {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL_ID, "OCR 引擎",
                            NotificationManager.IMPORTANCE_MIN);
                    ch.setDescription("本地 OCR 引擎常驻（不联网、不采集数据）");
                    ch.setSound(null, null);
                    ch.enableVibration(false);
                    ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                    nm.createNotificationChannel(ch);
                }
                b = new Notification.Builder(this, CHANNEL_ID);
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("FV 识图桥")
                    .setContentText("本地 OCR 引擎已就绪（离线）")
                    .setOngoing(true)
                    .build();
            startForeground(NOTIFICATION_ID, n);
            Log.i(TAG, "OcrService: 主进程已前台常驻（引擎仍 lazy）");
        } catch (Throwable t) {
            // 后台启动 FGS 偶受系统限制；只要主进程被拉起，Provider 就可用，
            // 所以这里失败也不影响主链路，仅记录。
            Log.w(TAG, "startForeground 失败（不影响 Provider 可用）: " + t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 由模块自身的组件（WakeActivity / SettingsActivity / Provider）调用：主进程前台保活。 */
    static void ensure(Context ctx) {
        try {
            Intent i = new Intent(ctx, OcrService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i(TAG, "OcrService.ensure() 已请求启动");
        } catch (Throwable t) {
            Log.w(TAG, "OcrService.ensure() 失败（忽略）: " + t);
        }
    }
}
