package com.fvocr.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/** 内置测试图：self_test 用，不需要任何外部图片，只在模块主进程里创建。 */
public final class SampleBitmap {

    private SampleBitmap() {
    }

    public static Bitmap make() {
        Bitmap bmp = Bitmap.createBitmap(1080, 460, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(76);
        c.drawText("FV 识图桥内置引擎", 56, 128, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(58);
        c.drawText("FV OCR Engine 2026", 56, 240, p);
        c.drawText("文字识别测试 ABC 12345", 56, 330, p);
        p.setTextSize(44);
        c.drawText("模块自带引擎 无需第二个 App", 56, 415, p);
        return bmp;
    }
}
