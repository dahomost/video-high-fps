package com.daho.videohighfps.lib.onnx;

import android.graphics.PointF;
import android.util.Log;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.Arrays;
import java.util.List;

public class PoseValidator {

    private static final String TAG = "✅ PoseValidator";

    // Main method to analyze pose
    public boolean isPoseCentered(Pose pose) {
        PoseLandmark nose = pose.getPoseLandmark(PoseLandmark.NOSE);
        if (nose == null) return false;

        float x = nose.getPosition().x;
        return x >= 100 && x <= 600; // Adjust based on your frame center zone
    }

    public boolean isPoseTooClose(Pose pose) {
        float distance = getAverageShoulderDistance(pose);
        return distance > 400; // Adjust threshold
    }

    public boolean isPoseTooFar(Pose pose) {
        float distance = getAverageShoulderDistance(pose);
        return distance < 100; // Adjust threshold
    }

    public boolean hasVisibleUpperBody(Pose pose) {
        return getAllUpperLandmarks().stream()
                .allMatch(type -> pose.getPoseLandmark(type) != null);
    }

    private float getAverageShoulderDistance(Pose pose) {
        PoseLandmark left = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark right = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        if (left == null || right == null) return 0;
        float dx = right.getPosition().x - left.getPosition().x;
        float dy = right.getPosition().y - left.getPosition().y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private List<Integer> getAllUpperLandmarks() {
        return Arrays.asList(
                PoseLandmark.NOSE,
                PoseLandmark.LEFT_EYE,
                PoseLandmark.RIGHT_EYE,
                PoseLandmark.LEFT_SHOULDER,
                PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW,
                PoseLandmark.RIGHT_ELBOW
        );
    }
}
