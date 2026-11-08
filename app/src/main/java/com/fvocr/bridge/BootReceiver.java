package com.fvocr.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 引擎进程自激活入口，运行在 {@code :ocr} 进程。
 *
 * <p>系统投递这些广播时就已经把 {@code :ocr} 进程拉起来了，再用
 * {@link OcrService} 前台保活即可。广播来源都是<b>系统或模块自身</b>
 * （不是 FV 悬浮球），所以不会被 MIUI 的关联启动管控拦截：
 * <ul>
 *   <li>{@code BOOT_COMPLETED} / {@code LOCKED_BOOT_COMPLETED}：开机（需自启动权限）</li>
 *   <li>{@code MY_PACKAGE_REPLACED}：安装 / 覆盖更新本 APK 时系统自动投递</li>
 *   <li>{@code com.fvocr.bridge.WAKE}：模块设置界面或 shell 手动触发</li>
 * </ul>
 *
 * <p>任意一条命中，{@code :ocr} 就常驻，FV 侧的 {@code ocr_bitmap} 调用
 * 便不再触发 {@code WakePathChecker} 的 "Unable to launch app"。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "FvOcrBridge";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        Log.i(TAG, "BootReceiver 收到 " + action + "，激活 :ocr 引擎进程");
        OcrService.ensure(ctx);
    }
}
