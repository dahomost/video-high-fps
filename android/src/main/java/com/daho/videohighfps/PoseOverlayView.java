package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

public class PoseOverlayView extends View {
    private Pose currentPose;
    private int viewWidth, viewHeight;

    private final Paint zonePaint = new Paint();
    private final Paint jointPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint boundaryPaint = new Paint();

    public PoseOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        zonePaint.setColor(Color.RED);
        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeWidth(6);

        jointPaint.setColor(Color.CYAN);
        jointPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(32);

        boundaryPaint.setColor(Color.YELLOW);
        boundaryPaint.setStyle(Paint.Style.STROKE);
        boundaryPaint.setStrokeWidth(4);
    }

    public void setPose(Pose pose, int width, int height) {
        this.currentPose = pose;
        this.viewWidth = width;
        this.viewHeight = height;
        invalidate(); // Trigger redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (currentPose == null)
            return;

        float left = viewWidth / 3f;
        float right = viewWidth * 2f / 3f;
        float top = viewHeight / 3f;
        float bottom = viewHeight * 2f / 3f;

        // 🟥 Detection zone
        canvas.drawRect(left, top, right, bottom, zonePaint);

        // 🔵 Joints
        PoseLandmark ls = currentPose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = currentPose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lh = currentPose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rh = currentPose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

        drawJoint(canvas, ls, "LS");
        drawJoint(canvas, rs, "RS");
        drawJoint(canvas, lh, "LH");
        drawJoint(canvas, rh, "RH");

        // 🟨 Bounding box around player (shoulders & hips)
        if (ls != null && rs != null && lh != null && rh != null) {
            float minX = Math.min(Math.min(ls.getPosition().x, rs.getPosition().x),
                    Math.min(lh.getPosition().x, rh.getPosition().x));
            float maxX = Math.max(Math.max(ls.getPosition().x, rs.getPosition().x),
                    Math.max(lh.getPosition().x, rh.getPosition().x));
            float minY = Math.min(Math.min(ls.getPosition().y, rs.getPosition().y),
                    Math.min(lh.getPosition().y, rh.getPosition().y));
            float maxY = Math.max(Math.max(ls.getPosition().y, rs.getPosition().y),
                    Math.max(lh.getPosition().y, rh.getPosition().y));

            canvas.drawRect(minX, minY, maxX, maxY, boundaryPaint);
        }
    }

    private void drawJoint(Canvas canvas, PoseLandmark landmark, String label) {
        if (landmark == null)
            return;

        float x = landmark.getPosition().x;
        float y = landmark.getPosition().y;

        canvas.drawCircle(x, y, 15, jointPaint);
        canvas.drawText(label, x + 10, y - 10, textPaint);
    }
}
