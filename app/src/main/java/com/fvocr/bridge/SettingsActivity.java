package com.fvocr.bridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * LSPosed 模块设置界面（{@code MODULE_SETTINGS}，<b>无桌面图标</b>）。
 *
 * <p>打开即自动激活 {@code :ocr} 引擎进程 —— 这是绕开 MIUI
 * {@code WakePathChecker} 关联启动拦截最可靠的一次性入口：由模块自己启动
 * 自己的组件，系统不拦；进程常驻后 FV 的 {@code content call} 就能落地。
 *
 * <p>本 Activity 在<b>主进程</b>，只通过 {@code ContentResolver.call} 与
 * {@code :ocr} 通信，绝不引用 {@code OcrCore} / {@code OCREngine}，
 * 因此主进程依旧零 native 引用（沿用 v1.0.1 的启动零 native 设计）。
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "FvOcrBridge";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("FV 识图桥 · 引擎");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("FV 识图桥"));
        root.addView(body(
                "一个 APK 自带小米本地 OCR 引擎。作用域只勾 FV 悬浮球，"
                        + "引擎跑在模块自身 :ocr 子进程，全部离线。\n\n"
                        + "为什么要点一下这里：\n"
                        + "HyperOS / MIUI 的「关联启动管控」(WakePathChecker) 会阻止 FV "
                        + "悬浮球跨应用拉起本模块的 :ocr 进程，导致识别一直回退、"
                        + "日志出现 Unknown authority。由模块自己启动自己的引擎服务则不受限制。\n\n"
                        + "打开本页会自动完成一次激活；激活后引擎进程常驻，"
                        + "日常使用无需任何命令。若重启后失效，请到系统「自启动管理」"
                        + "里允许本应用，或重新打开本页。"));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(button("① 重新激活引擎", v -> {
            wake();
            setStatus("已发送激活广播，正在查询引擎状态 …");
            query("ping");
        }));
        root.addView(button("② 运行自检（内置测试图）", v -> {
            setStatus("正在 self_test（首次需加载引擎，可能数秒）…");
            query("self_test");
        }));
        root.addView(button("③ 查看引擎状态", v -> query("ping")));
        root.addView(button("④ 打开系统自启动设置", v -> openAutoStart()));

        root.addView(body(
                "等价的一次性激活命令（adb shell / Termux / MT 终端）：\n"
                        + "am broadcast --include-stopped-packages "
                        + "-a com.fvocr.bridge.WAKE -n com.fvocr.bridge/.BootReceiver\n\n"
                        + "诊断：adb logcat -s FvOcrBridge\n"
                        + "与 GalleryOCRBridge 二选一，不要同时勾选 FV 作用域。"));

        setContentView(root);

        // 打开即自动激活
        wake();
        setStatus("正在激活引擎进程 …");
        query("ping");
    }

    private void wake() {
        try {
            Intent i = new Intent(Config.ACTION_WAKE);
            i.setComponent(new ComponentName(Config.ENGINE_PKG, Config.CLS_RECEIVER));
            sendBroadcast(i);
            Log.i(TAG, "SettingsActivity 已发送 WAKE");
        } catch (Throwable t) {
            Log.w(TAG, "WAKE 发送失败: " + t);
        }
    }

    /** 在子线程里跨进程查询，避免拉起 :ocr 冷启动时卡住 UI。 */
    private void query(final String method) {
        new Thread(() -> {
            String out;
            try {
                Bundle r = getContentResolver().call(
                        Uri.parse(Config.CALL_URI), method, null, null);
                if (r == null) {
                    out = "✗ " + method + " 返回 null（:ocr 进程可能仍未被拉起）\n"
                            + "请点「① 重新激活引擎」，或执行 README 里的一次性激活命令。";
                } else if ("self_test".equals(method)) {
                    out = (r.getBoolean("success", false) ? "✓ 引擎可用" : "✗ 引擎失败")
                            + "\ntext=[" + r.getString("text", "") + "]"
                            + "\nerror=" + r.getString("error", "")
                            + "\ncostMs=" + r.getLong("costMs", 0);
                } else {
                    out = "✓ 引擎进程已在线（:ocr 常驻，FV 调用可落地）"
                            + "\nauthority=" + r.getString("authority", "")
                            + "\nprocess=" + r.getString("process", "")
                            + "\nsoLoaded=" + r.getBoolean("soLoaded", false)
                            + "\nversion=" + r.getString("version", "")
                            + "\nabi=" + r.getString("abi", "");
                }
            } catch (Throwable t) {
                out = "✗ " + method + " 异常: " + t + "\n"
                        + "常见原因：MIUI 关联启动拦截。请点「① 重新激活引擎」或"
                        + "在系统自启动管理里允许本应用。";
                Log.w(TAG, method + " failed", t);
            }
            final String text = out;
            ui.post(() -> setStatus(text));
        }, "FvOcrSettings").start();
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    private void openAutoStart() {
        // 先试 MIUI/ HyperOS 自启动管理页，再退到系统应用详情
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent("miui.intent.action.APP_AUTOSTART"));
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            setStatus("无法跳转设置页，请手动打开：设置 → 应用设置 → 授权管理 → 自启动管理");
        }
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
