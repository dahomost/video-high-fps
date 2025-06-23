package com.daho.videohighfps;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.util.Log;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.vision.common.InputImage;
//import com.google.mlkit.vision.face.*;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

public class onnxPreChecking {

    private final FeedbackHelper feedbackHelper;
    private static final String TAG = "onnxPreChecking";

    private final Handler lightCheckHandler = new Handler(Looper.getMainLooper());
    private boolean wasDarkBefore = false;
    private boolean lightingCheckRunning = false;

    private final PoseDetector poseDetector;
    private Pose latestPose; // Variable to hold the latest pose

    // ✅ Centralized TTS messages
    private final static class phrases {
        static final String TOO_DARK = "Lighting is too low... Please..., brighten the area.";
        static final String LIGHT_OK = "Light is OK... ... Good to go.";
        static final String TOO_CLOSE = "You're too close... ... Step back slightly.";
        static final String OFF_CENTER = "You're not centered... Please..., align yourself to the center of the frame.";
        static final String FACE_OK = "Great position... ... Stay still and get ready.";
    }

    public onnxPreChecking(Context context) {
        this.feedbackHelper = new FeedbackHelper(context);

        // ✅ Initialize PoseDetector (stream mode for live preview)
        PoseDetectorOptions options = new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build();

        poseDetector = PoseDetection.getClient(options);
    }

    public void cleanup() {
        feedbackHelper.shutdown();
        stopReactiveLightingCheck();
        poseDetector.close();
    }

    // ✅ TTS feedback methods
    public void sayTooDarkWarning() {
        Log.d(TAG, "🗣️ Triggering TTS: TOO DARK");
        feedbackHelper.speakWithBeeps(phrases.TOO_DARK, 2, 1000, () -> Log.d("TTS", "Now ready for next action"));
    }

    public void sayLightIsGood() {
        Log.d(TAG, "🗣️ Triggering TTS: LIGHT OK");
        feedbackHelper.speakWithBeeps(phrases.LIGHT_OK, 1, 1000, () -> Log.d("TTS", "Now ready for next action"));
    }

    // ------------------------------------------------------------------

    // ✅ Check lighting every 200ms
    public void startReactiveLightingCheck(TextureView textureView) {
        if (lightingCheckRunning)
            return;
        lightingCheckRunning = true;

        lightCheckHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!lightingCheckRunning || textureView == null || !textureView.isAvailable())
                    return;

                int brightness = calculateAverageBrightness(textureView);
                Log.d(TAG, "🔁 Lighting check - Brightness: " + brightness + " | wasDarkBefore: " + wasDarkBefore);

                if (brightness != -1) {
                    if (brightness < 60 && !wasDarkBefore) {
                        wasDarkBefore = true;
                        sayTooDarkWarning();
                    } else if (brightness >= 60 && wasDarkBefore) {
                        wasDarkBefore = false;
                        sayLightIsGood();
                    }
                }

