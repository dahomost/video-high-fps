package com.daho.videohighfps;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.util.Log;
import android.content.Context;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetectorOptions;
import java.util.List;

public class onnxPreChecking {

    private final FeedbackHelper feedbackHelper;
    private static final String TAG = "onnxPreChecking";

    // ✅ Centralized TTS messages with punctuation for clarity
    private final static class phrases {
        static final String TOO_DARK = "Lighting is too low... Please, brighten the area.";
        static final String LIGHT_OK = "Light is OK... Good to go.";
        static final String TOO_CLOSE = "You're too close... Step back slightly.";
        static final String OFF_CENTER = "You're not centered... Please, align yourself to the center of the frame.";
        static final String FACE_OK = "Great position... Stay still and get ready.";
        static final String FACE_NOT_FOUND = "Face not detected... Please walk back... and make sure your face is visible.";
    }

    public onnxPreChecking(Context context) {
        this.feedbackHelper = new FeedbackHelper(context);
    }

    public void sayTooDarkWarning() {
        feedbackHelper.speakWithBeeps(phrases.TOO_DARK, 2, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayLightIsGood() {
        feedbackHelper.speakWithBeeps(phrases.LIGHT_OK, 1, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayMoveBackWarning() {
        feedbackHelper.speakWithBeeps(phrases.TOO_CLOSE, 2, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayCenterYourselfWarning() {
        feedbackHelper.speakWithBeeps(phrases.OFF_CENTER, 2, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayFaceNotFound() {
        feedbackHelper.speakWithBeeps(phrases.FACE_NOT_FOUND, 2, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayFaceOK() {
        feedbackHelper.speakWithBeeps(phrases.FACE_OK, 1, 500, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void cleanup() {
        feedbackHelper.shutdown();
    }

    public void checkLighting(TextureView textureView) {
        int brightness = calculateAverageBrightness(textureView);
        Log.d(TAG, "Brightness: " + brightness);

        if (brightness == -1)
            return;

        if (brightness < 60) {
            sayTooDarkWarning();
        } else {
            sayLightIsGood();
        }
    }

    private int calculateAverageBrightness(TextureView textureView) {
        if (textureView == null || !textureView.isAvailable())
            return -1;

        Bitmap bitmap = textureView.getBitmap(100, 100);
        if (bitmap == null)
            return -1;

        long sum = 0;
        int count = 0;

        for (int x = 0; x < bitmap.getWidth(); x++) {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                sum += (r + g + b) / 3;
                count++;
            }
        }

        bitmap.recycle();
        return (int) (sum / count);
    }

    public void checkFacePosition(TextureView textureView) {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not available for face check");
            return;
        }

        Log.d(TAG, "Running face detection...");
        Bitmap bitmap = textureView.getBitmap(480, 480);
        if (bitmap == null) {
            Log.w(TAG, "Failed to capture bitmap from TextureView");
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build();

        FaceDetector detector = FaceDetection.getClient(options);

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    Log.d(TAG, "Detected " + faces.size() + " face(s)");

                    if (faces.isEmpty()) {
                        Log.d(TAG, "No face detected.");
                        sayFaceNotFound();
                        return;
                    }

                    Face face = faces.get(0);
                    Rect box = face.getBoundingBox();

                    int faceWidth = box.width();
                    int faceCenterX = box.centerX();
                    int frameCenterX = bitmap.getWidth() / 2;

                    Log.d(TAG, "Face width: " + faceWidth + ", CenterX: " + faceCenterX + ", Frame center: "
                            + frameCenterX);

                    if (faceWidth > 250) {
                        Log.d(TAG, "Too close");
                        sayMoveBackWarning();
                    } else if (Math.abs(faceCenterX - frameCenterX) > 100) {
                        Log.d(TAG, "Off-center");
                        sayCenterYourselfWarning();
                    } else {
                        Log.d(TAG, "Face OK");
                        sayFaceOK();
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e));
    }

    public void checkPoseAndMaybeStartRecording(TextureView textureView, Runnable onCenteredAndReady) {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not available for pose check");
            return;
        }

        Bitmap bitmap = textureView.getBitmap();
        if (bitmap == null) {
            Log.w(TAG, "Failed to capture bitmap from TextureView");
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build();

        PoseDetector detector = PoseDetection.getClient(options);

        detector.process(image)
                .addOnSuccessListener(pose -> {
                    List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();
                    if (landmarks.isEmpty()) {
                        feedbackHelper.speakWithBeeps("Body not detected... Please stand in front of the camera.", 2,
                                500, null);
                        return;
                    }

                    Rect personBox = getBoundingBoxFromPose(landmarks);
                    int viewWidth = textureView.getWidth();
                    int viewHeight = textureView.getHeight();

                    if (isInsideCenterGrid(personBox, viewWidth, viewHeight)) {
                        feedbackHelper.speakWithBeeps("You're in the center... Three... Two... One... Go.", 3, 500,
                                onCenteredAndReady);
                    } else {
                        feedbackHelper.speakWithBeeps("You're not centered... Please adjust yourself.", 2, 500, null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Pose detection failed", e);
                });
    }

    private boolean isInsideCenterGrid(Rect box, int width, int height) {
        int cellWidth = width / 3;
        int cellHeight = height / 3;

        Rect centerGrid = new Rect(cellWidth, cellHeight, 2 * cellWidth, 2 * cellHeight);
        return centerGrid.contains(box);
    }

    private Rect getBoundingBoxFromPose(List<PoseLandmark> landmarks) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (PoseLandmark landmark : landmarks) {
            float x = landmark.getPosition().x;
            float y = landmark.getPosition().y;
            left = Math.min(left, (int) x);
            top = Math.min(top, (int) y);
            right = Math.max(right, (int) x);
            bottom = Math.max(bottom, (int) y);
        }

        return new Rect(left, top, right, bottom);
    }
}
