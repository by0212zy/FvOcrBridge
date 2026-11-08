package com.fvocr.bridge;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 在 {@code :ocr} 子进程里探测 vendor 原生库（尤其 {@code libOpenCL.so}）的可见性。
 *
 * <p><b>为什么需要它</b>：v1.0.2 的 tombstone 明确给出
 * {@code opencl_executor.cc:237 Check failed: !all_platforms.empty() No OpenCL platforms found}
 * —— MACE 的 OpenCL 后端拿不到 GPU platform，根因是普通 App 的 linker namespace
 * 不允许 dlopen vendor 私有库。v1.0.3 在 Manifest 里补齐了
 * {@code uses-native-library} 声明，本类用来验证声明是否真的生效。
 *
 * <p>本类除 {@link #preloadOpenCL()} 外全是纯 Java 文件读取，零 native 风险；
 * 即使 preload 触发 native 崩溃，也只会杀掉 {@code :ocr} 子进程。
 */
public final class LibProbe {

    private static final String TAG = "FvOcrBridge";

    private static final String[] PUBLIC_LIB_LISTS = {
            "/vendor/etc/public.libraries.txt",
            "/system/etc/public.libraries.txt",
            "/product/etc/public.libraries.txt",
            "/system_ext/etc/public.libraries.txt",
    };

    /** /proc/self/maps 里关心的库关键字。 */
    private static final String[] MAP_KEYWORDS = {
            "libOpenCL", "libGLES_mali", "libPVROCL", "libvndksupport",
            "libneuron", "libcutils", "libhidlbase", "libapuware",
    };

    private LibProbe() {
    }

    /** 供界面 / shell 展示的完整报告（纯文件读取，不加载任何 so）。 */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== vendor 库可见性探测（:ocr 进程）=====\n");

        sb.append("\n--- 系统公开原生库清单 ---\n");
        boolean anyList = false;
        for (String path : PUBLIC_LIB_LISTS) {
            File f = new File(path);
            if (!f.canRead()) {
                continue;
            }
            anyList = true;
            sb.append("[").append(path).append("]\n");
            String content = readAll(path);
            if (content.isEmpty()) {
                sb.append("  (空或不可读)\n");
            } else {
                for (String line : content.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    boolean hit = t.contains("OpenCL") || t.contains("GLES_mali")
                            || t.contains("PVROCL") || t.contains("vndksupport")
                            || t.contains("cutils") || t.contains("neuron");
                    sb.append(hit ? "  ★ " : "    ").append(t).append('\n');
                }
            }
        }
        if (!anyList) {
            sb.append("  (均不可读：普通 App 无法读取 public.libraries.txt)\n");
        }

        sb.append("\n--- 关键库文件是否存在 ---\n");
        String[] candidates = {
                "/system/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/system/vendor/lib64/egl/libGLES_mali.so",
                "/system/lib64/egl/libGLES_mali.so",
                "/vendor/lib64/libOpenCL.so",
                "/vendor/lib64/egl/libGLES_mali.so",
                "/system/vendor/lib64/libPVROCL.so",
        };
        for (String p : candidates) {
            sb.append(new File(p).exists() ? "  ✓ " : "  · ").append(p).append('\n');
        }

        sb.append("\n--- 已映射到本进程的 GPU/OpenCL 相关库（/proc/self/maps）---\n");
        String maps = readAll("/proc/self/maps");
        int hits = 0;
        if (maps.isEmpty()) {
            sb.append("  (不可读)\n");
        } else {
            for (String line : maps.split("\n")) {
                for (String k : MAP_KEYWORDS) {
                    if (line.contains(k)) {
                        sb.append("  ").append(line.trim()).append('\n');
                        hits++;
                        break;
                    }
                }
            }
            if (hits == 0) {
                sb.append("  (尚无——引擎未初始化或库未加载)\n");
            }
        }
        return sb.toString();
    }

    /**
     * 主动把 {@code libOpenCL.so} 加载进本进程。
     *
     * <p>作用有三：① 验证 {@code uses-native-library} 声明是否真的放行了该库；
     * ② 若加载成功，linker 对同 SONAME 的后续 dlopen 会直接复用已加载的 handle，
     * 有利于 MACE 绕过 namespace 的查找限制；③ 提供绝对路径兜底，覆盖
     * 「库存在但不在 public.libraries.txt 里、声明被系统忽略」的机型。
     *
     * <p>只加载 OpenCL ICD loader（不含 glog 断言，不会主动 abort）；每一步都
     * try-catch，失败仅记录文字。整个流程运行在 {@code :ocr} 子进程。
     */
    public static String preloadOpenCL() {
        StringBuilder sb = new StringBuilder();

        // ① 依赖 uses-native-library 的标准加载（v1.0.3 新增声明）
        try {
            System.loadLibrary("OpenCL");
            sb.append("  ✓ System.loadLibrary(\"OpenCL\") 成功 → uses-native-library 已生效\n");
            Log.i(TAG, "loadLibrary OpenCL OK");
            return sb.toString();
        } catch (Throwable t) {
            sb.append("  · System.loadLibrary(\"OpenCL\"): ")
                    .append(t.getClass().getSimpleName())
                    .append(t.getMessage() == null ? "" : ": " + t.getMessage())
                    .append('\n');
        }

        // ② 绝对路径兜底：MACE 硬编码的候选路径 + Treble 实际路径
        String[] paths = {
                "/system/vendor/lib64/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/vendor/lib64/libOpenCL.so",
                "/system/vendor/lib64/egl/libGLES_mali.so",
                "/system/lib64/egl/libGLES_mali.so",
                "/vendor/lib64/egl/libGLES_mali.so",
        };
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                continue;
            }
            try {
                System.load(p);
                sb.append("  ✓ System.load(\"").append(p)
                        .append("\") 成功 → 已加载 OpenCL/GPU 实现\n");
                Log.i(TAG, "System.load " + p + " OK");
                return sb.toString();
            } catch (Throwable t) {
                sb.append("  · System.load(\"").append(p).append("\"): ")
                        .append(t.getClass().getSimpleName())
                        .append(t.getMessage() == null ? "" : ": " + t.getMessage())
                        .append('\n');
            }
        }
        sb.append("  ✗ 未能加载任何 OpenCL 实现：vendor 库对本进程不可访问\n");
        return sb.toString();
    }

    private static String readAll(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null && lines < 4000) {
                sb.append(line).append('\n');
                lines++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
