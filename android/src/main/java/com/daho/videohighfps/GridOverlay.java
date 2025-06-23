package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class GridOverlay extends View {
    private final Paint paint = new Paint();

    public GridOverlay(Context context) {
        super(context);
        init();
    }

    public GridOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setColor(0xFF000000); // solid black
        paint.setStrokeWidth(3f);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        // Vertical lines
        canvas.drawLine(width / 3f, 0, width / 3f, height, paint);
        canvas.drawLine(2 * width / 3f, 0, 2 * width / 3f, height, paint);

        // Horizontal lines
        canvas.drawLine(0, height / 3f, width, height / 3f, paint);
        canvas.drawLine(0, 2 * height / 3f, width, 2 * height / 3f, paint);
    }
}
