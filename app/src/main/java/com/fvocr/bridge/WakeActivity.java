package com.fvocr.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * v2.0.2 显式激活入口，运行在模块<b>主进程</b>。
 *
 * <p><b>为什么必须有一个 Activity</b>：真机日志（LSPosed_20261108_170719.zip）显示
 * HyperOS / MIUI 把 {@code com.fvocr.bridge} 整个包的后台冷启动全禁了，
 * Service / Provider / Broadcast 三条唤醒路径全部被 {@code WakePathChecker}
 * 拒绝，连系统投递的 {@code MY_PACKAGE_REPLACED} 都拦：
 * <pre>
 * WakePathChecker: MIUILOG-AutoStart, Service/Provider/Broadcast Reject
 *   caller=com.fooview.android.fooview callee=com.fvocr.bridge
 *   classname=com.fvocr.bridge.OcrProvider wakeType=4
 *   classname=com.fvocr.bridge.OcrService wakeType=8
 *   classname=com.fvocr.bridge.BootReceiver action=com.fvocr.bridge.WAKE wakeType=2
 * BroadcastQueueInjector: Unable to launch app ... process is not permitted to wake path
 * </pre>
 * 日志标题写的是 <b>Service/Provider/Broadcast Reject</b> —— 显式
 * {@code startActivity} 不在这道管控里（同份日志里多次
 * {@code START ... result code=0}）。v2.0.1 所有组件都在 {@code :ocr} 子进程，
 * 「激活 :ocr」又需要 :ocr 先起来，成了无解的循环依赖；v2.0.2 把组件全收回
 * 主进程，只要这里被启动一次，主进程就活、Provider 就发布、前台服务就常驻。
 *
 * <p>本 Activity 透明且立即 {@code finish()}：
 * <ul>
 *   <li>{@code silent=true}（FV 侧自动兜底携带）：零 UI，只激活；</li>
 *   <li>桌面图标 / 手工 {@code am start -n com.fvocr.bridge/.WakeActivity}：
 *       弹一句提示并顺带打开设置页，让用户能看到引擎状态。</li>
 * </ul>
 *
 * <p><b>同应用、同进程直启前台服务</b>：caller == callee，且由前台 Activity 发起，
 * 既不受关联启动管控，也满足 Android 12+ 对后台 {@code startForegroundService}
 * 的限制。引擎本身仍是 lazy 的，这里不加载任何 native 库。
 */
public class WakeActivity extends Activity {

    private static final String TAG = "FvOcrBridge";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean silent = getIntent() != null
                && getIntent().getBooleanExtra(Config.EXTRA_SILENT, false);
        Log.i(TAG, "WakeActivity 启动（显式 Activity 路径，主进程），激活引擎");

        // 关键一步：同应用直启前台服务，把主进程保活。系统随后会
        // installContentProviders 发布 OcrProvider，FV 的 content call 即可落地。
        OcrService.ensure(this);

        if (!silent) {
            try {
                Toast.makeText(this, "FV 识图桥：引擎已激活（后台常驻）",
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // 个别 ROM 禁 Toast，不影响激活
            }
            try {
                startActivity(new Intent(this, SettingsActivity.class));
            } catch (Throwable t) {
                Log.w(TAG, "跳转设置页失败（不影响激活）: " + t);
            }
        }
        finish();
    }
}
