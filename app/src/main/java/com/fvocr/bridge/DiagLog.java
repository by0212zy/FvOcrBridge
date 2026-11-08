package com.fvocr.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 跨进程诊断日志：写在 getFilesDir() 下（主进程与 :ocr 子进程共享同一 app data 目录）。
 *
 * <p>每次进入一个可能触发 native 的步骤前，先 {@code commit()} 一步再执行。
 * 用 FileChannel.force(true) 保证真正落盘 —— native 的 abort()/SIGSEGV 会直接
 * 杀死进程，Java 缓冲区根本来不及 flush，所以必须同步刷盘。
 *
 * <p>标记含义：
 * <ul>
 *   <li>{@code STEP:<name>} 即将执行该步骤（若进程死在这里，lastStep 就是它）</li>
 *   <li>{@code OK:<name>} 该步骤正常返回</li>
 *   <li>{@code DONE} 全部完成</li>
 * </ul>
 */
public final class DiagLog {

    private static final String TAG = "FvOcrBridge";
    private static final String FILE = "miocr_steps.log";
    private static final String PREFIX_STEP = "STEP:";
    private static final String PREFIX_OK = "OK:";
    private static final String DONE = "DONE";

    private DiagLog() {
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    public static synchronized void clear(Context c) {
        try {
            File f = file(c);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable t) {
            Log.w(TAG, "clear failed", t);
        }
    }

    public static synchronized void step(Context c, String name) {
        append(c, PREFIX_STEP + name);
    }

    public static synchronized void ok(Context c, String name, String detail) {
        append(c, PREFIX_OK + name + (detail == null || detail.isEmpty() ? "" : " | " + detail));
    }

    public static synchronized void done(Context c) {
        append(c, DONE);
    }

    /** 追加一行并同步刷盘。 */
    private static void append(Context c, String line) {
        Log.i(TAG, line);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file(c), true);
            fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } catch (Throwable t) {
            Log.w(TAG, "append failed: " + line, t);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读取全部日志（供主进程展示）。 */
    public static synchronized String read(Context c) {
        File f = file(c);
        if (!f.exists()) {
            return "";
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "read failed", t);
            return "";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 最后一次「即将执行」但没 OK 的步骤名；全部完成返回 null。 */
    public static synchronized String pendingStep(Context c) {
        String text = read(c);
        if (text.isEmpty()) {
            return null;
        }
        String lastStep = null;
        boolean finished = false;
        for (String line : text.split("\n")) {
            if (line.startsWith(PREFIX_STEP)) {
                lastStep = line.substring(PREFIX_STEP.length());
                finished = false;
            } else if (line.startsWith(PREFIX_OK)) {
                // ok() 写的是 "OK:<name> | <detail>"，按前缀匹配 name。
                String n = line.substring(PREFIX_OK.length());
                if (lastStep != null && (n.equals(lastStep) || n.startsWith(lastStep + " |"))) {
                    lastStep = null;
                }
            } else if (line.equals(DONE)) {
                finished = true;
                lastStep = null;
            }
        }
        return finished ? null : lastStep;
    }

    /** 供界面显示：最后一次诊断是否完整走完。 */
    public static synchronized boolean isDone(Context c) {
        return read(c).endsWith(DONE + "\n");
    }
}
