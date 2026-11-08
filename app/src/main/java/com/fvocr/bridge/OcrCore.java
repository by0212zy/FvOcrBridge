package com.fvocr.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import com.xiaomi.ocr.sdk_ocr.OCREngine;
import com.xiaomi.ocr.sdk_ocr.OCRData;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;

/**
 * 把 OCREngine 包一层，做成模块内部可直接调用的本地 OCR。
 *
 * <p><b>v2.0.2：本类只在模块主进程被调用</b>。注入 FV 进程的 LSPosed 模块代码
 * 不引用本类、不加载任何 native 库（纯 Java 转发）。引擎收回主进程后，
 * native 崩溃会杀掉主进程；v1.0.3 已修掉导致 MACE abort() 的
 * OpenCL / uses-native-library namespace 根因，且每次进入 native 之前
 * 仍用 {@link DiagLog} 同步刷盘，万一 SIGABRT / SIGSEGV 仍能从落盘日志
 * 读到精确的崩溃步骤。
 */
public final class OcrCore {

    public static final String AUTHORITY = "com.fvocr.bridge.ocr";
    public static final String URI_CALL = "content://" + AUTHORITY;
    public static final String URI_INPUT = URI_CALL + "/ocr_input";

    private static final String TAG = "FvOcrBridge";
    /** 超过 800 万像素按比例缩小，防止大图 OOM / 推理过慢（截屏一般远小于此）。 */
    private static final long MAX_PIXELS = 8_000_000L;

    private static boolean sInited = false;
    private static boolean sInitOk = false;
    private static String sCacheDir = "";
    /** v1.0.3：nativeInit 之前先主动加载 OpenCL ICD，结果留作诊断。 */
    private static String sLibPreload = "";

    private OcrCore() {
    }

    public static final class Result {
        public boolean success;
        public String text = "";
        public String error = "";
        public String source = "";
        public long costMs;

        public static Result fail(String msg, String source) {
            Result r = new Result();
            r.success = false;
            r.error = msg == null ? "" : msg;
            r.source = source;
            return r;
        }
    }

    /** 幂等初始化；cacheDir 单独建子目录，引擎在里面写 OpenCL 编译缓存。 */
    public static synchronized boolean init(Context ctx) {
        if (sInited) {
            return sInitOk;
        }
        sInited = true;
        Context app = ctx.getApplicationContext();
        File dir = new File(app.getCacheDir(), "miocr");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot mkdir " + dir);
        }
        sCacheDir = dir.getAbsolutePath();

        // v1.0.3：在 nativeInit 之前主动把 libOpenCL.so 加载进本进程。
        // uses-native-library 声明已把 vendor 库放进 linker namespace；提前 load
        // 一次可让 MACE 内部对同 SONAME 的 dlopen 直接复用已加载的 handle，
        // 同时把「声明是否生效」的结果落盘，便于定位。
        DiagLog.step(ctx, "⓪ 预加载 libOpenCL.so（验证 uses-native-library）");
        try {
            sLibPreload = LibProbe.preloadOpenCL();
            Log.i(TAG, "preload result:\n" + sLibPreload);
            DiagLog.ok(ctx, "⓪ 预加载 libOpenCL.so（验证 uses-native-library）",
                    sLibPreload.replace('\n', ' ').trim());
        } catch (Throwable t) {
            sLibPreload = "preload threw " + t.getClass().getSimpleName();
            Log.w(TAG, sLibPreload, t);
            DiagLog.ok(ctx, "⓪ 预加载 libOpenCL.so（验证 uses-native-library）", sLibPreload);
        }

