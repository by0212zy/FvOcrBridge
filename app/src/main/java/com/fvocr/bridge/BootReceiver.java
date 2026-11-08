package com.fvocr.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 兼容用的广播入口，运行在模块<b>主进程</b>。
 *
 * <p><b>v2.0.2：这不是主激活路径</b>。真机日志证实 HyperOS / MIUI 连本包的
 * 广播冷启动也拦：系统投递的 {@code MY_PACKAGE_REPLACED} 与手工
 * {@code am broadcast ... WAKE} 都出现
 * {@code BroadcastQueueInjector: process is not permitted to auto start / wake path}，
 * 且 {@code Broadcast completed: result=0} 只代表广播队列处理完，
 * Receiver 根本没有执行机会。所以主入口改为 {@link WakeActivity}
 * （显式 Activity，不在 "Service/Provider/Broadcast Reject" 管控里）。
 *
 * <p>保留本 Receiver 的价值：一旦用户已在系统「自启动管理」里放行本应用，
 * 下列事件就能自动把主进程拉起并前台保活，省去每次开机手动点图标：
 * <ul>
 *   <li>{@code BOOT_COMPLETED} / {@code LOCKED_BOOT_COMPLETED}：开机</li>
 *   <li>{@code MY_PACKAGE_REPLACED}：安装 / 覆盖更新本 APK</li>
 *   <li>{@code com.fvocr.bridge.WAKE}：设置页或 shell 手动触发（兼容）</li>
 * </ul>
 * 主进程常驻后，FV 的 {@code ocr_bitmap} 走已运行进程分支，
 * 不再触发 {@code WakePathChecker} 的 "Unable to launch app"。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "FvOcrBridge";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        Log.i(TAG, "BootReceiver 收到 " + action + "，激活主进程引擎");
        OcrService.ensure(ctx);
    }
}
