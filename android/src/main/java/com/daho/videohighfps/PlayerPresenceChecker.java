package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.TextureView;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.*;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class PlayerPresenceChecker {
    private static final String TAG = "TPA ONNX";
    private static final int CHECK_INTERVAL_MS = 2500;
    private static boolean loopRunning = false;
    private static boolean isTtsReady = false;
    private static boolean isSpeaking = false;
    private static boolean wasDarkBefore = false;
    private static boolean lightingWasGood = false;
    private static boolean playerWasDetected = false;
    private static TextToSpeech tts;
    private static Handler handler;
    private static TextureView textureView;
    private static Context context;
    private static boolean wasPlayerDetectedBefore = false;

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

        if (handler != null) {
            handler.removeCallbacksAndMessages(null); // 🔴 cancel all delayed tasks
        }

        if (tts != null) {
            tts.stop(); // 🔴 stop any current speech
            tts.shutdown(); // 🔴 fully release TTS
            tts = null;
            isTtsReady = false;
        }

        ttsQueue.clear(); // ✅ clear any queued messages
        isSpeaking = false; // ✅ reset flag
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

    private static int previousBrightness = -1;

    private static void performCheck() {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not ready");
            return;
        }

        Bitmap fullBitmap = textureView.getBitmap();
        if (fullBitmap == null) {
            Log.w(TAG, "Failed to get bitmap for brightness check");
            return;
        }

        Bitmap lightingBitmap = Bitmap.createScaledBitmap(fullBitmap, 100, 100, false);
        int brightness = calculateBrightness(lightingBitmap);
        lightingBitmap.recycle();

        Log.d(TAG, "🔆 Brightness: " + brightness);

        // 1️⃣ DARK lighting: say once and skip pose
        if (brightness < 60) {
            if (!wasDarkBefore) {
                clearTts();
                speakOnce("It's too dark");
                wasDarkBefore = true;
            }
            fullBitmap.recycle();
            return;
        }

        // 2️⃣ Lighting became OK
        if (wasDarkBefore) {
            clearTts();
            speakOnce("Lighting is ok");
            wasDarkBefore = false;
        }

        // 3️⃣ Check lighting stability
        if (previousBrightness > 0) {
            int min = previousBrightness - 10;
            int max = previousBrightness + 5;
            if (brightness < min || brightness > max) {
                Log.d(TAG, "⏸️ Waiting for stable lighting before pose detection...");
                previousBrightness = brightness;
                fullBitmap.recycle();
                return;
            }
        }

        previousBrightness = brightness; // update after stable

        // 4️⃣ Pose detection
        Bitmap poseBitmap = fullBitmap.copy(Bitmap.Config.ARGB_8888, true);
        fullBitmap.recycle();

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

    // ✅ Checks if player joints are present inside red rectangle zone
    private static boolean isPlayerInFrame(Pose pose) {
        PoseLandmark leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

        if (leftShoulder == null || rightShoulder == null || leftHip == null || rightHip == null) {
            Log.w(TAG, "🔍 Missing landmarks: "
                    + (leftShoulder == null ? "LeftShoulder " : "")
                    + (rightShoulder == null ? "RightShoulder " : "")
                    + (leftHip == null ? "LeftHip " : "")
                    + (rightHip == null ? "RightHip" : ""));
            return false;
        }

        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();

        float zoneLeft = viewWidth / 3f;
        float zoneRight = viewWidth * 2f / 3f;
        float zoneTop = viewHeight / 3f;
        float zoneBottom = viewHeight * 2f / 3f;

        Log.d(TAG, String.format("📐 Zone: L=%.1f, T=%.1f, R=%.1f, B=%.1f",
                zoneLeft, zoneTop, zoneRight, zoneBottom));

        return logAndCheckZone(leftShoulder, "LeftShoulder", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(rightShoulder, "RightShoulder", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(leftHip, "LeftHip", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(rightHip, "RightHip", zoneLeft, zoneTop, zoneRight, zoneBottom);
    }

    // ✅ Helper to Logs and checks if landmark is inside the red rectangle zone
    private static boolean logAndCheckZone(PoseLandmark landmark, String name,
            float left, float top, float right, float bottom) {
        float x = landmark.getPosition().x;
        float y = landmark.getPosition().y;

        boolean inside = x >= left && x <= right && y >= top && y <= bottom;
        Log.d(TAG, String.format("📍 %s: (%.1f, %.1f) → %s", name, x, y, inside ? "✅ inside" : "❌ outside"));
        return inside;
    }

    private static boolean isInsideZone(PoseLandmark lm, float left, float top, float right, float bottom) {
        float x = lm.getPosition().x;
        float y = lm.getPosition().y;
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static final Queue<String> ttsQueue = new LinkedList<>();

    private static void speakOnce(String message) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready - Skipping: " + message);
            return;
        }

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
                speakOnce(nextMessage);
            }
        }, 4000);
    }

    private static void clearTts() {
        ttsQueue.clear();
        isSpeaking = false;
    }
}
