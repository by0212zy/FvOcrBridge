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
 * 让模块自身的 {@code :ocr} 引擎进程常驻的前台服务。
 *
 * <p><b>为什么必须要有它（v2.0.1 根因修复）</b>：HyperOS / MIUI 的
 * {@code WakePathChecker}（自启动 / 关联启动管控）会拒绝普通应用跨应用拉起
 * 另一个应用的 ContentProvider：
 * <pre>
 * WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
 *   caller=com.fooview.android.fooview callee=com.fvocr.bridge
 *   classname=com.fvocr.bridge.OcrProvider wakeType=4
 * ContentProviderHelper: Unable to launch app for com.fvocr.bridge.ocr
 * </pre>
 * FV 侧因此拿到 {@code Unknown authority com.fvocr.bridge.ocr}，
 * 而 {@code :ocr} 进程自始至终一次都没被启动过（旧版 GalleryOCRBridge 能用，
 * 是因为它借道小米相册这个系统白名单应用，绕开了这道检查）。
 *
 * <p>关键在于：{@code ContentProviderHelper} <b>只在需要「拉起进程」时</b>
 * 才做这项检查；只要 {@code :ocr} 进程已经在运行，就走已运行分支直接返回
 * provider。所以本服务把 {@code :ocr} 进程保活，FV 的调用即可正常落地。
 *
 * <p>本服务由<b>模块自身</b>的组件启动（caller == callee），不属于关联启动，
 * 不受 {@code WakePathChecker} 拦截。它不初始化引擎（native 仍然 lazy），
 * 只负责占住进程；MACE 崩溃隔离的设计完全不变。
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
            Log.i(TAG, "OcrService: :ocr 进程已前台常驻（引擎仍 lazy）");
        } catch (Throwable t) {
            // 后台启动 FGS 偶受系统限制；只要 :ocr 进程被拉起，Provider 就可用，
            // 所以这里失败也不影响主链路，仅记录。
            Log.w(TAG, "startForeground 失败（不影响 Provider 可用）: " + t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 由模块自身的组件调用：把 {@code :ocr} 进程拉起并保活。 */
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
