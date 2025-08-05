package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.TextureView;
import java.util.Queue;
import java.util.LinkedList;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.*;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

import java.util.Locale;

public class PlayerPresenceChecker {
    private static final String TAG = "TPA ONNX";
    private static final int CHECK_INTERVAL_MS = 2500;
    private static boolean loopRunning = false;
    private static boolean isTtsReady = false;
    private static boolean isSpeaking = false;

    private static TextToSpeech tts;
    private static Handler handler;
    private static TextureView textureView;
    private static Context context;

    public static void startMonitoring(TextureView tv, Context ctx, Handler bgHandler) {
        if (loopRunning)
            return;

        textureView = tv;
        context = ctx;
        handler = bgHandler;
        loopRunning = true;

        initTTS();
        scheduleNextCheck();
    }

    public static void stopMonitoring() {
        loopRunning = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private static void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED;
                Log.d(TAG, "TTS initialized: " + isTtsReady);
            }
        });
    }

    private static void scheduleNextCheck() {
        handler.postDelayed(() -> {
            if (!loopRunning)
                return;
            performCheck();
            scheduleNextCheck();
        }, CHECK_INTERVAL_MS);
    }

    private static void performCheck() {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not ready");
            return;
        }

        // 🔆 Lighting check
        Bitmap lightingBitmap = textureView.getBitmap(100, 100);
        if (lightingBitmap == null) {
            Log.w(TAG, "Failed to get bitmap for brightness check");
            return;
        }

        int brightness = calculateBrightness(lightingBitmap);
        Log.d(TAG, "🔆 Brightness: " + brightness);
        lightingBitmap.recycle();

        if (brightness < 60) {
            speakOnce("It's too dark");
            return;
        } else {
            speakOnce("Lighting is ok");
        }

        // 🧍 Pose detection (make a copy to prevent recycling error)
        Bitmap originalBitmap = textureView.getBitmap();
        if (originalBitmap == null) {
            Log.w(TAG, "Failed to get pose bitmap");
            return;
        }

        Bitmap poseBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true);
        originalBitmap.recycle(); // Only recycle the temp original

        InputImage image = InputImage.fromBitmap(poseBitmap, 0);

        AccuratePoseDetectorOptions options = new AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                .build();

        PoseDetector detector = PoseDetection.getClient(options);

        detector.process(image)
                .addOnSuccessListener(pose -> {
                    boolean playerDetected = isPlayerInFrame(pose);
                    if (playerDetected) {
                        speakOnce("You're detected, good to start recording");
                    } else {
                        speakOnce("You're not present in the frame");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Pose detection failed", e);
                });
    }

    private static int calculateBrightness(Bitmap bitmap) {
        long totalBrightness = 0;
        int pixels = bitmap.getWidth() * bitmap.getHeight();
        int[] pixelArray = new int[pixels];
        bitmap.getPixels(pixelArray, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        for (int color : pixelArray) {
            int r = (color >> 16) & 0xff;
            int g = (color >> 8) & 0xff;
            int b = color & 0xff;
            totalBrightness += (r + g + b) / 3;
        }

        return (int) (totalBrightness / pixels);
    }

    private static boolean isPlayerInFrame(Pose pose) {
        // Basic heuristic: check if major joints are detected
        PoseLandmark leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

        return leftShoulder != null && rightShoulder != null &&
                leftHip != null && rightHip != null;
    }

    private static final Queue<String> ttsQueue = new LinkedList<>();

    private static void speakOnce(String message) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready - Skipping: " + message);
            return;
        }

        // If already speaking, queue the message
        if (tts.isSpeaking() || isSpeaking) {
            ttsQueue.add(message);
            Log.w(TAG, "TTS busy - Queued: " + message);
            return;
        }

        isSpeaking = true;
        Log.d(TAG, "🗣️ TTS: " + message);
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + System.currentTimeMillis());

        handler.postDelayed(() -> {
            isSpeaking = false;
            if (!ttsQueue.isEmpty()) {
                String nextMessage = ttsQueue.poll();
                speakOnce(nextMessage); // Recursively play next
            }
        }, 4000); // adjust cooldown as needed
    }

}