        try {
            sInitOk = OCREngine.getInstance().init(sCacheDir, null);
        } catch (Throwable t) {
            sInitOk = false;
            Log.e(TAG, "engine init failed", t);
        }
        Log.i(TAG, "init: ok=" + sInitOk + " cacheDir=" + sCacheDir
                + " soLoaded=" + OCREngine.isLibraryLoaded());
        return sInitOk;
    }

    /** v1.0.3：供界面 / ping 展示的库预加载结果。 */
    public static String getLibPreload() {
        return sLibPreload;
    }

    public static boolean isSoLoaded() {
        return OCREngine.isLibraryLoaded();
    }

    public static String getEngineVersion() {
        try {
            String v = OCREngine.getInstance().getCachedVersion();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    public static String getCacheDirPath() {
        return sCacheDir;
    }

    /** 从内存字节（截屏 PNG / 分享图片）识别。 */
    public static Result recognize(Context ctx, byte[] pngOrJpeg, String source) {
        if (pngOrJpeg == null || pngOrJpeg.length == 0) {
            return Result.fail("图片数据为空", source);
        }
        long start = System.currentTimeMillis();
        try {
            int orientation = readOrientation(pngOrJpeg);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw = BitmapFactory.decodeByteArray(pngOrJpeg, 0, pngOrJpeg.length, opt);
            if (raw == null) {
                return Result.fail("图片解码失败（不是有效的 PNG/JPEG？）", source);
            }
            Bitmap bmp = applyOrientation(raw, orientation);
            return doRecognize(ctx, bmp, source, start);
        } catch (Throwable t) {
            Log.e(TAG, "recognize(bytes) failed", t);
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage(), source);
        }
    }

    /** 从文件路径识别。 */
    public static Result recognizePath(Context ctx, String path, String source) {
        if (path == null || path.isEmpty()) {
            return Result.fail("缺少 path 参数", source);
        }
        long start = System.currentTimeMillis();
        try {
            File f = new File(path);
            if (!f.canRead()) {
                return Result.fail("文件不可读: " + path, source);
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw = BitmapFactory.decodeFile(path, opt);
            if (raw == null) {
                return Result.fail("图片解码失败: " + path, source);
            }
            int orientation = readOrientation(path);
            Bitmap bmp = applyOrientation(raw, orientation);
            return doRecognize(ctx, bmp, source, start);
        } catch (Throwable t) {
            Log.e(TAG, "recognizePath failed", t);
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage(), source);
        }
    }

    /** 直接传 Bitmap（App 内调用 / 其它 App 通过 Bundle 传图）。 */
    public static Result recognize(Context ctx, Bitmap src, String source) {
        long start = System.currentTimeMillis();
        if (src == null || src.isRecycled()) {
            return Result.fail("Bitmap 为空或已回收", source);
        }
        try {
            return doRecognize(ctx, normalize(src), source, start);
        } catch (Throwable t) {
            Log.e(TAG, "recognize(bitmap) failed", t);
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage(), source);
        }
    }

    private static Result doRecognize(Context ctx, Bitmap bmp, String source, long start) {
        // 逐步同步刷盘：native 崩溃后靠这些标记定位。
        DiagLog.clear(ctx);
        DiagLog.step(ctx, "① init() → System.loadLibrary + nativeInit(cacheDir, null)");
        if (!init(ctx)) {
            DiagLog.ok(ctx, "① init() → System.loadLibrary + nativeInit(cacheDir, null)",
                    "返回 false，未进推理");
            return Result.fail("OCR 引擎初始化失败（so 未加载或非 arm64 设备）", source);
        }
        DiagLog.ok(ctx, "① init() → System.loadLibrary + nativeInit(cacheDir, null)", "true");

        try {
            DiagLog.step(ctx, "② doOCR() → nativeOCRBitmap（MACE 推理）");
            OCRData.OCRResult r = OCREngine.getInstance().doOCR(bmp);
            DiagLog.ok(ctx, "② doOCR() → nativeOCRBitmap（MACE 推理）",
                    r == null ? "返回 null" : "返回 OCRResult");
            Result out = new Result();
            out.costMs = System.currentTimeMillis() - start;
            out.source = source;
            if (r == null) {
                out.success = false;
                out.error = "引擎返回 null（图片过大或格式不支持）";
                DiagLog.done(ctx);
                return out;
            }
            String text = r.total_text;
            if (text == null && r.paragraphs != null) {
                StringBuilder sb = new StringBuilder();
                for (OCRData.OCRParagraph p : r.paragraphs) {
                    if (p != null && p.paragraph_text != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(p.paragraph_text);
                    }
                }
                text = sb.toString();
            }
            out.text = text == null ? "" : text.trim();
            out.success = true;
            DiagLog.done(ctx);
            return out;
        } catch (Throwable t) {
            DiagLog.ok(ctx, "② doOCR() → nativeOCRBitmap（MACE 推理）",
                    "Java 异常 " + t.getClass().getSimpleName());
            DiagLog.done(ctx);
            Log.e(TAG, "doOCR failed", t);
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage(), source);
        }
    }

    /**
     * native 层用 AndroidBitmap_lockPixels 读像素，只接受 CPU 可读的 ARGB_8888；
     * HARDWARE / RGBA_F16 必须转换，超大图等比缩小。
     */
    private static Bitmap normalize(Bitmap src) {
        Bitmap bmp = src;
        if (bmp.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap out = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(),
                    Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(bmp, 0, 0, null);
            bmp = out;
        }
        long pixels = (long) bmp.getWidth() * bmp.getHeight();
        if (pixels > MAX_PIXELS) {
            double scale = Math.sqrt((double) MAX_PIXELS / (double) pixels);
            int w = Math.max(1, (int) (bmp.getWidth() * scale));
            int h = Math.max(1, (int) (bmp.getHeight() * scale));
            bmp = Bitmap.createScaledBitmap(bmp, w, h, true);
        }
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
        }
        return bmp;
    }

    private static int readOrientation(byte[] data) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static int readOrientation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap src, int orientation) {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return src;
        }
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            default: return src;
        }
        try {
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            return src;
        }
    }
}
