package com.xiaomi.ocr.sdk_ocr;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 复刻自小米相册 classes3.dex 的 {@code com.xiaomi.ocr.sdk_ocr.OCREngine}。
 *
 * <p>这是整套 SDK 唯一的 Java 入口，逻辑极薄：
 * <pre>
 *   &lt;clinit&gt; 依次 loadLibrary("c++_shared" / "miocr" / "miocr_wrapper")
 *   init(cacheDir, libDir)  -> nativeInit 拿到 long handle
 *   doOCR(Bitmap)           -> nativeOCRBitmap(handle, bitmap) -> OCRData.OCRResult
 * </pre>
 *
 * <p><b>类名、包名、native 方法名都不能改</b>：{@code libmiocr_wrapper.so}
 * 里是标准 JNI 静态命名绑定（无 JNI_OnLoad / RegisterNatives），
 * 导出符号形如 {@code Java_com_xiaomi_ocr_sdk_1ocr_OCREngine_nativeOCRBitmap@@DOCDET_1.0}。
 *
 * <p>相对原版只做了两处无害调整：
 * <ol>
 *   <li>catch 里的 {@code com.miui.gallery.util.logger.DefaultLogger} 换成 {@link Log}；</li>
 *   <li>{@code init()} 里读取 version 加了 try-catch，避免个别机型上取版本失败影响主流程。</li>
 * </ol>
 * 相册真实调用点（OCRLocalRequestHelper$OCRLocalAsyncTask）是
 * {@code getInstance().init(getCacheDir().getPath(), null)}，第二个参数传 null 即可。
 */
public class OCREngine {

    private static final String TAG = "FvOcrBridge";
    private static final OCREngine INSTANCE = new OCREngine();
    private static boolean sIsLoadSuccess = false;

    static {
        checkAndLoadLibOCR();
    }

    private String mCacheDir = "";
    private String mLibDir = "";
    private final Object mNativeLock = new Object();
    private long mNativeObj = 0L;
    private String mVersion = "";

    private final AtomicBoolean mRegionEnable = new AtomicBoolean(false);
    private final AtomicBoolean mRegionBusy = new AtomicBoolean(false);
    private final AtomicLong mRegionNextTimestamp = new AtomicLong(-1L);

    private OCREngine() {
    }

    private static void checkAndLoadLibOCR() {
        if (sIsLoadSuccess) {
            return;
        }
        try {
            // 顺序不能变：wrapper 的 NEEDED 里有 libmiocr.so，必须先加载引擎本体。
            Log.i(TAG, "loadLibrary c++_shared ...");
            System.loadLibrary("c++_shared");
            Log.i(TAG, "loadLibrary miocr ...");
            System.loadLibrary("miocr");
            Log.i(TAG, "loadLibrary miocr_wrapper ...");
            System.loadLibrary("miocr_wrapper");
            sIsLoadSuccess = true;
            Log.i(TAG, "all so loaded");
        } catch (Throwable t) {
            sIsLoadSuccess = false;
            Log.e(TAG, "load jni failed.", t);
        }
    }

    public static OCREngine getInstance() {
        if (!sIsLoadSuccess) {
            checkAndLoadLibOCR();
        }
        return INSTANCE;
    }

    /** 供 UI 展示引擎是否可用，不触发任何 native 调用。 */
    public static boolean isLibraryLoaded() {
        return sIsLoadSuccess;
    }

    // ------------------------------------------------------------------
    // 与 libmiocr_wrapper.so 导出符号一一对应的 native 声明（全部保留，
    // 即使 demo 只用到前 4 个，也避免任何潜在的符号解析差异）。
    // ------------------------------------------------------------------
    private native String nativeGetVersion();

    private native long nativeInit(String cacheDir, String libDir);

    private native OCRData.OCRResult nativeOCRBitmap(long handle, Bitmap bitmap);

    private native OCRData.Location[] nativeOCRLineDetectBitmap(long handle, Bitmap bitmap);

    private native int nativeOCRPageOrient(long handle, Bitmap bitmap);

    private native OCRData.OCRResult nativeOCRRecognizeBitmap(long handle, Bitmap bitmap,
                                                              OCRData.Location[] locations,
                                                              boolean region);

    private native OCRData.RegionDetectResult nativeOCRRegionDetect(long handle,
                                                                    OCRData.FocusPoint point);

    private native void nativeOCRRegionDetectInit(long handle);

    private native void nativeOCRRegionDetectSetInput(Image image, int rotate);

    private native OCRData.RegionData nativeOCRRegionImage(long handle);

    private native void nativeRelease(long handle);

    /**
     * @param cacheDir 可写缓存目录，引擎用来写 OpenCL 编译缓存
     *                 （mace_cl_compiled_program.bin / orient_tmp.bin）
     * @param libDir   so 目录，相册传 null 即可（模型内嵌在 libmiocr.so 里）
     */
    public boolean init(String cacheDir, String libDir) throws IllegalStateException {
        checkAndLoadLibOCR();
        if (!sIsLoadSuccess) {
            return false;
        }
        synchronized (mNativeLock) {
            if (mNativeObj != 0L) {
                return true;
            }
            mCacheDir = cacheDir;
            mLibDir = libDir;
            Log.i(TAG, "nativeInit BEGIN cacheDir=" + cacheDir + " libDir=" + libDir);
            mNativeObj = nativeInit(cacheDir, libDir);
            Log.i(TAG, "nativeInit END handle=" + mNativeObj);
            if (mNativeObj == 0L) {
                Log.e(TAG, "init: nativeInit returned 0, cacheDir=" + cacheDir);
                return false;
            }
        }
        // 不再自动调用 nativeGetVersion：版本只是可选信息，且个别机型上该 JNI
        // 调用可能直接崩溃（Java 的 try-catch 拦不住 native 崩溃）。
        // 版本改由调用方按需单独查询，界面「分步诊断」把它拆成独立的一步。
        mVersion = "";
        Log.i(TAG, "init ok: cachePath=" + cacheDir + " (version query skipped)");
        return true;
    }

    /** 整图 OCR，返回 {@link OCRData.OCRResult#total_text}。 */
    public OCRData.OCRResult doOCR(Bitmap bitmap) throws IllegalStateException {
        if (!sIsLoadSuccess) {
            throw new IllegalStateException("ocr so load failed");
        }
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        synchronized (mNativeLock) {
            if (mNativeObj == 0L) {
                mNativeObj = nativeInit(mCacheDir, mLibDir);
            }
            if (mNativeObj == 0L) {
                Log.e(TAG, "doOCR: OCR init failed!");
                return null;
            }
            Log.i(TAG, "nativeOCRBitmap BEGIN " + bitmap.getWidth() + "x"
                    + bitmap.getHeight() + " cfg=" + bitmap.getConfig());
            OCRData.OCRResult result = nativeOCRBitmap(mNativeObj, bitmap);
            Log.i(TAG, "nativeOCRBitmap END result="
                    + (result == null ? "null" : "ok"));
            return result;
        }
    }

    public String version() throws IllegalStateException {
        checkAndLoadLibOCR();
        if (!sIsLoadSuccess) {
            return "";
        }
        return nativeGetVersion();
    }

    public String getCachedVersion() {
        return mVersion;
    }

    public void release() {
        if (!sIsLoadSuccess) {
            return;
        }
        synchronized (mNativeLock) {
            if (mNativeObj != 0L) {
                nativeRelease(mNativeObj);
                mNativeObj = 0L;
            }
        }
    }
}
