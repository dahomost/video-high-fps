package com.daho.videohighfps;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.util.Log;
import android.content.Context;
import android.graphics.Rect;

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

}
