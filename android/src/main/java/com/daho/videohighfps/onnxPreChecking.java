package com.daho.videohighfps;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.util.Log;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class onnxPreChecking {

    private final FeedbackHelper feedbackHelper;
    private static final String TAG = "onnxPreChecking";

    private final Handler lightCheckHandler = new Handler(Looper.getMainLooper());
    private boolean wasDarkBefore = false;
    private boolean lightingCheckRunning = false;

    private final PoseDetector poseDetector;
    private Pose latestPose;

    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private final Context context;

    private final static class phrases {
        static final String TOO_DARK = "Lighting is too low... brighten the area.";
        static final String LIGHT_OK = "Light is OK... ... Good to go.";
        static final String TOO_CLOSE = "You're too close... Step back slightly.";
        static final String OFF_CENTER = "You're not centered..., align yourself to the center of the frame.";
        static final String FACE_OK = "Great position... Stay still and get ready.";
    }

    public onnxPreChecking(Context context) {
        this.feedbackHelper = new FeedbackHelper(context);
        this.context = context;

        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();

        poseDetector = PoseDetection.getClient(options);
    }

    public void cleanup() {
        feedbackHelper.shutdown();
        stopReactiveLightingCheck();
        poseDetector.close();
        inferenceExecutor.shutdownNow(); // 🔒 Ensure thread pool is cleared
    }

    public void sayTooDarkWarning() {
        Log.d(TAG, "🗣️ Triggering TTS: TOO DARK");
        feedbackHelper.speakWithBeeps(phrases.TOO_DARK, 2, 1000, () -> Log.d("TTS", "Next"));
    }

    // With callback
    public void sayLightIsGood(Runnable callback) {
        if (feedbackHelper == null) {
            Log.w(TAG, "⚠️ feedbackHelper was null. Skipping light feedback.");
            if (callback != null)
                callback.run();
            return;
        }
        if (feedbackHelper != null) {
            feedbackHelper.speakWithBeeps("Lighting looks good", 1, 500, callback);
        } else {
            Log.w(TAG, "⚠️ feedbackHelper still null, skipping light feedback.");
            if (callback != null)
                callback.run();
        }
    }

    // No callback fallback ...
    public void sayLightIsGood() {
        sayLightIsGood(() -> {
        });
    }

    public void startReactiveLightingCheck(TextureView textureView) {
        if (lightingCheckRunning || textureView == null || !textureView.isAvailable())
            return;

        lightingCheckRunning = true;

        inferenceExecutor.execute(() -> {
            while (lightingCheckRunning) {
                Bitmap bitmap = textureView.getBitmap(100, 100);
                if (bitmap == null) {
                    Log.w(TAG, "Failed to get bitmap for lighting check");
                    sleep(200);
                    continue;
                }

                try {
                    int brightness = calculateBrightness(bitmap);
                    Log.d(TAG, "🔁 Lighting check - Brightness: " + brightness + " | wasDarkBefore: " + wasDarkBefore);

                    if (brightness < 60 && !wasDarkBefore) {
                        wasDarkBefore = true;
                        sayTooDarkWarning();
                    } else if (brightness >= 60 && wasDarkBefore) {
                        wasDarkBefore = false;
                        sayLightIsGood();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error in lighting check loop", e);
                } finally {
                    bitmap.recycle();
                }

                sleep(200); // Delay before next check
            }
        });
    }

    private int calculateBrightness(Bitmap bitmap) {
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

        return count == 0 ? -1 : (int) (sum / count);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public void stopReactiveLightingCheck() {
        lightingCheckRunning = false;
        lightCheckHandler.removeCallbacksAndMessages(null);
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

    public void detectPoseFromPreview(TextureView textureView) {
        Log.d(TAG, "started -  pose check -------------------------------------------");

        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not available for pose detection");
            return;
        }

        // ✅ Use safe background thread
        inferenceExecutor.execute(() -> {
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap == null) {
                Log.w(TAG, "Failed to get bitmap from TextureView");
                return;
            }

            try {
                InputImage image = InputImage.fromBitmap(bitmap, 0);

                poseDetector.process(image)
                        .addOnSuccessListener(pose -> {
                            // ✅ Log all landmarks
                            for (PoseLandmark landmark : pose.getAllPoseLandmarks()) {
                                float x = landmark.getPosition().x;
                                float y = landmark.getPosition().y;
                                int type = landmark.getLandmarkType();
                                Log.d(TAG, "📍 Landmark type " + type + " at x=" + x + ", y=" + y);
                            }

                            updateLatestPose(pose); // ✅ Save for future use

                            // ✅ Analyze on main thread
                            new Handler(Looper.getMainLooper()).post(() -> {
                                int width = textureView.getWidth();
                                int height = textureView.getHeight();
                                analyzePoseAndSpeak(pose, width, height);
                            });
                        })
                        .addOnFailureListener(e -> Log.e(TAG, "❌ Pose detection failed: " + e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "❌ Pose detection error: " + e.getMessage(), e);
            } finally {
                bitmap.recycle(); // ✅ Always recycle
            }
        });
    }

    private void analyzePoseAndSpeak(Pose pose, int previewWidth, int previewHeight) {
        if (pose == null || pose.getAllPoseLandmarks().isEmpty()) {
            sayPoseNotDetected();
            return;
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (PoseLandmark landmark : pose.getAllPoseLandmarks()) {
            float x = landmark.getPosition().x;
            float y = landmark.getPosition().y;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;

        int boundaryLeft = previewWidth / 3 + 50;
        int boundaryTop = previewHeight / 3 + 50;
        int boundaryRight = 2 * previewWidth / 3 - 50;
        int boundaryBottom = 2 * previewHeight / 3 - 50;

        boolean isCentered = centerX > boundaryLeft && centerX < boundaryRight &&
                centerY > boundaryTop && centerY < boundaryBottom;

        float bboxWidth = maxX - minX;
        boolean isTooClose = bboxWidth > previewWidth * 0.6;

        Log.d(TAG, "Centered: " + isCentered + ", Too Close: " + isTooClose);

        if (!isCentered) {
            sayCenterYourselfWarning();
        } else if (isTooClose) {
            sayMoveBackWarning();
        } else {
            sayFaceOK();
        }
    }

    private void sayCenterYourselfWarning() {
        feedbackHelper.speakWithBeeps(phrases.OFF_CENTER, 2, 500, () -> Log.d("TTS", "Next"));
    }

    private void sayMoveBackWarning() {
        feedbackHelper.speakWithBeeps(phrases.TOO_CLOSE, 2, 1500, () -> Log.d("TTS", "Next"));
    }

    private void sayFaceOK() {
        feedbackHelper.speakWithBeeps(phrases.FACE_OK, 1, 1500, () -> Log.d("TTS", "Next"));
    }

    private void sayPoseNotDetected() {
        feedbackHelper.speakWithBeeps("Player not detected... Please make sure your full body is visible in the frame.",
                2, 1500, () -> Log.d("TTS", "Next"));
    }

    public void speakPoseNotValid(Runnable callback) {
        if (feedbackHelper != null) {
            feedbackHelper.speakWithBeeps("Pose is not valid. Please stand in the center", 2, 500, callback);
        } else {
            Log.w(TAG, "FeedbackHelper not ready, skipping invalid pose feedback.");
            if (callback != null)
                callback.run();
        }
    }

    public void updateLatestPose(Pose pose) {
        this.latestPose = pose;
    }

    public Pose getLatestPose() {
        return latestPose;
    }

}
