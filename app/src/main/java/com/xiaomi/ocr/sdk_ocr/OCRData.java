package com.xiaomi.ocr.sdk_ocr;

import android.graphics.Bitmap;

/**
 * 复刻自小米相册 classes3.dex 的 {@code com.xiaomi.ocr.sdk_ocr.OCRData}。
 *
 * <p><b>不要改包名、类名、字段名、字段类型。</b>
 * {@code libmiocr_wrapper.so} 会用 JNI {@code FindClass("com/xiaomi/ocr/sdk_ocr/OCRData$OCRResult")}
 * 以及 {@code GetFieldID(..., "total_text", "Ljava/lang/String;")} 之类的硬编码字符串
 * 创建并填充返回对象。名字对不上会直接抛 {@code NoSuchFieldError} / 返回 null。
 *
 * <p>原 SDK 里这些类都是 static nested class（smali 中没有 this$0 字段），
 * 这里保持一致。字段全部 public 且非 final，供 native 层直接赋值。
 */
public class OCRData {

    public static final int ENTITY_TYPE_WEBSITE = 0;
    public static final int ENTITY_TYPE_PHONE_NUMBER = 1;
    public static final int ENTITY_TYPE_EMAIL = 2;
    public static final int ENTITY_TYPE_DELIVERY_NUMBER = 3;
    public static final int ERR_CODE_NOT_INIT = -1;
    public static final int ERR_CODE_MODEL_FAIL = -2;

    public OCRData() {
    }

    /** 区域检测对焦点（归一化坐标）。 */
    public static class FocusPoint {
        public float norm_x;
        public float norm_y;
        public int type;

        public FocusPoint() {
        }
    }

    /** 文本框：box 为 [x1,y1,x2,y2]，poly 为四点八坐标。 */
    public static class Location {
        public float[] box;
        public float[] poly;

        public Location() {
        }
    }

    /** 单字结果。 */
    public static class OCRChar {
        public float char_confidence;
        public String char_text;
        public Location location;

        public OCRChar() {
        }
    }

    /** 实体（网址 / 电话 / 邮箱 / 快递单号）。 */
    public static class OCREntity {
        public int end_index;
        public String entity_text;
        public int entity_type;
        public int line_id;
        public Location location;
        public int start_index;

        public OCREntity() {
        }
    }

    /** 单行结果。注意字段名 {@code Character} 首字母大写，原样保留。 */
    public static class OCRLine {
        public OCRChar[] Character;
        public float line_confidence;
        public int line_id;
        public String line_text;
        public Location location;

        public OCRLine() {
        }
    }

    /** 段落结果。 */
    public static class OCRParagraph {
        public OCRLine[] lines;
        public String paragraph_text;

        public OCRParagraph() {
        }
    }

    /** doOCR 的返回对象：{@code total_text} 就是整段识别文字。 */
    public static class OCRResult {
        public OCREntity[] entities;
        public OCRParagraph[] paragraphs;
        public String total_text;

        public OCRResult() {
        }
    }

    /** 区域识别取图结果。 */
    public static class RegionData {
        public Bitmap regionImg;
        public Location regionLoc;
        public Location[] texts_locations;

        public RegionData() {
        }
    }

    /** 区域检测结果。 */
    public static class RegionDetectResult {
        public long nextTimestamp;
        public Location regionLoc;

        public RegionDetectResult() {
        }
    }

    /** 相机帧 YUV 数据。 */
    public static class YUVImage {
        public boolean is_dump;
        public int rotate;
        public long timestamp;
        public byte[] yuv_data;
        public int yuv_height;
        public int yuv_width;

        public YUVImage() {
        }
    }
}
