package com.fvocr.bridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * LSPosed 模块设置界面（{@code MODULE_SETTINGS}），运行在模块<b>主进程</b>。
 *
 * <p>v2.0.2：本 Activity 与 {@link OcrProvider} / {@link OcrService} 同一进程。
 * 打开它就意味着主进程已活、Provider 已发布，所以这里只需要同应用直启
 * {@link OcrService} 把进程前台保活 —— caller == callee 且由前台 Activity 发起，
 * 不经过 MIUI 的 Service/Provider/Broadcast 唤醒管控，也不受 Android 12+
 * 后台 {@code startForegroundService} 限制。
 *
 * <p>桌面默认有一个「FV 识图桥」图标（{@code LauncherAlias} → {@link WakeActivity}）：
 * 一是给用户一条能点到的显式激活入口，二是让应用出现在 MIUI 自启动管理列表里
 * （日志 {@code MST_PackageCache: has not launcher icon,return} 说明无图标会被跳过）。
 * 不想要图标可在本页一键隐藏，隐藏后仍可用本页或
 * {@code am start -n com.fvocr.bridge/.WakeActivity} 激活。
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "FvOcrBridge";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button launcherBtn;

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
                        + "v2.0.2 起引擎与 Provider 都在主进程，全部离线。\n\n"
                        + "为什么要点一下：\n"
                        + "HyperOS / MIUI 的「关联启动管控」(WakePathChecker) 会拦下本包"
                        + "所有 Service / Provider / Broadcast 冷启动，连系统发给自己的"
                        + "安装完成广播都不例外，导致 FV 识别一直回退、日志出现 Unknown authority。"
                        + "显式启动 Activity 不在这道管控里 —— 点一次桌面图标，主进程即常驻，"
                        + "之后 FV 的调用直接命中已运行进程，不再触发任何唤醒检查。\n\n"
                        + "激活后引擎前台服务常驻，日常无需任何命令。若重启后失效，"
                        + "请到系统「自启动管理」里允许本应用，或再点一次桌面图标。"));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTypeface(Typeface.MONOSPACE);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setMovementMethod(new ScrollingMovementMethod());
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(button("① 立即激活引擎（保活）", v -> {
            wake();
            setStatus("已请求前台保活，正在查询引擎状态 …");
            query("ping");
        }));
        root.addView(button("② 运行自检（内置测试图）", v -> {
            setStatus("正在 self_test（首次需加载引擎，可能数秒）…");
            query("self_test");
        }));
        root.addView(button("③ 查看引擎状态", v -> query("ping")));
        root.addView(button("④ 打开系统自启动设置", v -> openAutoStart()));

        launcherBtn = button("", v -> toggleLauncher());
        root.addView(launcherBtn);
        refreshLauncherButton();

        root.addView(body(
                "一次性激活命令（adb shell / Termux / MT 终端）：\n"
                        + "am start -n com.fvocr.bridge/.WakeActivity\n\n"
                        + "引擎自检：content call --uri content://com.fvocr.bridge.ocr --method self_test\n"
                        + "诊断：adb logcat -s FvOcrBridge\n"
                        + "与 GalleryOCRBridge 二选一，不要同时勾选 FV 作用域。"));

        setContentView(root);

        // 打开即同应用直启前台服务，把主进程保活
        wake();
        setStatus("正在激活引擎进程 …");
        query("ping");
    }

    /** v2.0.2：同应用、同进程、由前台 Activity 发起，最可靠的保活方式。 */
    private void wake() {
        try {
            OcrService.ensure(this);
            Log.i(TAG, "SettingsActivity 已同应用直启 OcrService");
        } catch (Throwable t) {
            Log.w(TAG, "直启 OcrService 失败: " + t);
        }
        // 兼容保留：同应用显式广播（进程已活，一定能收到）
        try {
            Intent i = new Intent(Config.ACTION_WAKE);
            i.setComponent(new ComponentName(Config.ENGINE_PKG, Config.CLS_RECEIVER));
            sendBroadcast(i);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 在子线程里查询，避免首次加载引擎时卡住 UI。 */
    private void query(final String method) {
        new Thread(() -> {
            String out;
            try {
                Bundle r = getContentResolver().call(
                        Uri.parse(Config.CALL_URI), method, null, null);
                if (r == null) {
                    out = "✗ " + method + " 返回 null（引擎未就绪）\n"
                            + "请点「① 立即激活引擎」，或点一次桌面图标。";
                } else if ("self_test".equals(method)) {
                    out = (r.getBoolean("success", false) ? "✓ 引擎可用" : "✗ 引擎失败")
                            + "\ntext=[" + r.getString("text", "") + "]"
                            + "\nerror=" + r.getString("error", "")
                            + "\ncostMs=" + r.getLong("costMs", 0);
                } else {
                    out = "✓ 引擎进程已在线（主进程常驻，FV 调用可落地）"
                            + "\nauthority=" + r.getString("authority", "")
                            + "\nprocess=" + r.getString("process", "")
                            + "\nsoLoaded=" + r.getBoolean("soLoaded", false)
                            + "\nversion=" + r.getString("version", "")
                            + "\nabi=" + r.getString("abi", "");
                }
            } catch (Throwable t) {
                out = "✗ " + method + " 异常: " + t + "\n"
                        + "请点「① 立即激活引擎」，或执行 am start -n com.fvocr.bridge/.WakeActivity";
                Log.w(TAG, method + " failed", t);
            }
            final String text = out;
            ui.post(() -> setStatus(text));
        }, "FvOcrSettings").start();
    }

    // ------------------------------------------------------------------
    // 桌面图标（activity-alias）开关
    // ------------------------------------------------------------------
    private boolean isLauncherEnabled() {
        int s = getPackageManager().getComponentEnabledSetting(
                new ComponentName(this, Config.CLS_LAUNCHER_ALIAS));
        return s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void refreshLauncherButton() {
        if (launcherBtn != null) {
            launcherBtn.setText(isLauncherEnabled()
                    ? "⑤ 隐藏桌面图标" : "⑤ 显示桌面图标");
        }
    }

    private void toggleLauncher() {
        boolean enabled = isLauncherEnabled();
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, Config.CLS_LAUNCHER_ALIAS),
                    state, PackageManager.DONT_KILL_APP);
            refreshLauncherButton();
            setStatus(enabled
                    ? "桌面图标已隐藏。仍可从 LSPosed 打开本页，或执行 "
                            + "am start -n com.fvocr.bridge/.WakeActivity 激活。"
                    : "桌面图标已显示。点一次即可完成引擎激活。");
        } catch (Throwable t) {
            setStatus("切换图标失败: " + t);
        }
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    private void openAutoStart() {
        // 先试 MIUI / HyperOS 自启动管理页，再退到系统应用详情
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
