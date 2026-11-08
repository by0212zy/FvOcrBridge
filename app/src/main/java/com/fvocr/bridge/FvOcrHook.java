package com.fvocr.bridge;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * FV 悬浮球侧（客户端）：把它的「自动文字识别」无感转发给本模块自带的 OCR 引擎。
 *
 * 一个 APK 两个进程：这里的代码被 LSPosed 注入 FV 进程（纯 Java、不碰 native），
 * 引擎跑在模块自身的 :ocr 子进程，两边用 ContentProvider.call 通信。
 * 小米相册完全不参与，作用域只勾 FV 一个包。
 *
 * 链路：
 *   Hook FV 里所有「(Bitmap) → String、非抽象」的 recognize 方法
 *     → Bundle 直传 Bitmap（ashmem，不受 1MB Binder 事务限制）
 *     → content call content://com.fvocr.bridge.ocr / ocr_bitmap
 *     → :ocr 子进程里的 OCREngine.doOCR(Bitmap) → total_text
 *     → 文本作为 recognize() 的返回值交回 FV
 *
 * 两级保险：成功就用内置引擎结果；引擎未就绪 / 超时 / 返回失败，
 * 一律 chain.proceed() 回退到 FV 自带 PaddleOCR，识别功能永不失效。
 */
final class FvOcrHook {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "FvOcrBridge");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicInteger FAIL_STREAK = new AtomicInteger();
    private static volatile long sSkipUntil = 0L;

    private FvOcrHook() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        int n = 0;
        // 只按签名匹配，不写死方法所在类，兼容 FV 不同版本的混淆差异。
        for (String cn : Config.FV_ENGINE_CLASSES) {
            Class<?> c = Host.find(loader, cn);
            if (c == null) {
                Diag.s("跳过未找到的引擎类 " + cn);
                continue;
            }
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                Diag.s("读取 " + cn + " 方法表失败: " + t);
                continue;
            }
            for (Method m : methods) {
                if (!"recognize".equals(m.getName())
                        || Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps;
                try {
                    ps = m.getParameterTypes();
                } catch (Throwable t) {
                    continue;
                }
                if (ps.length != 1 || ps[0] != Bitmap.class
                        || !String.class.equals(m.getReturnType())) {
                    continue;
                }
                if (hookOne(module, m)) {
                    n++;
                }
            }
        }
        Diag.i(module, pkg + ": FV 本地识别已接管 " + n
                + " 处（0 处请检查 FV 版本或混淆类名）");
    }

    private static boolean hookOne(XposedModule module, Method m) {
        try {
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Bitmap bm = null;
                        try {
                            for (Object o : chain.getArgs()) {
                                if (o instanceof Bitmap) {
                                    bm = (Bitmap) o;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 取参失败就回退
                        }
                        if (bm == null || bm.isRecycled()) {
                            return chain.proceed();
                        }
                        String text = bridge(bm);
                        if (text != null && !text.isEmpty()) {
                            Diag.s("接管成功，独立 OCR 文本长度=" + text.length());
                            return text;
                        }
                        // 引擎不可用 / 未装 / 超时 / 图片确实无字 → 回退 FV 自带识别
                        return chain.proceed();
                    });
            try {
                module.deoptimize(m);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            return true;
        } catch (Throwable t) {
            Diag.w(module, "hook " + m.getDeclaringClass().getName()
                    + ".recognize 失败", t);
            return false;
        }
    }

    /** 用独立线程做同步 Binder 调用并限时，避免 :ocr 冷启动拖死 FV 的工作线程。 */
    private static String bridge(Bitmap bm) {
        if (System.currentTimeMillis() < sSkipUntil) {
            return null;
        }
        Future<String> f;
        try {
            f = POOL.submit(new Bridge(bm));
        } catch (Throwable t) {
            Diag.s("桥接提交失败: " + t);
            return null;
        }
        try {
            String r = f.get(Config.BRIDGE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (r == null) {
                onFailure();
            } else {
                onSuccess();
            }
            return r;
        } catch (Throwable t) {
            f.cancel(true);
            Diag.s("桥接超时 / 异常（回退 FV 自带识别）: " + t);
            onFailure();
            return null;
        }
    }

    private static void onSuccess() {
        FAIL_STREAK.set(0);
        sSkipUntil = 0L;
    }

    private static void onFailure() {
        int n = FAIL_STREAK.incrementAndGet();
        if (n >= Config.FAIL_THRESHOLD) {
            sSkipUntil = System.currentTimeMillis() + Config.COOL_DOWN_MS;
            Diag.s("连续 " + n + " 次未取到内置 OCR，"
                    + (Config.COOL_DOWN_MS / 1000) + " 秒内直接回退 FV 自带识别；"
                    + "请执行 content call --uri content://com.fvocr.bridge.ocr --method self_test 检查引擎");
        }
    }

    private static final class Bridge implements Callable<String> {
        private final Bitmap src;

        Bridge(Bitmap b) {
            src = b;
        }

        @Override
        public String call() {
            Context ctx = Host.application();
            if (ctx == null) {
                Diag.s("拿不到 Application");
                return null;
            }
            ContentResolver cr = ctx.getContentResolver();

            // HARDWARE / mutable Bitmap 不适合跨 Binder 用 ashmem 传，复制成只读软件副本
            Bitmap soft = softwareCopy(src);
            try {
                for (int i = 0; i < Config.MAX_RETRY; i++) {
                    try {
                        Bundle args = new Bundle();
                        args.putParcelable(Config.EXTRA_BITMAP, soft == null ? src : soft);
                        Bundle r = cr.call(Uri.parse(Config.CALL_URI),
                                Config.METHOD_BITMAP, null, args);
                        if (r != null && r.getBoolean(Config.EXTRA_SUCCESS, false)) {
                            String t = r.getString(Config.EXTRA_TEXT, "");
                            Diag.s("ocr_bitmap 成功，文本长度="
                                    + (t == null ? 0 : t.length()));
                            // 引擎正常但图片无字时返回空串：不计失败，交回 FV 自行回退
                            return t == null ? "" : t;
                        }
                        Diag.s("引擎返回失败（第" + (i + 1) + "次）: "
                                + (r == null ? "null Bundle"
                                : r.getString(Config.EXTRA_ERROR)));
                    } catch (Throwable t) {
                        Diag.s("ocr_bitmap 第" + (i + 1) + "次异常: " + t);
                    }
                    if (!sleep(Config.RETRY_INTERVAL_MS)) {
                        break;
                    }
                }
            } finally {
                if (soft != null && !soft.isRecycled()) {
                    soft.recycle();
                }
            }
            return null;
        }
    }

    private static Bitmap softwareCopy(Bitmap b) {
        try {
            boolean hardware = Build.VERSION.SDK_INT >= 26
                    && b.getConfig() == Bitmap.Config.HARDWARE;
            if (!hardware && !b.isMutable()) {
                return null;
            }
            return b.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return false 表示线程被中断，应停止重试。 */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
