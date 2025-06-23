package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

public class GridOverlay extends View {
    private final Paint paint = new Paint();
    private final Paint boundaryPaint = new Paint();

    // Constructor
    public GridOverlay(Context context) {
        super(context);
        init();
    }

    public GridOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    // Initialize paints
    private void init() {
        paint.setColor(0xFF000000); // Solid black for the grid lines
        paint.setStrokeWidth(3f);
        paint.setStyle(Paint.Style.STROKE);

        boundaryPaint.setColor(0xFFFF0000); // Red for the boundary
        boundaryPaint.setStrokeWidth(2f); // Reduced the stroke width for a thinner line
        boundaryPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();

        // Draw the 3x3 grid
        // Vertical lines
        canvas.drawLine(width / 3f, 0, width / 3f, height, paint);
        canvas.drawLine(2 * width / 3f, 0, 2 * width / 3f, height, paint);

        // Horizontal lines
        canvas.drawLine(0, height / 3f, width, height / 3f, paint);
        canvas.drawLine(0, 2 * height / 3f, width, 2 * height / 3f, paint);

        // Increase boundary size by 15%
        int boundaryPadding = 100; // Default padding
        int boundaryLeft = (int) (width / 3 + boundaryPadding);
        int boundaryTop = (int) (height / 3 + boundaryPadding);
        float boundaryRight = (int) (2 * width / 3 - boundaryPadding);
        float boundaryBottom = (int) (2 * height / 3 - boundaryPadding);

        // Increase width and height by 15%
        float newWidth = (boundaryRight - boundaryLeft) * 3.6f; // Increase width by 15%
        float newHeight = (boundaryBottom - boundaryTop) * 2f; //

        // Update boundary coordinates to increase the size by 15%
        boundaryLeft = (int) (boundaryLeft - (newWidth - (boundaryRight - boundaryLeft)) / 2);
        boundaryTop = (int) (boundaryTop - (newHeight - (boundaryBottom - boundaryTop)) / 2);
        boundaryRight = boundaryLeft + newWidth;
        boundaryBottom = boundaryTop + newHeight;

        // Draw boundary rectangle in the center (larger red boundary)
        canvas.drawRect(boundaryLeft, boundaryTop, boundaryRight, boundaryBottom, boundaryPaint);
    }
}
