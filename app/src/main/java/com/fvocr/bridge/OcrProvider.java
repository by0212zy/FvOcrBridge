package com.fvocr.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.xiaomi.ocr.sdk_ocr.OCREngine;
import com.xiaomi.ocr.sdk_ocr.OCRData;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * 对外入口：本模块自带的本地 OCR，供被注入的 FV 进程、shell 命令与其它 App 调用。
 *
 * <p><b>本 Provider 运行在模块自身的 :ocr 子进程</b>（AndroidManifest 的
 * android:process）。所有 native 调用都只发生在这里，与本 APK 注入 FV 进程的
 * LSPosed 模块代码完全隔离。MACE / MNN 的 glog CHECK 失败会直接 abort()
 * （SIGABRT），Java 拦不住；进程隔离后崩溃只杀掉 :ocr 子进程，
 * FV 悬浮球与模块主进程都不受影响。
 *
 * <pre>
 * # 一条命令提取当前屏幕文字（截屏走内存管道，不落盘）
 * screencap -p | content write --uri content://com.fvocr.bridge.ocr/ocr_input \
 *   &amp;&amp; content call --uri content://com.fvocr.bridge.ocr --method ocr_screen
 *
 * # 识别已有图片
 * content call --uri content://com.fvocr.bridge.ocr --method ocr_path \
 *   --extra path:s:/sdcard/a.png
 *
 * # 内置测试图自检（一步完成 init + 推理，不需要外部图片）
 * content call --uri content://com.fvocr.bridge.ocr --method self_test
 *
 * # 引擎状态
 * content call --uri content://com.fvocr.bridge.ocr --method ping
 *
 * # 探测 vendor 库可见性（验证 uses-native-library 是否生效）
 * content call --uri content://com.fvocr.bridge.ocr --method probe_libs
 * </pre>
 *
 * <p><b>v1.0.3 关键修复</b>：Android 12+ 普通 App 必须声明
 * {@code uses-native-library} 才能 dlopen vendor 私有库。Manifest 已照抄小米
 * 相册的 23 个声明 + 3 个 MACE 硬编码 OpenCL 实现库。此前缺失导致
 * {@code clGetPlatformIDs} 返回 0 平台 → MACE glog CHECK 失败 → abort()。
 */
public class OcrProvider extends ContentProvider {

    private static final String TAG = "FvOcrBridge";
    private static final int CODE_INPUT = 1;
    private static final Object LOCK = new Object();
    /** 最近一次通过 ocr_input 管道写入的图片字节（在 :ocr 进程内）。 */
    private static byte[] sPendingImage = null;

    private UriMatcher mUriMatcher;

    @Override
    public boolean onCreate() {
        mUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mUriMatcher.addURI(OcrCore.AUTHORITY, "ocr_input", CODE_INPUT);
        // 绝不在 onCreate 里碰 native：nativeInit 会探测 OpenCL / APU 后端，
        // 在普通 App 进程里可能直接 abort() 杀进程。引擎完全 lazy。
        Log.i(TAG, "provider ready in :ocr process (engine NOT initialized, lazy)");
        return true;
    }