                lightCheckHandler.postDelayed(this, 200);
            }
        });
    }

    // ------------------------------------------------------------------

    public void stopReactiveLightingCheck() {
        lightingCheckRunning = false;
        lightCheckHandler.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------

    // ✅ Lighting estimation from preview
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

    // ------------------------------------------------------------------
    // ✅ Pose detection from TextureView bitmap + spoken feedback
    public void detectPoseFromPreview(TextureView textureView) {
        Log.d(TAG, "started -  pose check -------------------------------------------");
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not available for pose detection");
            return;
        }

        Bitmap bitmap = textureView.getBitmap();
        if (bitmap == null) {
            Log.w(TAG, "Failed to get bitmap from TextureView");
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        poseDetector.process(image)
                .addOnSuccessListener(pose -> {
                    // Log.d(TAG, "✅ precheck --> Pose detected: " +
                    // pose.getAllPoseLandmarks().size() + " landmarks");

                    // Log pose landmarks
                    for (PoseLandmark landmark : pose.getAllPoseLandmarks()) {
                        float x = landmark.getPosition().x;
                        float y = landmark.getPosition().y;
                        int type = landmark.getLandmarkType();
                        Log.d(TAG, "📍 Landmark type " + type + " at x=" + x + ", y=" + y);
                    }

                    // ✅ Store the latest pose
                    updateLatestPose(pose);

                    // ✅ Analyze the pose and provide feedback
                    int width = textureView.getWidth();
                    int height = textureView.getHeight();
                    analyzePoseAndSpeak(pose, width, height);
                })
                .addOnFailureListener(e -> Log.e(TAG, "❌ Pose detection failed: " + e.getMessage()));
    }

    // ✅ Analyze the pose and provide spoken feedback
    private void analyzePoseAndSpeak(Pose pose, int previewWidth, int previewHeight) {
        if (pose == null || pose.getAllPoseLandmarks().isEmpty()) {
            Log.w(TAG, "No landmarks detected or pose is invalid.");
            sayPoseNotDetected(); // Trigger feedback if no pose found
            return;
        }

        // Initialize bounding box coordinates for the detected pose
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;

        for (PoseLandmark landmark : pose.getAllPoseLandmarks()) {
            float x = landmark.getPosition().x;
            float y = landmark.getPosition().y;

            // Find bounding box coordinates (min/max)
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        // Calculate center of bounding box
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;

        // Define the valid boundary for the person to be centered inside
        int boundaryLeft = previewWidth / 3 + 50; // The boundary's left position
        int boundaryTop = previewHeight / 3 + 50; // The boundary's top position
        int boundaryRight = 2 * previewWidth / 3 - 50; // The boundary's right position
        int boundaryBottom = 2 * previewHeight / 3 - 50; // The boundary's bottom position

        boolean isCentered = centerX > boundaryLeft && centerX < boundaryRight && centerY > boundaryTop
                && centerY < boundaryBottom;

        // Calculate pose bounding box width
        float bboxWidth = maxX - minX;

        boolean isTooClose = bboxWidth > previewWidth * 0.6; // Check if the person is too close

        // Log to see if these conditions are met
        Log.d(TAG, "Centered: " + isCentered + ", Too Close: " + isTooClose);

        if (!isCentered) {
            Log.d(TAG, "Your Position is off-center.");
            sayCenterYourselfWarning(); // Give feedback if not centered
        } else if (isTooClose) {
            Log.d(TAG, "Your position is in the center.");
            sayMoveBackWarning(); // Give feedback if too close
        } else {
            Log.d(TAG, "Your position is ok, start the recording");
            sayFaceOK(); // Give feedback if the person is in the correct position
        }
    }

    // Sample feedback methods
    private void sayCenterYourselfWarning() {
        feedbackHelper.speakWithBeeps("You're not centered... Please align yourself to the center of the frame.", 2,
                500, () -> {
                    Log.d("TTS", "Now ready for next action");
                });
    }

    private void sayMoveBackWarning() {
        feedbackHelper.speakWithBeeps("You're too close... Step back slightly.", 2, 1500, () -> {
            Log.d("TTS", "Now ready for next action");
        });
    }

    private void sayFaceOK() {
        feedbackHelper.speakWithBeeps("You are in a good position... Don't move and be read for the recording.", 1,
                1500, () -> {
                    Log.d("TTS", "Now ready for next action");
                });
    }

    private void sayPoseNotDetected() {
        feedbackHelper.speakWithBeeps("Player not detected... Please make sure your full body is visible in the frame.",
                2, 1500, () -> {
                    Log.d("TTS", "Now ready for next action");
                });
    }

    // Method to update the latest pose
    public void updateLatestPose(Pose pose) {
        this.latestPose = pose;
    }

    // Method to retrieve the latest pose
    public Pose getLatestPose() {
        return latestPose;
    }
}
