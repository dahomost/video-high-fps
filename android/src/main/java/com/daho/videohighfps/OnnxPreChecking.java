package com.daho.videohighfps;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.TextureView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.*;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

public class OnnxPreChecking {
    private static final String TAG = "🔊🔊 ONNX check -=>";
    private static final int CHECK_INTERVAL_MS = 4000;

    private static boolean loopRunning = false;
    private static boolean wasDarkBefore = false;
    private static boolean isTtsReady = false;
    private static boolean isSpeaking = false;

    private static int previousBrightness = -1;

    private static TextureView textureView;
    private static Context context;
    private static Handler handler;
    private static TextToSpeech tts;

    private static final Queue<String> ttsQueue = new LinkedList<>();

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
            handler.removeCallbacksAndMessages(null); // ✅ cancel all delayed tasks
        }

        if (tts != null) {
            tts.stop(); // ✅ stop any current speech
            tts.shutdown(); // ✅ fully release TTS
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

    private static void performCheck() {
        if (textureView == null || !textureView.isAvailable()) {
            Log.w(TAG, "TextureView not ready");
            return;
        }

        Bitmap fullBitmap = textureView.getBitmap();
        if (fullBitmap == null) {
            Log.w(TAG, "Failed to get bitmap");
            return;
        }

        Bitmap lightingBitmap = Bitmap.createScaledBitmap(fullBitmap, 100, 100, false);
        int brightness = calculateBrightness(lightingBitmap);
        lightingBitmap.recycle();

        Log.d(TAG, "✅ Brightness: " + brightness);

        if (brightness < 60) {
            if (!wasDarkBefore) {
                clearTts();
                speakOnce("It's too dark");
                wasDarkBefore = true;
            }
            fullBitmap.recycle();
            return;
        }

        if (wasDarkBefore) {
            clearTts();
            speakOnce("Lighting is ok");
            wasDarkBefore = false;
        }

        if (previousBrightness > 0) {
            int min = previousBrightness - 10;
            int max = previousBrightness + 5;
            if (brightness < min || brightness > max) {
                Log.d(TAG, "✅ Waiting for stable lighting...");
                previousBrightness = brightness;
                fullBitmap.recycle();
                return;
            }
        }

        previousBrightness = brightness;

        Bitmap poseBitmap = fullBitmap.copy(Bitmap.Config.ARGB_8888, true);
        fullBitmap.recycle();

        InputImage image = InputImage.fromBitmap(poseBitmap, 0);

        PoseDetector detector = PoseDetection.getClient(
                new AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
                        .build());

        detector.process(image)
                .addOnSuccessListener(pose -> {
                    boolean detected = isPlayerInFrame(pose);

                    // ✅ SAFEGUARD: check for null to avoid crash
                    MainActivity activity = MainActivity.getInstance();
                    if (activity != null) {
                        activity.updatePoseOverlay(pose, poseBitmap.getWidth(), poseBitmap.getHeight());
                    }

                    speakOnce(detected
                            ? "You're detected... good to start recording"
                            : "You're not detected in the frame");
                })
                .addOnFailureListener(e -> Log.e(TAG, "❌ Pose detection failed", e));
    }

    private static int calculateBrightness(Bitmap bitmap) {
        long total = 0;
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        for (int color : pixels) {
            int r = (color >> 16) & 0xff;
            int g = (color >> 8) & 0xff;
            int b = color & 0xff;
            total += (r + g + b) / 3;
        }

        return (int) (total / pixels.length);
    }

    private static boolean isPlayerInFrame(Pose pose) {
        PoseLandmark ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);

        if (ls == null || rs == null || lh == null || rh == null) {
            Log.w(TAG, "Missing landmarks");
            return false;
        }

        int width = textureView.getWidth();
        int height = textureView.getHeight();
        float rectWidth = width * 0.6f;
        float rectHeight = height * 0.6f;
        float zoneLeft = (width - rectWidth) / 2;
        float zoneTop = (height - rectHeight) / 2;
        float zoneRight = zoneLeft + rectWidth;
        float zoneBottom = zoneTop + rectHeight;

        Log.d(TAG, String.format("📐 Zone: L=%.1f, T=%.1f, R=%.1f, B=%.1f",
                zoneLeft, zoneTop, zoneRight, zoneBottom));

        return logAndCheckZone(ls, "LeftShoulder", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(rs, "RightShoulder", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(lh, "LeftHip", zoneLeft, zoneTop, zoneRight, zoneBottom) &&
                logAndCheckZone(rh, "RightHip", zoneLeft, zoneTop, zoneRight, zoneBottom);
    }

    /**
     * Log the position of a landmark and check if it's within the specified zone.
     * Returns true if the landmark is inside the zone, false otherwise.
     */
    private static boolean logAndCheckZone(PoseLandmark lm, String name,
            float left, float top, float right, float bottom) {
        float x = lm.getPosition().x;
        float y = lm.getPosition().y;
        boolean inside = x >= left && x <= right && y >= top && y <= bottom;
        Log.d(TAG, String.format("📍 %s: (%.1f, %.1f) → %s", name, x, y, inside ? "✅ inside" : "❌ outside"));
        return inside;
    }

    /**
     * Speak a message using TTS, ensuring it doesn't overlap with ongoing speech.
     * If TTS is busy, queue the message for later.
     */
    private static void speakOnce(String message) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS not ready - Skipping: " + message);
            return;
        }

        if (tts.isSpeaking() || isSpeaking) {
            ttsQueue.add(message);
            Log.w(TAG, "TTS Queued: " + message);
            return;
        } else {
            Log.d(TAG, "TTS Ready - Speaking: " + message);
        }

        isSpeaking = true;
        Log.d(TAG, "✅ TTS: " + message);
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + System.currentTimeMillis());

        handler.postDelayed(() -> {
            isSpeaking = false;
            if (!ttsQueue.isEmpty()) {
                speakOnce(ttsQueue.poll());
            }
        }, 2000);
    }

    private static void clearTts() {
        ttsQueue.clear();
        isSpeaking = false;
    }
}
