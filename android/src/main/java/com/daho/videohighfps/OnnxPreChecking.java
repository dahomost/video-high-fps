package com.daho.videohighfps;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;
import android.view.TextureView;

import com.daho.videohighfps.lib.onnx.FeedbackHelper;
import com.daho.videohighfps.lib.onnx.LightingAnalyzer;
import com.daho.videohighfps.lib.onnx.PoseValidator;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

public class OnnxPreChecking {

    private static final String TAG = "✅ OnnxPreChecking";

    private static PoseValidator poseValidator;
    private static LightingAnalyzer lightingAnalyzer;
    private static FeedbackHelper feedbackHelper;
    private static PoseDetector poseDetector;

    private static boolean isLooping = false;

    public static void startMonitoring(TextureView textureView, Activity activity, Handler handler) {
        Log.d(TAG, "🧠 startMonitoring()");

        // Initialize helpers
        poseValidator = new PoseValidator();
        lightingAnalyzer = new LightingAnalyzer();
        feedbackHelper = new FeedbackHelper(activity);

        AccuratePoseDetectorOptions options = new AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build();

        poseDetector = PoseDetection.getClient(options);

        isLooping = true;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isLooping)
                    return;

                Bitmap bitmap = textureView.getBitmap();
                if (bitmap != null) {
                    analyzeFrame(bitmap);
                } else {
                    Log.w(TAG, "⚠️ TextureView bitmap is null");
                }

                handler.postDelayed(this, 2500); // Loop every 2.5s
            }
        });
    }

    private static void analyzeFrame(Bitmap bitmap) {
        if (!lightingAnalyzer.isLightingGood(bitmap)) {
            feedbackHelper.speak("It's too dark. Improve lighting.");
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        poseDetector.process(image)
                .addOnSuccessListener(pose -> {
                    if (!poseValidator.hasVisibleUpperBody(pose)) {
                        feedbackHelper.speak("Move into the frame. I can't see your body.");
                        return;
                    }

                    if (poseValidator.isPoseTooClose(pose)) {
                        feedbackHelper.speak("You're too close. Step back.");
                    } else if (poseValidator.isPoseTooFar(pose)) {
                        feedbackHelper.speak("You're too far. Step closer.");
                    } else if (!poseValidator.isPoseCentered(pose)) {
                        feedbackHelper.speak("Please center yourself in the frame.");
                    } else {
                        feedbackHelper.speak("Perfect. You're good to go!");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Pose detection failed", e);
                });
    }

    public static void stopMonitoring() {
        Log.d(TAG, "🛑 stopMonitoring()");
        isLooping = false;

        if (poseDetector != null) {
            poseDetector.close();
            poseDetector = null;
        }

        if (feedbackHelper != null) {
            feedbackHelper.shutdown();
            feedbackHelper = null;
        }
    }
}
