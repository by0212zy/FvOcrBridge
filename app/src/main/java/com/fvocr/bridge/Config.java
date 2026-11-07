package com.fvocr.bridge;

/**
 * 常量集中处。Hook 点按方法签名反射查找，不绑定 FV 具体版本。
 *
 * 独立 OCR 引擎（MiOcrStandalone v1.0.3+）对外接口：
 *   authority  com.example.miocr.standalone.ocr   （exported=true，无权限，跑在 :ocr 进程）
 *   method     ocr_bitmap  extras{bitmap:Bitmap} → Bundle{success, text, error, source, costMs}
 *   method     ping        → Bundle{success=true, soLoaded, version, ...}
 *   URI        /ocr_input  → content write 内存管道，配合 ocr_screen 供命令行使用
 *
 * FV 悬浮球 1.6.4 本地 OCR 链路：
 *   z1/e.recognize(Bitmap) -> String              统一入口，返回合并后的纯文本
 *   com.baidu.paddle.lite.demo.ocr.BaiduOCREngine 百度 PaddleLite 本地实现（要下载语言包）
 *   z1/c                                         语言包未下载时的空壳（直接返回 null）
 *
 * 本模块只做「客户端」：把 FV 已截好的 Bitmap 一次 Binder 送给独立引擎，
 * 取回 text 作为 recognize() 的返回值。引擎未装 / 未就绪 / 超时一律回退 FV 自带
 * PaddleOCR，识别功能不会变坏。
 */
final class Config {
    static final String TAG = "FvOcrBridge";

    /** 唯一作用域：FV 悬浮球。 */
    static final String FV_PKG = "com.fooview.android.fooview";

    /** 独立 OCR 引擎 App 的包名与对外 Provider。 */
    static final String OCR_PKG = "com.example.miocr.standalone";
    static final String CALL_URI = "content://com.example.miocr.standalone.ocr";
    static final String METHOD_BITMAP = "ocr_bitmap";

    /** Bundle 的 key（与 OcrProvider / OcrCore.Result 对齐）。 */
    static final String EXTRA_BITMAP = "bitmap";
    static final String EXTRA_SUCCESS = "success";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_ERROR = "error";

    /** 一次跨进程识别的整体超时。首次调用 :ocr 冷启动 + nativeInit 可能较慢。 */
    static final long BRIDGE_TIMEOUT_MS = 10_000L;
    static final int MAX_RETRY = 2;
    static final long RETRY_INTERVAL_MS = 400L;
    /** 连续失败达到阈值后，短时间内不再发起 Binder 调用，避免引擎未装时每次识别都卡住。 */
    static final int FAIL_THRESHOLD = 2;
    static final long COOL_DOWN_MS = 30_000L;

    /** FV 里可能实现 recognize(Bitmap)->String 的类；按签名匹配，不写死方法。 */
    static final String[] FV_ENGINE_CLASSES = {
            "com.baidu.paddle.lite.demo.ocr.BaiduOCREngine",
            "z1.c",
            "z1.e",
    };

    private Config() {
    }
}