    // ------------------------------------------------------------------
    // call：同步返回 Bundle
    // ------------------------------------------------------------------
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Log.i(TAG, "call method=" + method + " arg=" + arg);
        if (method == null) {
            return toBundle(OcrCore.Result.fail("method 为空", "call"));
        }
        switch (method) {
            case "ping":
                return ping();
            case "probe_libs":
                return probeLibs();
            case "self_test":
                // 内置测试图一步完成 init + 推理，装上即可验证引擎，不需要外部图片
                return toBundle(OcrCore.recognize(getContext(),
                        SampleBitmap.make(), "self_test"));
            case "ocr_screen":
                return ocrScreen();
            case "ocr_path": {
                String path = extras != null ? extras.getString("path") : null;
                if (path == null) path = arg;
                return toBundle(OcrCore.recognizePath(getContext(), path, "ocr_path"));
            }
            case "ocr_bytes": {
                byte[] data = extras != null ? extras.getByteArray("bytes") : null;
                return toBundle(OcrCore.recognize(getContext(), data, "ocr_bytes"));
            }
            case "ocr_bitmap": {
                Bitmap bmp = extras != null ? (Bitmap) extras.getParcelable("bitmap") : null;
                return toBundle(OcrCore.recognize(getContext(), bmp, "ocr_bitmap"));
            }
            // ---- 分步诊断：每步一次 Binder 调用，崩在哪一步一目了然 ----
            case "diag_reset":
                return diagReset();
            case "diag_step":
                return diagStep(arg);
            default:
                return toBundle(OcrCore.Result.fail("未知 method: " + method, "call"));
        }
    }

    private Bundle ping() {
        Bundle b = new Bundle();
        b.putBoolean("success", true);
        b.putBoolean("soLoaded", OcrCore.isSoLoaded());
        b.putString("version", OcrCore.getEngineVersion());
        b.putString("cacheDir", OcrCore.getCacheDirPath());
        b.putString("abi", android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "unknown");
        b.putString("authority", OcrCore.AUTHORITY);
        b.putString("process", ":ocr");
        b.putString("libPreload", OcrCore.getLibPreload());
        return b;
    }

    /**
     * v1.0.3：只读探测 vendor 库可见性（不初始化引擎、不进推理）。
     * 用于验证 Manifest 里的 uses-native-library 声明是否真的生效。
     */
    private Bundle probeLibs() {
        Bundle b = new Bundle();
        b.putBoolean("success", true);
        b.putString("report", LibProbe.report());
        b.putString("preload", LibProbe.preloadOpenCL());
        b.putString("process", ":ocr");
        return b;
    }

    private Bundle ocrScreen() {
        byte[] data;
        synchronized (LOCK) {
            if (sPendingImage == null) {
                try {
                    LOCK.wait(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            data = sPendingImage;
            sPendingImage = null;
        }
        if (data == null || data.length == 0) {
            return toBundle(OcrCore.Result.fail(
                    "未收到截图：请先执行 content write ... /ocr_input", "ocr_screen"));
        }
        return toBundle(OcrCore.recognize(getContext(), data, "ocr_screen"));
    }

    // ------------------------------------------------------------------
    // 分步诊断（由 shell 逐次调用；每步前后同步刷盘）
    // ------------------------------------------------------------------
    private Bundle diagReset() {
        DiagLog.clear(getContext());
        Bundle b = new Bundle();
        b.putBoolean("ok", true);
        b.putString("detail", "诊断日志已清空");
        return b;
    }

    private Bundle diagStep(String which) {
        Bundle b = new Bundle();
        b.putString("step", which == null ? "" : which);
        String name;
        try {
            if ("0".equals(which)) {
                name = "⓪ vendor 库可见性 / 预加载 libOpenCL.so";
                DiagLog.step(getContext(), name);
                String report = LibProbe.report();
                String pre = LibProbe.preloadOpenCL();
                DiagLog.ok(getContext(), name,
                        pre.replace('\n', ' ').trim());
                b.putBoolean("ok", true);
                b.putString("detail", pre.replace('\n', ' ').trim());
                b.putString("report", report);
            } else if ("1".equals(which)) {
                name = "① System.loadLibrary + so .init_array";
                DiagLog.step(getContext(), name);
                boolean loaded = OCREngine.isLibraryLoaded();
                DiagLog.ok(getContext(), name, "soLoaded=" + loaded);
                b.putBoolean("ok", loaded);
                b.putString("detail", "soLoaded=" + loaded
                        + (loaded ? "" : "（so 加载失败：非 arm64 / 缺库）"));
            } else if ("2".equals(which)) {
                name = "② nativeInit(cacheDir, null)";
                DiagLog.step(getContext(), name);
                boolean ok = OcrCore.init(getContext());
                DiagLog.ok(getContext(), name, "init=" + ok);
                b.putBoolean("ok", ok);
                b.putString("detail", "init=" + ok);
            } else if ("3".equals(which)) {
                name = "③ nativeGetVersion";
                DiagLog.step(getContext(), name);
                String v = OCREngine.getInstance().version();
                DiagLog.ok(getContext(), name, "version=" + v);
                b.putBoolean("ok", true);
                b.putString("detail", "version=" + v);
            } else if ("4".equals(which)) {
                name = "④ nativeOCRBitmap(内置测试图)";
                DiagLog.step(getContext(), name);
                OCRData.OCRResult r = OCREngine.getInstance().doOCR(SampleBitmap.make());
                String text = (r == null || r.total_text == null) ? "(null)" : r.total_text;
                DiagLog.ok(getContext(), name, "text=[" + text.replace('\n', ' ') + "]");
                DiagLog.done(getContext());
                b.putBoolean("ok", true);
                b.putString("detail", "识别文字=[" + text.replace('\n', ' ') + "]");
            } else {
                b.putBoolean("ok", false);
                b.putString("detail", "未知步骤: " + which);
                return b;
            }
            b.putString("label", name);
        } catch (Throwable t) {
            Log.e(TAG, "diagStep " + which + " failed", t);
            b.putBoolean("ok", false);
            b.putString("detail", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return b;
    }

    private static Bundle toBundle(OcrCore.Result r) {
        Bundle b = new Bundle();
        b.putBoolean("success", r.success);
        b.putString("text", r.text == null ? "" : r.text);
        b.putString("error", r.error == null ? "" : r.error);
        b.putString("source", r.source == null ? "" : r.source);
        b.putLong("costMs", r.costMs);
        return b;
    }

    // ------------------------------------------------------------------
    // 内存管道：screencap -p | content write --uri .../ocr_input
    // ------------------------------------------------------------------
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mUriMatcher.match(uri) != CODE_INPUT) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        return inputPipe();
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (mUriMatcher.match(uri) != CODE_INPUT) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        // content write 走的是 openAssetFile，长度未知用 -1。
        return new AssetFileDescriptor(inputPipe(), 0, -1);
    }

    private ParcelFileDescriptor inputPipe() throws FileNotFoundException {
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("createPipe failed: " + e.getMessage());
        }
        final ParcelFileDescriptor readSide = pipe[0];
        synchronized (LOCK) {
            sPendingImage = null;
        }
        new Thread(() -> {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readSide)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                }
            } catch (java.io.IOException e) {
                Log.w(TAG, "pipe read error", e);
            }
            byte[] data = bos.toByteArray();
            synchronized (LOCK) {
                sPendingImage = data;
                LOCK.notifyAll();
            }
            Log.i(TAG, "ocr_input received " + data.length + " bytes");
        }, "ocr-input-reader").start();
        return pipe[1];
    }

    // ------------------------------------------------------------------
    // 标准 ContentProvider 模板，本模块只用 call / openFile，不需要表。
    // ------------------------------------------------------------------
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
