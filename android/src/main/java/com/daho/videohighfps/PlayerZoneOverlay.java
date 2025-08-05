package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class PlayerZoneOverlay extends View {
    private final Paint gridPaint = new Paint();
    private final Paint rectanglePaint = new Paint();

    public PlayerZoneOverlay(Context context) {
        super(context);
        init();
    }

    public PlayerZoneOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Grid lines: light red
        gridPaint.setColor(Color.RED);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);

        // Rectangle: gray
        rectanglePaint.setColor(Color.WHITE);
        rectanglePaint.setStrokeWidth(3f);
        rectanglePaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();

        // Draw vertical grid lines (1/3 and 2/3)
        canvas.drawLine(width / 3f, 0, width / 3f, height, gridPaint);
        canvas.drawLine(2 * width / 3f, 0, 2 * width / 3f, height, gridPaint);

        // Draw horizontal grid lines (1/3 and 2/3)
        canvas.drawLine(0, height / 3f, width, height / 3f, gridPaint);
        canvas.drawLine(0, 2 * height / 3f, width, 2 * height / 3f, gridPaint);

        // Draw center red rectangle (60% of screen)
        float rectWidth = width * 0.6f;
        float rectHeight = height * 0.6f;
        float left = (width - rectWidth) / 2;
        float top = (height - rectHeight) / 2;
        float right = left + rectWidth;
        float bottom = top + rectHeight;

        canvas.drawRect(left, top, right, bottom, rectanglePaint);
    }
}
