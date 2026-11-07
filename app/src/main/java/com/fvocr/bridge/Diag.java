package com.fvocr.bridge;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/** 运行时诊断：同时写 LSPosed 模块日志与系统 logcat，筛 FvOcrBridge。 */
final class Diag {

    private static volatile XposedModule bound;

    private Diag() {
    }

    static void bind(XposedModule m) {
        bound = m;
    }

    static void s(String msg) {
        XposedModule m = bound;
        if (m != null) {
            i(m, msg);
        }
        Log.i(Config.TAG, msg);
    }

    static void i(XposedModule m, String msg) {
        try {
            m.log(Log.INFO, Config.TAG, msg);
        } catch (Throwable ignored) {
            // 日志异常不能影响 Hook
        }
    }

    static void w(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.WARN, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    static void e(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.ERROR, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
