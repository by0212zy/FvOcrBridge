package com.fvocr.bridge;

/**
 * 常量集中处。Hook 点按方法签名反射查找，不绑定 FV 具体版本。
 *
 * 本模块一个 APK 同时承担两个角色：
 *   · LSPosed 模块（java_init.list）注入 FV 进程，纯 Java，不碰任何 native；
 *   · OCR 引擎宿主：自带 libmiocr.so，OcrProvider / OcrService 跑在模块【主进程】。
 *
 * v2.0.2：先前把引擎放 :ocr 子进程，而「激活 :ocr」又需要 :ocr 先启动，
 * 加上 MIUI 禁止本包一切后台冷启动，形成无解循环依赖。现全部收回主进程，
 * 由 WakeActivity（显式 Activity，不在 WakePathChecker 的
 * Service/Provider/Broadcast Reject 里）启动一次即可让 Provider 常驻。
 *
 * 两者通过 content://com.fvocr.bridge.ocr 通信：
 *   ocr_bitmap  extras{bitmap:Bitmap} → Bundle{success, text, error, source, costMs}
 *   ping / probe_libs / self_test / diag_step / ocr_screen / ocr_path（诊断与命令行）
 *
 * FV 悬浮球 1.6.4 本地 OCR 链路：
 *   z1/e.recognize(Bitmap) -> String              统一入口，返回合并后的纯文本
 *   com.baidu.paddle.lite.demo.ocr.BaiduOCREngine 百度 PaddleLite 本地实现（要下载语言包）
 *   z1/c                                         语言包未下载时的空壳（直接返回 null）
 *
 * 引擎未就绪 / 超时一律回退 FV 自带 PaddleOCR，识别功能不会变坏。
 */
final class Config {
    static final String TAG = "FvOcrBridge";

    /** 唯一作用域：FV 悬浮球。 */
    static final String FV_PKG = "com.fooview.android.fooview";

    /** 模块自身 Provider 的 authority（与 AndroidManifest 一致）。 */
    static final String CALL_URI = "content://com.fvocr.bridge.ocr";
    static final String METHOD_BITMAP = "ocr_bitmap";

    /**
     * v2.0.2：显式 Activity 激活。HyperOS / MIUI 的 WakePathChecker 会拒绝本包
     * 的一切 Service / Provider / Broadcast 冷启动（v2.0.1 的广播激活因此无效，
     * am broadcast 的 result=0 只代表广播队列处理完，Receiver 根本没执行）。
     * 显式 startActivity 不在该管控路径里，是当前唯一能可靠拉起主进程的入口。
     */
    static final String ENGINE_PKG = "com.fvocr.bridge";
    static final String ACTION_WAKE = "com.fvocr.bridge.WAKE";
    static final String CLS_SERVICE = "com.fvocr.bridge.OcrService";
    static final String CLS_RECEIVER = "com.fvocr.bridge.BootReceiver";
    static final String CLS_WAKE_ACTIVITY = "com.fvocr.bridge.WakeActivity";
    static final String CLS_LAUNCHER_ALIAS = "com.fvocr.bridge.LauncherAlias";
    /** FV 侧自动兜底启动 WakeActivity 时置 true，完全静默、不弹界面。 */
    static final String EXTRA_SILENT = "silent";
    /** 一次性激活命令（显式 Activity 路径），失败时直接打印给用户。 */
    static final String WAKE_CMD =
            "am start -n com.fvocr.bridge/.WakeActivity";

    /** Bundle 的 key（与 OcrProvider / OcrCore.Result 对齐）。 */
    static final String EXTRA_BITMAP = "bitmap";
    static final String EXTRA_SUCCESS = "success";
    static final String EXTRA_TEXT = "text";
    static final String EXTRA_ERROR = "error";

    /** 一次跨进程识别的整体超时。首次调用主进程冷启动 + nativeInit 可能较慢。 */
    static final long BRIDGE_TIMEOUT_MS = 12_000L;
    static final int MAX_RETRY = 3;
    static final long RETRY_INTERVAL_MS = 400L;
    /** 检测到引擎进程未就绪时，显式 Activity 激活后额外等待的时间。 */
    static final long WAKE_RETRY_MS = 900L;
    /** 自激活请求节流，避免每次识别都拉 Activity / 服务。 */
    static final long WAKE_THROTTLE_MS = 60_000L;
    /** 连续失败达到阈值后，短时间内不再发起 Binder 调用，避免引擎不可用时每次识别都卡住。 */
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
