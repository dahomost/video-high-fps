package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

public class GridOverlay extends View {
    private static final String TAG = "✅ ONNX overlay -=>";
    private final Paint gridPaint = new Paint();
    private final Paint rectanglePaint = new Paint();
    private final Paint jointPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint boundaryPaint = new Paint();
    private final Paint debugPaint = new Paint();
    private final int[] textureLocation = new int[2];
    private TextureView textureView;
    private Pose currentPose;

    private int poseImageWidth = 1; // prevent division by zero
    private int poseImageHeight = 1;

    public GridOverlay(Context context) {
        super(context);
        init();
    }

    public GridOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.RED);
        gridPaint.setStrokeWidth(2f);
        gridPaint.setStyle(Paint.Style.STROKE);

        rectanglePaint.setColor(Color.WHITE);
        rectanglePaint.setStrokeWidth(3f);
        rectanglePaint.setStyle(Paint.Style.STROKE);

        jointPaint.setColor(Color.CYAN);
        jointPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(32f);

        boundaryPaint.setColor(Color.YELLOW);
        boundaryPaint.setStrokeWidth(4f);
        boundaryPaint.setStyle(Paint.Style.STROKE);

        debugPaint.setColor(Color.GREEN);
        debugPaint.setTextSize(28f);
    }

    public void setTextureView(TextureView textureView) {
        this.textureView = textureView;
        invalidate();
    }

    public void setPose(Pose pose, int imageWidth, int imageHeight) {
        this.currentPose = pose;
        this.poseImageWidth = imageWidth > 0 ? imageWidth : 1;
        this.poseImageHeight = imageHeight > 0 ? imageHeight : 1;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if (textureView == null || !textureView.isAvailable())
            return;

        textureView.getLocationOnScreen(textureLocation);
        int texX = textureLocation[0];
        int texY = textureLocation[1];

        int width = textureView.getWidth();
        int height = textureView.getHeight();

        canvas.save();
        canvas.translate(texX, texY); // Align drawing to texture position

        // ✅ Grid lines
        canvas.drawLine(width / 3f, 0, width / 3f, height, gridPaint);
        canvas.drawLine(2 * width / 3f, 0, 2 * width / 3f, height, gridPaint);
        canvas.drawLine(0, height / 3f, width, height / 3f, gridPaint);
        canvas.drawLine(0, 2 * height / 3f, width, 2 * height / 3f, gridPaint);

        // ✅ Center rectangle
        float rectWidth = width * 0.6f;
        float rectHeight = height * 0.6f;
        float left = (width - rectWidth) / 2;
        float top = (height - rectHeight) / 2;
        float right = left + rectWidth;
        float bottom = top + rectHeight;
        canvas.drawRect(left, top, right, bottom, rectanglePaint);
        canvas.drawText("ZONE", left + 10, top - 10, debugPaint);

        // ✅ Draw pose joints and boundary
        if (currentPose != null) {
            PoseLandmark ls = currentPose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
            PoseLandmark rs = currentPose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
            PoseLandmark lh = currentPose.getPoseLandmark(PoseLandmark.LEFT_HIP);
            PoseLandmark rh = currentPose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

            drawJoint(canvas, ls, "LS");
            drawJoint(canvas, rs, "RS");
            drawJoint(canvas, lh, "LH");
            drawJoint(canvas, rh, "RH");

            Log.d(TAG, "Drawing joints...");

            if (ls != null && rs != null && lh != null && rh != null) {
                float minX = Math.min(Math.min(ls.getPosition().x, rs.getPosition().x),
                        Math.min(lh.getPosition().x, rh.getPosition().x));
                float maxX = Math.max(Math.max(ls.getPosition().x, rs.getPosition().x),
                        Math.max(lh.getPosition().x, rh.getPosition().x));
                float minY = Math.min(Math.min(ls.getPosition().y, rs.getPosition().y),
                        Math.min(lh.getPosition().y, rh.getPosition().y));
                float maxY = Math.max(Math.max(ls.getPosition().y, rs.getPosition().y),
                        Math.max(lh.getPosition().y, rh.getPosition().y));

                // Scale bounding box to view size
                float scaleX = width / (float) poseImageWidth;
                float scaleY = height / (float) poseImageHeight;

                canvas.drawRect(
                        minX * scaleX,
                        minY * scaleY,
                        maxX * scaleX,
                        maxY * scaleY,
                        boundaryPaint);

                canvas.drawText("BOUNDARY", minX * scaleX + 10, minY * scaleY - 10, debugPaint);
            }
        }

        canvas.restore();
    }

    // Helper method to draw a joint with label
    private void drawJoint(Canvas canvas, PoseLandmark landmark, String label) {
        if (landmark == null || textureView == null || !textureView.isAvailable())
            return;

        float viewWidth = textureView.getWidth();
        float viewHeight = textureView.getHeight();

        float scaleX = viewWidth / (float) poseImageWidth;
        float scaleY = viewHeight / (float) poseImageHeight;

        float x = landmark.getPosition().x * scaleX;
        float y = landmark.getPosition().y * scaleY;

        canvas.drawCircle(x, y, 15, jointPaint);
        canvas.drawText(label, x + 10, y - 10, textPaint);

        Log.d(TAG, "📍 Joint " + label + ": (" + x + ", " + y + ")");
    }
}
