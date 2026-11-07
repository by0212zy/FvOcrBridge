package com.fvocr.bridge;

import android.os.Build;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口（META-INF/xposed/java_init.list）。
 *
 * 严格按包名分流：只在 com.fooview.android.fooview 里安装 FvOcrHook，
 * 同进程内被 LSPosed 回调的其它包（gms / webview 等）一律跳过。
 *
 * 不 Hook 小米相册、不修改系统属性、不改 Settings 数据库、不联网、
 * 不打包任何第三方 OCR / so / 模型。OCR 引擎由独立 App MiOcrStandalone 提供。
 */
public class ModuleMain extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private volatile String processName = "?";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Diag.bind(this);
        processName = param.getProcessName();
        Diag.i(this, "════ FvOcrBridge 载入 ════ 进程=" + processName
                + " 框架=" + getFrameworkName() + " API=" + getApiVersion()
                + " 设备=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " SDK=" + Build.VERSION.SDK_INT
                + " 引擎URI=" + Config.CALL_URI);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String pkg = param.getPackageName();
        if (!Config.FV_PKG.equals(pkg)) {
            return;
        }
        final String proc = processName;
        final ClassLoader loader = param.getClassLoader();
        if (loader == null) {
            return;
        }
        if (!INSTALLED.add(proc + "|" + pkg + "|" + System.identityHashCode(loader))) {
            return;
        }

        Diag.i(this, "onPackageReady 进程=" + proc + " 包=" + pkg);
        try {
            FvOcrHook.install(this, loader, pkg);
        } catch (Throwable t) {
            Diag.e(this, "安装 Hook 失败: " + pkg + "/" + proc, t);
        }
    }
}
