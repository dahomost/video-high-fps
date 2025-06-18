package com.daho.videohighfps;

import android.graphics.Bitmap;
import android.view.TextureView;
import android.util.Log;
import android.content.Context;

public class onnxPreChecking {

    private final FeedbackHelper feedbackHelper;
    private static final String TAG = "onnxPreChecking";

    public onnxPreChecking(Context context) {
        this.feedbackHelper = new FeedbackHelper(context);
    }

    public void sayTooDarkWarning() {
        feedbackHelper.speakWithBeeps("Too dark, please adjust your lighting", 2);
    }

    public void sayLightIsGood() {
        feedbackHelper.speakWithBeeps("Light ok, good to go", 1);
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

        Bitmap bitmap = textureView.getBitmap(100, 100); // Small sample
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
