package com.fvocr.bridge;

import android.content.Context;

import java.lang.reflect.Method;

/** Class.forName(name, false, loader) 一律只加载不初始化，避免早期触发宿主 &lt;clinit&gt;。 */
final class Host {
    private Host() {
    }

    static Class<?> find(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前进程的 Application（FV 主进程或 :circle 识别进程均可）。 */
    static Context application() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
